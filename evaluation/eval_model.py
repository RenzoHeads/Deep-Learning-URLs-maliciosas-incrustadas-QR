#!/usr/bin/env python3
"""
eval_model.py - Evalúa el modelo CANINE-S en conjuntos de prueba externos.

Evalúa un clasificador de URLs de phishing CANINE-S en PyTorch o TFLite sobre
dos conjuntos de benchmark externos (prueba_latam y prueba_generica), calcula
métricas estándar de clasificación binaria, y persiste resultados como JSON +
gráficos PNG.

Uso:
    python eval_model.py \
        --ruta_modelo ./models/canine_s.pt \
        --tipo_modelo pytorch \
        --output_dir ./evaluation/results

Autor: Fase 6 - Detector de Seguridad QR
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")  # backend sin cabeza (headless)
import matplotlib.pyplot as plt
from sklearn.metrics import (
    accuracy_score,
    precision_score,
    recall_score,
    f1_score,
    roc_auc_score,
    average_precision_score,
    confusion_matrix,
    roc_curve,
    precision_recall_curve,
)

# Asegurar que el directorio raiz del proyecto este en sys.path para que
# los imports `from ml_comun...` funcionen sea cual sea el CWD.
_PROYECTO_RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _PROYECTO_RAIZ not in sys.path:
    sys.path.insert(0, _PROYECTO_RAIZ)

from ml_comun.url import clean_url


# ---------------------------------------------------------------------------
# Constantes
# ---------------------------------------------------------------------------
MAX_LEN = 150
ETIQUETA_BENIGNO = 0
ETIQUETA_MALICIOSO = 1

# Ubicaciones por defecto de los conjuntos de prueba (relativas a CWD)
LATAM_BENIGNO = "prueba_latam/benigno_latam_1500.csv"
LATAM_PHISH = "prueba_latam/phishing_latam_1500.csv"
GEN_BENIGNO = "prueba_generica/benigno_generico_1500.csv"
GEN_PHISH = "prueba_generica/phishing_generico_1500.csv"


# ---------------------------------------------------------------------------
# Limpieza de URLs — consolidada en ml_comun.url
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# Carga de datos
# ---------------------------------------------------------------------------
def _load_csv(ruta: str, etiqueta_por_defecto: int) -> pd.DataFrame:
    """Carga un único CSV, conservando solo la URL y asignando la columna label."""
    if not os.path.isfile(ruta):
        raise FileNotFoundError(f"CSV no encontrado: {ruta}")
    df = pd.read_csv(ruta)
    # Detectar columna de URL (nombres flexibles)
    url_col = None
    for cand in ("url", "URL", "Url", "domain", "Domain"):
        if cand in df.columns:
            url_col = cand
            break
    if url_col is None:
        raise ValueError(f"No hay columna url/domain en {ruta}; columnas={list(df.columns)}")
    out = pd.DataFrame({"url": df[url_col].astype(str), "label": etiqueta_por_defecto})
    return out


def cargar_conjunto_prueba(ruta_benigno: str, ruta_phish: str) -> pd.DataFrame:
    """Concatena CSVs benigno (label=0) y phishing (label=1) en un único DataFrame."""
    benigno = _load_csv(ruta_benigno, ETIQUETA_BENIGNO)
    phish = _load_csv(ruta_phish, ETIQUETA_MALICIOSO)
    combined = pd.concat([benigno, phish], ignore_index=True)
    # Aplicar limpieza
    combined["url_clean"] = combined["url"].map(clean_url)
    # Descartar vacíos
    combined = combined[combined["url_clean"].str.len() > 0].reset_index(drop=True)
    return combined


# ---------------------------------------------------------------------------
# Tokenizador CANINE (codepoints Unicode a nivel de caracter)
# ---------------------------------------------------------------------------
def canine_tokenize(url: str, max_len: int = MAX_LEN) -> np.ndarray:
    """Tokeniza una URL en un array de longitud fija de codepoints Unicode.

    CANINE usa codepoints Unicode crudos como IDs de token. Truncamos/rellenamos a max_len.
    Valor de relleno = 0.

    Args:
        url: cadena de URL limpia.
        max_len: longitud máxima de secuencia.

    Returns:
        np.ndarray de shape (max_len,) dtype int32.
    """
    cps = [ord(c) for c in url[:max_len]]
    arr = np.zeros(max_len, dtype=np.int32)
    arr[: len(cps)] = cps
    return arr


def tokenizar_lote(urls: List[str], max_len: int = MAX_LEN) -> np.ndarray:
    """Tokeniza una lista de URLs en un array int32 (N, max_len)."""
    out = np.zeros((len(urls), max_len), dtype=np.int32)
    for i, u in enumerate(urls):
        cps = [ord(c) for c in u[:max_len]]
        out[i, : len(cps)] = cps
    return out


# ---------------------------------------------------------------------------
# Carga del modelo e inferencia
# ---------------------------------------------------------------------------
def cargar_modelo_pytorch(ruta_modelo: str):
    """Carga un checkpoint CANINE-S en PyTorch.

    Devuelve un objeto modelo invocable. Caen a un clasificador heurístico
    determinístico si torch no está disponible para que el script siga siendo
    ejecutable para verificación de métricas/plomería.
    """
    try:
        import torch  # type: ignore
    except ImportError:
        print("[aviso] torch no instalado - usando modelo heurístico de respuesto.",
              file=sys.stderr)
        return None
    try:
        model = torch.load(ruta_modelo, map_location="cpu")
        if hasattr(model, "eval"):
            model.eval()
        return model
    except Exception as exc:
        print(f"[aviso] Falló la carga del modelo torch ({exc}); usando heurístico de respuesto.",
              file=sys.stderr)
        return None


def cargar_modelo_tflite(ruta_modelo: str):
    """Carga un intérprete TFLite. Devuelve None si tflite_runtime no está disponible."""
    try:
        import tflite_runtime.interpreter as tflite  # type: ignore
    except ImportError:
        try:
            import tensorflow.lite as tflite  # type: ignore
        except ImportError:
            print("[aviso] tflite_runtime/tensorflow no instalado - "
                  "usando modelo heurístico de respuesto.", file=sys.stderr)
            return None
    interp = tflite.Interpreter(ruta_modelo=ruta_modelo)
    interp.allocate_tensors()
    return interp


def _heuristic_predict_proba(urls: List[str]) -> np.ndarray:
    """Heurística determinística de palabras clave usada como respuesto cuando no hay
    modelo real disponible. Devuelve P(malicioso) en [0,1]. Codifica señales simples
    de phishing para que las métricas/plomería puedan ejercitarse."""
    palabras_clave = ["login", "signin", "account", "verify", "secure", "update",
               "bank", "paypal", "confirm", "password", "free", "gift",
               "win", "prize", "activate", "suspended", "aml", "transfer"]
    sufijos = ["-login", "-signin", ".tk", ".ml", ".ga", ".cf", ".gq"]
    out = []
    for u in urls:
        lu = u.lower()
        puntaje = 0.0
        puntaje += 0.08 * sum(k in lu for k in palabras_clave)
        puntaje += 0.12 * sum(s in lu for s in sufijos)
        # URLs largas / muchos subdominios
        if lu.count(".") >= 4:
            puntaje += 0.1
        if len(lu) >= 60:
            puntaje += 0.08
        if lu.count("-") >= 3:
            puntaje += 0.05
        # IP como host
        if re.search(r"\d+\.\d+\.\d+\.\d+", lu):
            puntaje += 0.15
        p = float(min(0.95, max(0.05, puntaje)))
        out.append(p)
    return np.array(out, dtype=np.float32)


def _pytorch_predict(model, token_ids: np.ndarray) -> np.ndarray:
    """Ejecuta el modelo PyTorch en un lote de IDs de token y devuelve P(malicioso)."""
    import torch  # type: ignore
    probs = []
    bs = 32
    with torch.no_grad():
        for i in range(0, len(token_ids), bs):
            batch = torch.tensor(token_ids[i:i + bs], dtype=torch.long)
            logits = model(batch)
            # Aceptar (logits,) o (logits, ...) tuplas
            if isinstance(logits, tuple):
                logits = logits[0]
            p = torch.softmax(logits, dim=-1)[:, 1]
            probs.append(p.cpu().numpy())
    return np.concatenate(probs)


def _tflite_predict(interp, token_ids: np.ndarray) -> np.ndarray:
    """Ejecuta el intérprete TFLite y devuelve P(malicioso) para cada fila."""
    in_det = interp.get_input_details()[0]
    out_det = interp.get_output_details()[0]
    probs = []
    for row in token_ids:
        data = np.expand_dims(row, axis=0).astype(in_det["dtype"])
        interp.set_tensor(in_det["index"], data)
        interp.invoke()
        out = interp.get_tensor(out_det["index"])[0]
        # La salida puede ser logits (2,) o un escalar de probabilidad
        if out.ndim == 1 and out.shape[0] == 2:
            e = np.exp(out - out.max())
            probs.append(float(e[1] / e.sum()))
        else:
            probs.append(float(out[-1]))
    return np.array(probs, dtype=np.float32)


def ejecutar_inferencia(urls: List[str], model, tipo_modelo: str) -> np.ndarray:
    """Tokeniza y ejecuta inferencia; devuelve array de P(malicioso)."""
    token_ids = tokenizar_lote(urls)
    if model is None:
        return _heuristic_predict_proba(urls)
    if tipo_modelo == "pytorch":
        return _pytorch_predict(model, token_ids)
    elif tipo_modelo == "tflite":
        return _tflite_predict(model, token_ids)
    raise ValueError(f"tipo_modelo desconocido: {tipo_modelo}")


# ---------------------------------------------------------------------------
# Métricas y gráficos
# ---------------------------------------------------------------------------
def calcular_metricas(y_true: np.ndarray, y_prob: np.ndarray,
                    threshold: float = 0.5) -> Dict:
    """Calcula métricas estándar de clasificación binaria."""
    y_pred = (y_prob >= threshold).astype(int)
    cm = confusion_matrix(y_true, y_pred, labels=[0, 1])
    tn, fp, fn, tp = int(cm[0, 0]), int(cm[0, 1]), int(cm[1, 0]), int(cm[1, 1])
    metrics = {
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "precision": float(precision_score(y_true, y_pred, zero_division=0)),
        "recall": float(recall_score(y_true, y_pred, zero_division=0)),
        "f1": float(f1_score(y_true, y_pred, zero_division=0)),
        "auc_roc": float(roc_auc_score(y_true, y_prob)) if len(np.unique(y_true)) > 1 else float("nan"),
        "pr_auc": float(average_precision_score(y_true, y_prob)) if len(np.unique(y_true)) > 1 else float("nan"),
        "confusion_matrix": {"tn": tn, "fp": fp, "fn": fn, "tp": tp},
        "n_samples": int(len(y_true)),
        "threshold": float(threshold),
    }
    return metrics


def graficar_matriz_confusion(cm: np.ndarray, titulo: str, ruta: str) -> None:
    fig, ax = plt.subplots(figsize=(5, 4))
    im = ax.imshow(cm, cmap="Blues")
    ax.set_xticks([0, 1]); ax.set_yticks([0, 1])
    ax.set_xticklabels(["Benigno", "Malicioso"])
    ax.set_yticklabels(["Benigno", "Malicioso"])
    ax.set_xlabel("Predicho"); ax.set_ylabel("Real"); ax.set_title(titulo)
    for i in range(2):
        for j in range(2):
            ax.text(j, i, str(cm[i, j]), ha="center", va="center",
                    color="white" if cm[i, j] > cm.max() / 2 else "black")
    fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    fig.tight_layout(); fig.savefig(ruta, dpi=150); plt.close(fig)


def graficar_roc(y_true: np.ndarray, y_prob: np.ndarray, titulo: str, ruta: str) -> None:
    fpr, tpr, _ = roc_curve(y_true, y_prob)
    auc = roc_auc_score(y_true, y_prob)
    fig, ax = plt.subplots(figsize=(5, 4))
    ax.plot(fpr, tpr, label=f"AUC = {auc:.4f}")
    ax.plot([0, 1], [0, 1], "--", color="grey")
    ax.set_xlabel("FPR"); ax.set_ylabel("TPR"); ax.set_title(titulo); ax.legend(loc="lower right")
    fig.tight_layout(); fig.savefig(ruta, dpi=150); plt.close(fig)


def graficar_pr(y_true: np.ndarray, y_prob: np.ndarray, titulo: str, ruta: str) -> None:
    p, r, _ = precision_recall_curve(y_true, y_prob)
    ap = average_precision_score(y_true, y_prob)
    fig, ax = plt.subplots(figsize=(5, 4))
    ax.plot(r, p, label=f"PR-AUC = {ap:.4f}")
    ax.set_xlabel("Recall"); ax.set_ylabel("Precision"); ax.set_title(titulo); ax.legend(loc="lower left")
    fig.tight_layout(); fig.savefig(ruta, dpi=150); plt.close(fig)


def graficar_barras_metricas(metrics: Dict, titulo: str, ruta: str) -> None:
    keys = ["accuracy", "precision", "recall", "f1", "auc_roc", "pr_auc"]
    vals = [metrics.get(k, 0.0) for k in keys]
    fig, ax = plt.subplots(figsize=(7, 4))
    ax.bar(keys, vals, color="steelblue")
    ax.set_ylim(0, 1.05)
    ax.set_ylabel("Puntaje"); ax.set_title(titulo)
    for i, v in enumerate(vals):
        ax.text(i, v + 0.02, f"{v:.3f}", ha="center", fontsize=8)
    fig.tight_layout(); fig.savefig(ruta, dpi=150); plt.close(fig)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def evaluar_conjunto(nombre: str, df: pd.DataFrame, model, tipo_modelo: str,
                 out_dir: str) -> Dict:
    urls = df["url_clean"].tolist()
    y_true = df["label"].values.astype(int)
    y_prob = ejecutar_inferencia(urls, model, tipo_modelo)
    metrics = calcular_metricas(y_true, y_prob)
    cm = np.array([[metrics["confusion_matrix"]["tn"], metrics["confusion_matrix"]["fp"]],
                   [metrics["confusion_matrix"]["fn"], metrics["confusion_matrix"]["tp"]]])
    graficar_matriz_confusion(cm, f"Matriz de Confusion - {nombre}", os.path.join(out_dir, f"cm_{nombre}.png"))
    graficar_roc(y_true, y_prob, f"ROC - {nombre}", os.path.join(out_dir, f"roc_{nombre}.png"))
    graficar_pr(y_true, y_prob, f"PR - {nombre}", os.path.join(out_dir, f"pr_{nombre}.png"))
    graficar_barras_metricas(metrics, f"Metricas - {nombre}", os.path.join(out_dir, f"metrics_{nombre}.png"))
    # Guardar predicciones crudas por conjunto para calibración de umbrales
    pred_df = pd.DataFrame({"url": urls, "probability": y_prob, "label": y_true})
    pred_df.to_csv(os.path.join(out_dir, f"predictions_{nombre}.csv"), index=False)
    return metrics


def main() -> int:
    ap = argparse.ArgumentParser(description="Evaluar modelo CANINE-S en conjuntos de prueba externos")
    ap.add_argument("--ruta_modelo", type=str, default="", help="Ruta al checkpoint del modelo (.pt/.tflite)")
    ap.add_argument("--tipo_modelo", type=str, choices=["pytorch", "tflite"], default="pytorch")
    ap.add_argument("--output_dir", type=str, default="evaluation/results",
                    help="Directorio donde escribir JSON + PNGs")
    ap.add_argument("--latam_benign", type=str, default=LATAM_BENIGNO)
    ap.add_argument("--latam_phish", type=str, default=LATAM_PHISH)
    ap.add_argument("--gen_benign", type=str, default=GEN_BENIGNO)
    ap.add_argument("--gen_phish", type=str, default=GEN_PHISH)
    args = ap.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Cargar modelo
    if args.tipo_modelo == "tflite":
        model = cargar_modelo_tflite(args.ruta_modelo) if args.ruta_modelo else None
    else:
        model = cargar_modelo_pytorch(args.ruta_modelo) if args.ruta_modelo else None
    if model is None:
        print("[aviso] Ejecutando con modelo heurístico de respuesto (sin modelo real cargado).",
              file=sys.stderr)

    # Cargar conjuntos de prueba
    results: Dict = {"tipo_modelo": args.tipo_modelo, "ruta_modelo": args.ruta_modelo}
    try:
        latam = cargar_conjunto_prueba(args.latam_benign, args.latam_phish)
        results["prueba_latam"] = evaluar_conjunto("prueba_latam", latam, model, args.tipo_modelo, str(out_dir))
    except Exception as exc:
        print(f"[aviso] Evaluación de prueba_latam falló: {exc}", file=sys.stderr)
        results["prueba_latam"] = {"error": str(exc)}
    try:
        gen = cargar_conjunto_prueba(args.gen_benign, args.gen_phish)
        results["prueba_generica"] = evaluar_conjunto("prueba_generica", gen, model, args.tipo_modelo, str(out_dir))
    except Exception as exc:
        print(f"[aviso] Evaluación de prueba_generica falló: {exc}", file=sys.stderr)
        results["prueba_generica"] = {"error": str(exc)}

    # Agregar (si ambos conjuntos tuvieron éxito)
    sets_ok = [v for v in (results.get("prueba_latam"), results.get("prueba_generica"))
              if isinstance(v, dict) and "error" not in v]
    if sets_ok:
        agg_keys = ["accuracy", "precision", "recall", "f1", "auc_roc", "pr_auc"]
        results["aggregate"] = {k: float(np.mean([s[k] for s in sets_ok])) for k in agg_keys}
        results["aggregate"]["confusion_matrix"] = {
            k: sum(s["confusion_matrix"][k] for s in sets_ok) for k in ("tn", "fp", "fn", "tp")
        }

    ruta_json = out_dir / f"eval_metrics_{args.tipo_modelo}.json"
    with open(ruta_json, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False, default=str)
    print(f"[ok] Resultados guardados en {ruta_json}")
    print(json.dumps(results.get("aggregate", results), indent=2, default=str))
    return 0


if __name__ == "__main__":
    sys.exit(main())
