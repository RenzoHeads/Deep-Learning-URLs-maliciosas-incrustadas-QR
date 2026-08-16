#!/usr/bin/env python3
"""
calibrate_thresholds.py - Calibración de umbrales para el sistema de alerta de tres estados.

El detector de seguridad QR clasifica URLs en tres bandas de riesgo usando dos
umbrales sobre la probabilidad de phishing:
    - p < umbral_bajo     -> SEGURO (benigno)
    - umbral_bajo <= p < umbral_alto -> SOSPECHOSO
    - p >= umbral_alto   -> MALICIOSO

Este script busca (umbral_bajo, umbral_alto) óptimos maximizando F1 (o un puntaje
ponderado que penaliza falsos negativos, ya que perder una URL de phishing es el
peor tipo de error) sobre un conjunto de validación.

Uso:
    python calibrate_thresholds.py \
        --predictions_csv ./evaluation/results/predictions_prueba_latam.csv \
        --output_dir ./evaluation/results

Columnas esperadas del CSV: url, probability, label  (label: 0=benigno, 1=malicioso)

Autor: Fase 6 - Detector de Seguridad QR
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
from sklearn.metrics import f1_score, precision_score, recall_score, accuracy_score, confusion_matrix

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except Exception:
    plt = None  # type: ignore


# Umbrales base por defecto (según spec del proyecto)
DEFAULT_BAJO = 0.30
DEFAULT_ALTO = 0.70


# ---------------------------------------------------------------------------
# Clasificación de tres estados
# ---------------------------------------------------------------------------
def predecir_tres_estados(prob: np.ndarray, t_bajo: float, t_alto: float) -> np.ndarray:
    """Mapea probabilidades a etiquetas binarias para cómputo de métricas de clase maliciosa.

    Para métricas binarias tratamos SOSPECHOSO + MALICIOSO como 'marcado malicioso'
    solo en >= t_alto para malicioso estricto, pero aquí calculamos la etiqueta
    'malicioso' como p >= t_alto (estricto) y 'benigno o sospechoso' como el resto.
    La banda real de tres estados la devuelve clasificar_bandas().
    """
    return (prob >= t_alto).astype(int)


def clasificar_bandas(prob: np.ndarray, t_bajo: float, t_alto: float) -> np.ndarray:
    """Devuelve 0=seguro, 1=sospechoso, 2=malicioso para cada probabilidad."""
    out = np.zeros(len(prob), dtype=int)
    out[(prob >= t_bajo) & (prob < t_alto)] = 1
    out[prob >= t_alto] = 2
    return out


# ---------------------------------------------------------------------------
# Función objetivo de búsqueda
# ---------------------------------------------------------------------------
def _counts_at_threshold(prob_sorted: np.ndarray, y_sorted: np.ndarray,
                         t: float) -> Tuple[int, int, int, int]:
    """TN/FP/FN/TP rápido para la regla `prob >= t` usando arrays pre-ordenados.

    prob_sorted es ascendente; contamos predicciones >= t vía searchsorted.
    """
    n = len(prob_sorted)
    idx = int(np.searchsorted(prob_sorted, t, side="left"))  # primer elemento >= t
    n_pred_pos = n - idx                              # predicho malicioso
    # El sufijo [idx:] de y_sorted corresponde a predicho-positivo
    suffix = y_sorted[idx:] if n_pred_pos else y_sorted[:0]
    tp = int(suffix.sum())
    fp = n_pred_pos - tp
    fn = int(y_sorted[:idx].sum())                    # malicioso real por debajo de t
    tn = (idx) - fn
    return tn, fp, fn, tp


def _metrics_from_counts(tn: int, fp: int, fn: int, tp: int) -> Dict[str, float]:
    """Calcula accuracy/precision/recall/f1/tasa_fn a partir de conteos de confusión."""
    total = tn + fp + fn + tp
    acc = (tn + tp) / total if total else 0.0
    prec = tp / (tp + fp) if (tp + fp) else 0.0
    rec = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = (2 * prec * rec / (prec + rec)) if (prec + rec) else 0.0
    fn_rate = fn / (fn + tp) if (fn + tp) else 0.0
    return {"accuracy": acc, "precision": prec, "recall": rec,
            "f1": f1, "fn_rate": fn_rate}


def puntuar_umbrales(prob: np.ndarray, y_true: np.ndarray,
                     t_bajo: float, t_alto: float,
                     fn_weight: float = 2.0) -> Dict:
    """Puntúa un par de umbrales. Más alto es mejor.

    Objetivo = F1(malicioso) - fn_weight * tasa_FN(normalizado). Las métricas usan
    la regla estricta `p >= t_alto` para la clase malicioso (banda inferior es
    no-malicioso). Los conteos de banda de tres estados usan (t_bajo, t_alto).
    """
    order = np.argsort(prob, kind="stable")
    ps = prob[order]
    ys = y_true[order]
    tn, fp, fn, tp = _counts_at_threshold(ps, ys, t_alto)
    m = _metrics_from_counts(tn, fp, fn, tp)
    objective = m["f1"] - fn_weight * m["fn_rate"]
    # Conteos de banda de tres estados vía searchsorted en y también
    i_bajo = int(np.searchsorted(ps, t_bajo, side="left"))
    i_alto = int(np.searchsorted(ps, t_alto, side="left"))
    band_counts = {
        "seguro": i_bajo,
        "sospechoso": i_alto - i_bajo,
        "malicioso": len(ps) - i_alto,
    }
    return {
        "t_low": float(t_bajo),
        "t_high": float(t_alto),
        **m,
        "objective": float(objective),
        "confusion_matrix": {"tn": int(tn), "fp": int(fp), "fn": int(fn), "tp": int(tp)},
        "band_counts": band_counts,
    }


def buscar_umbrales(prob: np.ndarray, y_true: np.ndarray,
                       step: float = 0.01,
                       fn_weight: float = 2.0) -> Tuple[Dict, List[Dict]]:
    """Búsqueda en grilla vectorizada sobre (t_bajo, t_alto), t_bajo < t_alto.

    Pre-ordena una vez; cada punto de grilla es O(1) vía searchsorted + sumas
    acumuladas. Mucho más rápido que el enfoque ingenuo por par de sklearn.
    """
    order = np.argsort(prob, kind="stable")
    ps = prob[order].astype(np.float64)
    ys = y_true[order].astype(np.float64)
    n = len(ps)
    # Conteos acumulados de malicioso-real por debajo de cada posición
    cum_pos = np.concatenate(([0], np.cumsum(ys)))          # longitud n+1
    total_pos = int(ys.sum())
    total_neg = n - total_pos

    grid = np.round(np.arange(0.05, 0.9601, step), 4)
    # Para cada umbral t_alto: idx = searchsorted; conteos derivados de cum_pos
    idxs_high = np.searchsorted(ps, grid, side="left")          # shape (G,)
    # predicho positivo = sufijo [idx:]; tp = malicioso en sufijo
    tp = total_pos - cum_pos[idxs_high]
    n_pred_pos = n - idxs_high
    fp = n_pred_pos - tp
    fn = cum_pos[idxs_high]
    tn = idxs_high - fn                                          # benigno real en prefijo
    tp = tp.reshape(-1, 1); fp = fp.reshape(-1, 1)
    fn = fn.reshape(-1, 1); tn = tn.reshape(-1, 1)
    # Por umbral alto: métricas
    total = n
    acc = (tn + tp) / total
    prec = np.where(tp + fp > 0, tp / np.maximum(tp + fp, 1), 0.0)
    rec = np.where(tp + fn > 0, tp / np.maximum(tp + fn, 1), 0.0)
    f1 = np.where(prec + rec > 0, 2 * prec * rec / np.maximum(prec + rec, 1e-12), 0.0)
    fn_rate = np.where(fn + tp > 0, fn / np.maximum(fn + tp, 1), 0.0)
    objective = f1 - fn_weight * fn_rate

    # Conteos de banda para cada t_bajo (contra cada t_alto): necesitar posiciones para
    # cada valor de grilla t_bajo. Precomputar una vez.
    idxs_low = np.searchsorted(ps, grid, side="left")          # shape (G,)
    # Para cada par (i_high_idx, low_grid): conteos de banda (broadcast numpy bajo nivel)
    results: List[Dict] = []
    G = len(grid)
    tlow = grid.reshape(1, -1)                                  # (1, G)
    thigh = grid.reshape(-1, 1)                                 # (G, 1)
    mask_low_lt_high = (tlow < thigh)                           # (G, G): [i_high, j_low]
    # broadcast de objetivo: shape (G_high, 1) -> tomar solo filas; objetivo ya (G_high,1)
    for i in range(G):
        t_alto = float(grid[i])
        if idxs_high[i] >= n:
            t_alto_eff = 1.5
        else:
            t_alto_eff = t_alto
        row_obj = float(objective[i, 0])
        row_f1 = float(f1[i, 0]); row_prec = float(prec[i, 0])
        row_rec = float(rec[i, 0]); row_acc = float(acc[i, 0])
        row_fnr = float(fn_rate[i, 0])
        row_tp = int(tp[i, 0]); row_fp = int(fp[i, 0])
        row_fn = int(fn[i, 0]); row_tn = int(tn[i, 0])
        for j in range(G):
            if not mask_low_lt_high[i, j]:
                continue
            t_bajo = float(grid[j])
            i_bajo = int(idxs_low[j])
            i_alto = int(idxs_high[i])
            band_counts = {
                "seguro": i_bajo,
                "sospechoso": i_alto - i_bajo,
                "malicioso": n - i_alto,
            }
            results.append({
                "t_low": t_bajo,
                "t_high": t_alto_eff,
                "accuracy": row_acc,
                "precision": row_prec,
                "recall": row_rec,
                "f1": row_f1,
                "fn_rate": row_fnr,
                "objective": row_obj - fn_weight * 0.0,  # objetivo ya incluye penalización fn
                "confusion_matrix": {"tn": row_tn, "fp": row_fp, "fn": row_fn, "tp": row_tp},
                "band_counts": band_counts,
            })
    # Nota: el objetivo ya incorpora fn_weight*tasa_fn; conservar valor.
    results.sort(key=lambda r: (r["objective"], r["f1"]), reverse=True)
    return results[0], results


# ---------------------------------------------------------------------------
# Gráficos
# ---------------------------------------------------------------------------
def graficar_superficie_objetivo(grid: List[Dict], ruta: str) -> None:
    if plt is None:
        return
    # Representar como dispersión coloreada por objetivo
    lows = np.array([r["t_low"] for r in grid])
    highs = np.array([r["t_high"] for r in grid])
    objs = np.array([r["objective"] for r in grid])
    fig, ax = plt.subplots(figsize=(6, 5))
    sc = ax.scatter(lows, highs, c=objs, cmap="viridis", s=6, alpha=0.7)
    ax.set_xlabel("umbral_bajo (seguro -> sospechoso)")
    ax.set_ylabel("umbral_alto (sospechoso -> malicioso)")
    ax.set_title("Superficie de objetivo F1 - fn_weight*tasa_FN")
    fig.colorbar(sc, ax=ax, label="objetivo")
    fig.tight_layout(); fig.savefig(ruta, dpi=150); plt.close(fig)


def graficar_bandas(prob: np.ndarray, t_bajo: float, t_alto: float, ruta: str) -> None:
    if plt is None:
        return
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.hist(prob, bins=50, color="lightgrey", edgecolor="black")
    ax.axvline(t_bajo, color="orange", linestyle="--", label=f"t_bajo={t_bajo:.2f}")
    ax.axvline(t_alto, color="red", linestyle="--", label=f"t_alto={t_alto:.2f}")
    ax.set_xlabel("Probabilidad de phishing"); ax.set_ylabel("Frecuencia")
    ax.set_title("Distribucion de probabilidades y bandas de alerta")
    ax.legend()
    fig.tight_layout(); fig.savefig(ruta, dpi=150); plt.close(fig)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser(description="Calibrar umbrales de alerta de tres estados")
    ap.add_argument("--predictions_csv", type=str, required=True,
                    help="CSV con columnas: url, probability, label")
    ap.add_argument("--output_dir", type=str, default="evaluation/results")
    ap.add_argument("--fn_weight", type=float, default=2.0,
                    help="Peso de penalización por falso negativo en el objetivo")
    ap.add_argument("--step", type=float, default=0.01, help="Tamaño de paso de la grilla")
    args = ap.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if not os.path.isfile(args.predictions_csv):
        print(f"[error] CSV de predicciones no encontrado: {args.predictions_csv}", file=sys.stderr)
        return 1
    df = pd.read_csv(args.predictions_csv)
    for col in ("probability", "label"):
        if col not in df.columns:
            print(f"[error] columna '{col}' faltante en {args.predictions_csv}", file=sys.stderr)
            return 1
    prob = df["probability"].astype(float).values
    y_true = df["label"].astype(int).values

    best, grid = buscar_umbrales(prob, y_true, step=args.step, fn_weight=args.fn_weight)

    # Comparar con umbrales por defecto
    default = puntuar_umbrales(prob, y_true, DEFAULT_BAJO, DEFAULT_ALTO, args.fn_weight)

    report: Dict = {
        "n_samples": int(len(prob)),
        "fn_weight": float(args.fn_weight),
        "step": float(args.step),
        "default_thresholds": {"t_low": DEFAULT_BAJO, "t_high": DEFAULT_ALTO, **default},
        "optimal_thresholds": {
            "t_low": best["t_low"],
            "t_high": best["t_high"],
            **{k: v for k, v in best.items() if k != "t_low" and k != "t_high"},
        },
        "top_10_candidates": grid[:10],
    }

    json_path = out_dir / "threshold_calibration.json"
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    graficar_superficie_objetivo(grid, os.path.join(str(out_dir), "threshold_surface.png"))
    graficar_bandas(prob, best["t_low"], best["t_high"], os.path.join(str(out_dir), "threshold_bands.png"))

    # Informe markdown
    md = ["# Calibracion de Umbrales del Sistema de Alerta\n"]
    md.append(f"- Muestras: {report['n_samples']}")
    md.append(f"- Pesos: fn_weight={args.fn_weight}, paso={args.step}\n")
    md.append("## Umbrales optimos\n")
    md.append(f"- **umbral_bajo** (seguro -> sospechoso): {best['t_low']:.2f}")
    md.append(f"- **umbral_alto** (sospechoso -> malicioso): {best['t_high']:.2f}")
    md.append(f"- F1: {best['f1']:.4f}  | Precision: {best['precision']:.4f}  | Recall: {best['recall']:.4f}")
    md.append(f"- Tasa FN: {best['fn_rate']:.4f}  | Objetivo: {best['objective']:.4f}")
    md.append(f"\n## Comparacion con umbrales por defecto ({DEFAULT_BAJO}/{DEFAULT_ALTO})\n")
    md.append("| Metrica | Default | Optimo | Delta |")
    md.append("|---|---|---|---|")
    for k in ("accuracy", "precision", "recall", "f1", "fn_rate", "objective"):
        d = best[k] - default[k]
        md.append(f"| {k} | {default[k]:.4f} | {best[k]:.4f} | {d:+.4f} |")
    md.append("\n## Distribucion por banda (optimo)\n")
    for band, n in best["band_counts"].items():
        md.append(f"- {band}: {n} ({n / len(prob) * 100:.1f}%)")

    md_path = out_dir / "threshold_calibration.md"
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(md))

    print(f"[ok] Umbrales óptimos: t_bajo={best['t_low']:.2f}  t_alto={best['t_high']:.2f}")
    print(f"     F1={best['f1']:.4f}  Tasa_FN={best['fn_rate']:.4f}")
    print(f"[ok] Informes guardados en {json_path} y {md_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
