#!/usr/bin/env python3
"""
compare_models.py - Compara el rendimiento del modelo PyTorch (FP32) vs TFLite (INT8).

Carga métricas JSON de ejecuciones de eval_model.py para ambos backends, calcula
deltas para cada métrica, renderiza una tabla comparativa + gráficos, y escribe
un informe en markdown.

Uso:
    python compare_models.py \
        --pytorch_metrics ./evaluation/results/eval_metrics_pytorch.json \
        --tflite_metrics  ./evaluation/results/eval_metrics_tflite.json \
        --output_dir ./evaluation/results

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

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except Exception:
    plt = None  # type: ignore


METRIC_KEYS = ["accuracy", "precision", "recall", "f1", "auc_roc", "pr_auc"]


def cargar_metricas(ruta: str) -> Dict:
    """Carga un JSON de métricas producido por eval_model.py."""
    if not os.path.isfile(ruta):
        raise FileNotFoundError(f"Archivo de métricas no encontrado: {ruta}")
    with open(ruta, "r", encoding="utf-8") as f:
        return json.load(f)


def extraer_metricas_conjunto(metrics: Dict, nombre_set: str) -> Dict[str, float]:
    """Extrae un dict plano de métrica→valor para un conjunto de prueba dado."""
    block = metrics.get(nombre_set, {})
    if "error" in block:
        return {k: float("nan") for k in METRIC_KEYS}
    return {k: float(block.get(k, float("nan"))) for k in METRIC_KEYS}


def calcular_deltas(pt: Dict[str, float], tf: Dict[str, float]) -> Dict[str, float]:
    """Delta = tflite - pytorch para cada métrica."""
    return {k: float(tf[k] - pt[k]) for k in METRIC_KEYS}


def construir_tabla(nombres_sets: List[str], pt_metrics: Dict, tf_metrics: Dict) -> str:
    """Construye una tabla markdown comparativa a través de todos los conjuntos."""
    header = "| Conjunto | Metrica | PyTorch FP32 | TFLite INT8 | Delta (TFLite - PyTorch) |"
    sep = "|---|---|---|---|---|"
    rows = [header, sep]
    for s in nombres_sets:
        pt = extraer_metricas_conjunto(pt_metrics, s)
        tf = extraer_metricas_conjunto(tf_metrics, s)
        for k in METRIC_KEYS:
            d = tf[k] - pt[k]
            rows.append(f"| {s} | {k} | {pt[k]:.4f} | {tf[k]:.4f} | {d:+.4f} |")
    # Fila agregada
    if "aggregate" in pt_metrics and "aggregate" in tf_metrics:
        pa, ta = pt_metrics["aggregate"], tf_metrics["aggregate"]
        for k in METRIC_KEYS:
            pv = float(pa.get(k, float("nan")))
            tv = float(ta.get(k, float("nan")))
            rows.append(f"| **agregado** | {k} | {pv:.4f} | {tv:.4f} | {tv - pv:+.4f} |")
    return "\n".join(rows)


def graficar_comparacion(nombre_set: str, pt: Dict[str, float], tf: Dict[str, float],
                    out_path: str) -> None:
    if plt is None:
        return
    keys = METRIC_KEYS
    x = np.arange(len(keys))
    w = 0.35
    fig, ax = plt.subplots(figsize=(8, 4.5))
    ax.bar(x - w / 2, [pt[k] for k in keys], w, label="PyTorch FP32", color="steelblue")
    ax.bar(x + w / 2, [tf[k] for k in keys], w, label="TFLite INT8", color="coral")
    ax.set_xticks(x); ax.set_xticklabels(keys, rotation=15)
    ax.set_ylim(0, 1.05); ax.set_ylabel("Puntaje")
    ax.set_title(f"PyTorch vs TFLite - {nombre_set}")
    ax.legend(loc="lower right")
    fig.tight_layout(); fig.savefig(out_path, dpi=150); plt.close(fig)


def graficar_delta(nombre_set: str, deltas: Dict[str, float], out_path: str) -> None:
    if plt is None:
        return
    keys = METRIC_KEYS
    vals = [deltas[k] for k in keys]
    colors = ["green" if v >= 0 else "red" for v in vals]
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.bar(keys, vals, color=colors)
    ax.axhline(0, color="black", linewidth=0.6)
    ax.set_ylabel("Delta (TFLite - PyTorch)")
    ax.set_title(f"Delta de metricas - {nombre_set}")
    fig.tight_layout(); fig.savefig(out_path, dpi=150); plt.close(fig)


def main() -> int:
    ap = argparse.ArgumentParser(description="Comparar métricas PyTorch vs TFLite")
    ap.add_argument("--pytorch_metrics", type=str, required=True,
                    help="JSON de eval_model.py (pytorch)")
    ap.add_argument("--tflite_metrics", type=str, required=True,
                    help="JSON de eval_model.py (tflite)")
    ap.add_argument("--output_dir", type=str, default="evaluation/results")
    args = ap.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    pt = cargar_metricas(args.pytorch_metrics)
    tf = cargar_metricas(args.tflite_metrics)

    nombres_sets = [s for s in ("prueba_latam", "prueba_generica") if s in pt or s in tf]

    # Construir deltas por conjunto
    all_deltas: Dict[str, Dict[str, float]] = {}
    for s in nombres_sets:
        p = extraer_metricas_conjunto(pt, s)
        t = extraer_metricas_conjunto(tf, s)
        all_deltas[s] = calcular_deltas(p, t)
        graficar_comparacion(s, p, t, os.path.join(str(out_dir), f"compare_{s}.png"))
        graficar_delta(s, all_deltas[s], os.path.join(str(out_dir), f"delta_{s}.png"))

    table = construir_tabla(nombres_sets, pt, tf)

    # Informe markdown
    md = ["# Comparacion de Modelos: PyTorch FP32 vs TFLite INT8\n"]
    md.append(f"- Métricas PyTorch: `{args.pytorch_metrics}`")
    md.append(f"- Métricas TFLite: `{args.tflite_metrics}`\n")
    md.append("## Tabla de metricas\n")
    md.append(table)
    md.append("\n## Deltas por conjunto\n")
    for s, d in all_deltas.items():
        md.append(f"### {s}\n")
        for k, v in d.items():
            sign = "+" if v >= 0 else ""
            md.append(f"- **{k}**: {sign}{v:.4f}")
        md.append("")
    md.append("## Graficos\n")
    for s in nombres_sets:
        md.append(f"### {s}")
        md.append(f"![Comparacion {s}](compare_{s}.png)")
        md.append(f"![Delta {s}](delta_{s}.png)\n")

    md_path = out_dir / "comparison_report.md"
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(md))

    # Guardar deltas como JSON también
    deltas_path = out_dir / "comparison_deltas.json"
    with open(deltas_path, "w", encoding="utf-8") as f:
        json.dump(all_deltas, f, indent=2, ensure_ascii=False)

    print(f"[ok] Informe de comparación: {md_path}")
    print(table)
    return 0


if __name__ == "__main__":
    sys.exit(main())
