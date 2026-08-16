#!/usr/bin/env python3
"""
eval_latency.py - Benchmark de latencia para el modelo TFLite CANINE-S.

Mide la latencia de inferencia por URL sobre N iteraciones y reporta
estadísticas de percentiles (p50, p90, p99), además de timing end-to-end
(preprocesamiento + inferencia + postprocesamiento). Diseñado para simulación
en Python de la ruta on-device en Android.

Uso:
    python eval_latency.py \
        --ruta_modelo ./models/canine_s.tflite \
        --n_iterations 100 \
        --output_dir ./evaluation/results

Autor: Fase 6 - Detector de Seguridad QR
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np
import pandas as pd

# Asegurar que el directorio raiz del proyecto este en sys.path para que
# los imports `from ml_comun...` funcionen sea cual sea el CWD.
_PROYECTO_RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _PROYECTO_RAIZ not in sys.path:
    sys.path.insert(0, _PROYECTO_RAIZ)

from ml_comun.url import clean_url


MAX_LEN = 150


def canine_tokenize(url: str, max_len: int = MAX_LEN) -> np.ndarray:
    """Tokeniza una URL en un array de codepoints Unicode de longitud fija."""
    cps = [ord(c) for c in url[:max_len]]
    arr = np.zeros(max_len, dtype=np.int32)
    arr[: len(cps)] = cps
    return arr


# ---------------------------------------------------------------------------
# URLs de muestra (mezcla representativa para benchmarking)
# ---------------------------------------------------------------------------
URLS_MUESTRA = [
    "https://www.google.com/search?q=test",
    "http://login.paypal-account-verify.com/signin",
    "https://banco-seguro.com/login.html?token=abc123xyz",
    "ftp://files.example.org/download/doc.pdf",
    "https://www.amazon.com/dp/B08N5WRWNW",
    "http://free-gift-prize-win.tk/claim",
    "https://github.com/nousresearch/hermes-agent",
    "https://account-update-suspended.ml/verify?id=98765",
    "https://en.wikipedia.org/wiki/Phishing",
    "http://192.168.1.1/admin/confirm?user=admin",
]


# ---------------------------------------------------------------------------
# Cargadores de modelo (mejor esfuerzo)
# ---------------------------------------------------------------------------
def cargar_tflite(ruta_modelo: str):
    try:
        import tflite_runtime.interpreter as tflite  # type: ignore
    except ImportError:
        try:
            import tensorflow.lite as tflite  # type: ignore
        except ImportError:
            return None
    interp = tflite.Interpreter(ruta_modelo=ruta_modelo, num_threads=1)
    interp.allocate_tensors()
    return interp


def _tflite_infer(interp, token_ids: np.ndarray) -> float:
    in_det = interp.get_input_details()[0]
    out_det = interp.get_output_details()[0]
    data = np.expand_dims(token_ids, axis=0).astype(in_det["dtype"])
    interp.set_tensor(in_det["index"], data)
    interp.invoke()
    out = interp.get_tensor(out_det["index"])[0]
    if out.ndim == 1 and out.shape[0] == 2:
        e = np.exp(out - out.max())
        return float(e[1] / e.sum())
    return float(out[-1])


def _heuristic_infer(token_ids: np.ndarray) -> float:
    """Respuesto determinístico que simula el costo de cómputo de la inferencia."""
    # Cómputo sintético ligero para que el timing refleje algo de trabajo real
    acc = 0.0
    for v in token_ids:
        acc += float(v) * 0.0001
    # matmul pequeño para imitar una capa densa
    _ = np.dot(token_ids.astype(np.float32), np.ones(MAX_LEN, dtype=np.float32))
    return float(min(0.95, max(0.05, acc / 10.0)))


# ---------------------------------------------------------------------------
# Harness de timing
# ---------------------------------------------------------------------------
def medir_latencia(urls: List[str], modelo, n_iter: int) -> Dict:
    """Mide latencia de preprocesamiento, inferencia y postprocesamiento.

    Devuelve dict con arrays por iteración y estadísticas de percentiles (ms).
    """
    pre_times: List[float] = []
    inf_times: List[float] = []
    post_times: List[float] = []
    e2e_times: List[float] = []

    rng = np.random.default_rng(42)

    for it in range(n_iter):
        url = urls[it % len(urls)]
        # ---- inicio end-to-end ----
        t0 = time.perf_counter()
        # preprocesamiento: limpiar + tokenizar
        u = clean_url(url)
        tok = canine_tokenize(u)
        t1 = time.perf_counter()
        # inferencia
        if modelo is None:
            prob = _heuristic_infer(tok)
        else:
            prob = _tflite_infer(modelo, tok)
        t2 = time.perf_counter()
        # postprocesamiento: umbral + string de etiqueta
        etiqueta = "malicioso" if prob >= 0.5 else "benigno"
        _ = f"{url}|{prob:.4f}|{etiqueta}"
        t3 = time.perf_counter()

        pre_times.append((t1 - t0) * 1000.0)
        inf_times.append((t2 - t1) * 1000.0)
        post_times.append((t3 - t2) * 1000.0)
        e2e_times.append((t3 - t0) * 1000.0)

    def pct(arr):
        a = np.array(arr, dtype=np.float64)
        return {
            "mean_ms": float(a.mean()),
            "std_ms": float(a.std()),
            "p50_ms": float(np.percentile(a, 50)),
            "p90_ms": float(np.percentile(a, 90)),
            "p99_ms": float(np.percentile(a, 99)),
            "min_ms": float(a.min()),
            "max_ms": float(a.max()),
        }

    return {
        "n_iterations": n_iter,
        "n_sample_urls": len(urls),
        "preprocessing_ms": pct(pre_times),
        "inference_ms": pct(inf_times),
        "postprocessing_ms": pct(post_times),
        "end_to_end_ms": pct(e2e_times),
        "raw_pre_ms": pre_times,
        "raw_inf_ms": inf_times,
        "raw_post_ms": post_times,
        "raw_e2e_ms": e2e_times,
    }


# ---------------------------------------------------------------------------
# Gráfico de latencia opcional
# ---------------------------------------------------------------------------
def graficar_latencia(results: Dict, path: str) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception:
        return
    fig, ax = plt.subplots(figsize=(7, 4))
    e2e = results["raw_e2e_ms"]
    ax.plot(range(len(e2e)), e2e, color="steelblue", linewidth=0.8, label="E2E")
    ax.axhline(results["end_to_end_ms"]["p50_ms"], color="green", linestyle="--", label="p50")
    ax.axhline(results["end_to_end_ms"]["p90_ms"], color="orange", linestyle="--", label="p90")
    ax.axhline(results["end_to_end_ms"]["p99_ms"], color="red", linestyle="--", label="p99")
    ax.set_xlabel("Iteracion"); ax.set_ylabel("Latencia (ms)"); ax.set_title("Latencia E2E por iteracion")
    ax.legend(loc="upper right", fontsize=8)
    fig.tight_layout(); fig.savefig(path, dpi=150); plt.close(fig)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser(description="Benchmark de latencia para modelo TFLite CANINE-S")
    ap.add_argument("--ruta_modelo", type=str, default="", help="Ruta al modelo .tflite")
    ap.add_argument("--n_iterations", type=int, default=100, help="Iteraciones por URL")
    ap.add_argument("--output_dir", type=str, default="evaluation/results")
    ap.add_argument("--urls_csv", type=str, default="", help="CSV opcional con columna 'url' para muestrear URLs")
    args = ap.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # URLs
    if args.urls_csv and os.path.isfile(args.urls_csv):
        df = pd.read_csv(args.urls_csv)
        url_col = "url" if "url" in df.columns else df.columns[0]
        urls = df[url_col].astype(str).tolist()
    else:
        urls = URLS_MUESTRA

    modelo = cargar_tflite(args.ruta_modelo) if args.ruta_modelo else None
    if modelo is None:
        print("[aviso] No se cargó modelo TFLite - usando simulación Python de respuesto.",
              file=sys.stderr)

    # Calentamiento (5 iteraciones, no medidas)
    _ = medir_latencia(urls[:1], modelo, n_iter=5)

    results = medir_latencia(urls, modelo, n_iter=args.n_iterations)
    # Quitar arrays crudos del JSON (conservarlos en campo separado si se necesita)
    raw = {k: v for k, v in results.items() if k.startswith("raw_")}
    for k in list(results.keys()):
        if k.startswith("raw_"):
            del results[k]

    json_path = out_dir / "latency_results.json"
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    print(f"[ok] Resultados de latencia guardados en {json_path}")

    graficar_latencia({**results, **raw}, os.path.join(str(out_dir), "latency_plot.png"))

    # Resumen por consola
    e2e = results["end_to_end_ms"]
    print(f"E2E  p50={e2e['p50_ms']:.3f}ms  p90={e2e['p90_ms']:.3f}ms  p99={e2e['p99_ms']:.3f}ms")
    inf = results["inference_ms"]
    print(f"INF  p50={inf['p50_ms']:.3f}ms  p90={inf['p90_ms']:.3f}ms  p99={inf['p99_ms']:.3f}ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
