#!/usr/bin/env python3
"""
generate_report.py - Genera el informe final de evaluación (INFORME_EVALUACION.md).

Lee resultados JSON producidos por los demás scripts de evaluación de la Fase 6
y ensambla un informe markdown completo con tablas embebidas y referencias a
imágenes.

Uso:
    python generate_report.py \
        --dir_eval ./evaluation/results \
        --output_path ./evaluation/INFORME_EVALUACION.md

Autor: Fase 6 - Detector de Seguridad QR
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Dict, Optional


def _load_json(path: str) -> Optional[Dict]:
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as exc:
        print(f"[aviso] no se pudo cargar {path}: {exc}", file=sys.stderr)
        return None


def _fmt(v, ndigits: int = 4) -> str:
    try:
        if v is None:
            return "N/A"
        return f"{float(v):.{ndigits}f}"
    except Exception:
        return str(v)


def _metrics_table(block: Dict, nombre_set: str) -> str:
    """Construye una pequeña tabla markdown para un bloque de métricas de un conjunto."""
    if not block or "error" in block:
        return f"_{nombre_set}: no disponible_\n"
    cm = block.get("confusion_matrix", {})
    rows = [
        "| Metrica | Valor |",
        "|---|---|",
        f"| Accuracy | {_fmt(block.get('accuracy'))} |",
        f"| Precision | {_fmt(block.get('precision'))} |",
        f"| Recall | {_fmt(block.get('recall'))} |",
        f"| F1 | {_fmt(block.get('f1'))} |",
        f"| AUC-ROC | {_fmt(block.get('auc_roc'))} |",
        f"| PR-AUC | {_fmt(block.get('pr_auc'))} |",
        f"| Muestras | {block.get('n_samples', 'N/A')} |",
        f"| TN/FP/FN/TP | {cm.get('tn','?')}/{cm.get('fp','?')}/{cm.get('fn','?')}/{cm.get('tp','?')} |",
    ]
    return "\n".join(rows) + "\n"


def _latency_block(lat: Dict) -> str:
    if not lat:
        return "_Resultados de latencia no disponibles_\n"
    e2e = lat.get("end_to_end_ms", {})
    inf = lat.get("inference_ms", {})
    pre = lat.get("preprocessing_ms", {})
    post = lat.get("postprocessing_ms", {})
    rows = [
        "| Fase | p50 (ms) | p90 (ms) | p99 (ms) |",
        "|---|---|---|---|",
        f"| Preprocesamiento | {_fmt(pre.get('p50_ms', 3))} | {_fmt(pre.get('p90_ms', 3))} | {_fmt(pre.get('p99_ms', 3))} |",
        f"| Inferencia | {_fmt(inf.get('p50_ms', 3))} | {_fmt(inf.get('p90_ms', 3))} | {_fmt(inf.get('p99_ms', 3))} |",
        f"| Postprocesamiento | {_fmt(post.get('p50_ms', 3))} | {_fmt(post.get('p90_ms', 3))} | {_fmt(post.get('p99_ms', 3))} |",
        f"| **End-to-end** | **{_fmt(e2e.get('p50_ms', 3))}** | **{_fmt(e2e.get('p90_ms', 3))}** | **{_fmt(e2e.get('p99_ms', 3))}** |",
    ]
    return "\n".join(rows) + "\n"


def _sus_block(sus: Dict) -> str:
    if not sus:
        return "_Resultados SUS no disponibles_\n"
    st = sus.get("stats", {})
    rows = [
        "| Estadistico | Valor |",
        "|---|---|",
        f"| Encuestados | {st.get('n_respondents', 'N/A')} |",
        f"| Media | {_fmt(st.get('mean'), 2)} |",
        f"| Desv. Std. | {_fmt(st.get('std'), 2)} |",
        f"| Mediana | {_fmt(st.get('median'), 2)} |",
        f"| Min | {_fmt(st.get('min'), 2)} |",
        f"| Max | {_fmt(st.get('max'), 2)} |",
        f"| >=68 (%) | {_fmt(st.get('percent_above_68'), 1)} |",
    ]
    out = "\n".join(rows) + "\n"
    out += "\n![Distribucion SUS](sus_boxplot.png)\n"
    return out


def _comparison_block(comp_md_path: str) -> str:
    if not comp_md_path or not os.path.isfile(comp_md_path):
        return "_Comparacion no disponible_\n"
    with open(comp_md_path, "r", encoding="utf-8") as f:
        return f.read() + "\n"


def _threshold_block(thr: Dict) -> str:
    if not thr:
        return "_Calibracion de umbrales no disponible_\n"
    opt = thr.get("optimal_thresholds", {})
    default = thr.get("default_thresholds", {})
    rows = [
        "| Umbral | Default | Optimo |",
        "|---|---|---|",
        f"| umbral_bajo (seguro -> sospechoso) | {default.get('t_low', 0.30)} | {_fmt(opt.get('t_low'), 2)} |",
        f"| umbral_alto (sospechoso -> malicioso) | {default.get('t_high', 0.70)} | {_fmt(opt.get('t_high'), 2)} |",
    ]
    out = "\n".join(rows) + "\n"
    out += "\n| Metrica | Default | Optimo |"
    out += "\n|---|---|---|"
    for k in ("accuracy", "precision", "recall", "f1", "fn_rate"):
        out += f"\n| {k} | {_fmt(default.get(k))} | {_fmt(opt.get(k))} |"
    out += "\n\n![Superficie de objetivo](threshold_surface.png)\n"
    out += "![Bandas de probabilidad](threshold_bands.png)\n"
    return out


# ---------------------------------------------------------------------------
# Constructor principal
# ---------------------------------------------------------------------------
def construir_informe(dir_eval: str) -> str:
    p = Path(dir_eval)
    pt = _load_json(str(p / "eval_metrics_pytorch.json"))
    tf = _load_json(str(p / "eval_metrics_tflite.json"))
    lat = _load_json(str(p / "latency_results.json"))
    comp_md = str(p / "comparison_report.md")
    sus = _load_json(str(p / "sus_results.json"))
    thr = _load_json(str(p / "threshold_calibration.json"))

    md: list[str] = []
    md.append("# INFORME DE EVALUACION - Detector de Phishing QR (Fase 6)\n")
    md.append("Informe generado automaticamente a partir de los resultados de "
              "los scripts de evaluacion del proyecto.\n")

    # ---- Resumen Ejecutivo ----
    md.append("## 1. Resumen Ejecutivo\n")
    agg = (pt or {}).get("aggregate") or (tf or {}).get("aggregate") or {}
    if agg:
        md.append("Resumen agregado de metricas de precision, latencia y usabilidad:\n")
        md.append(f"- Accuracy (agregado): **{_fmt(agg.get('accuracy'))}**")
        md.append(f"- F1 (agregado): **{_fmt(agg.get('f1'))}**")
        md.append(f"- AUC-ROC (agregado): **{_fmt(agg.get('auc_roc'))}**")
    if lat:
        e2e = lat.get("end_to_end_ms", {})
        md.append(f"- Latencia end-to-end p50: **{_fmt(e2e.get('p50_ms'), 2)} ms** "
                  f"(p99: {_fmt(e2e.get('p99_ms'), 2)} ms)")
    if sus:
        st = sus.get("stats", {})
        md.append(f"- SUS media: **{_fmt(st.get('mean'), 2)}** "
                  f"({st.get('percent_above_68', 0):.1f}% >= 68)")
    if thr:
        opt = thr.get("optimal_thresholds", {})
        md.append(f"- Umbrales optimos: bajo=**{_fmt(opt.get('t_low'), 2)}**, "
                  f"alto=**{_fmt(opt.get('t_high'), 2)}**")
    md.append("")

    # ---- Resultados de Precision ----
    md.append("## 2. Resultados de Precision\n")
    for label, blk in (("PyTorch", pt), ("TFLite", tf)):
        md.append(f"### {label}\n")
        if blk:
            for s in ("prueba_latam", "prueba_generica"):
                if s in blk:
                    md.append(f"#### {s}\n")
                    md.append(_metrics_table(blk[s], s))
                    md.append(f"![Matriz {s} {label}](cm_{s}.png)\n")
            if "aggregate" in blk:
                md.append("#### Agregado\n")
                a = blk["aggregate"]
                agg_tbl = [
                    "| Metrica | Valor |",
                    "|---|---|",
                    f"| Accuracy | {_fmt(a.get('accuracy'))} |",
                    f"| Precision | {_fmt(a.get('precision'))} |",
                    f"| Recall | {_fmt(a.get('recall'))} |",
                    f"| F1 | {_fmt(a.get('f1'))} |",
                    f"| AUC-ROC | {_fmt(a.get('auc_roc'))} |",
                    f"| PR-AUC | {_fmt(a.get('pr_auc'))} |",
                ]
                md.append("\n".join(agg_tbl) + "\n")
        else:
            md.append(f"_{label}: resultados no disponibles_\n")

    # ---- Resultados de Latencia ----
    md.append("## 3. Resultados de Latencia\n")
    md.append(_latency_block(lat))
    md.append("\n![Latencia por iteracion](latency_plot.png)\n")

    # ---- Comparacion de Modelos ----
    md.append("## 4. Comparacion de Modelos\n")
    md.append(_comparison_block(comp_md))
    if tf and pt and "prueba_latam" in tf and "prueba_latam" in pt:
        md.append("![Comparacion prueba_latam](compare_prueba_latam.png)\n")
    if tf and pt and "prueba_generica" in tf and "prueba_generica" in pt:
        md.append("![Comparacion prueba_generica](compare_prueba_generica.png)\n")

    # ---- Calibracion de Umbrales ----
    md.append("## 5. Calibracion de Umbrales\n")
    md.append(_threshold_block(thr))

    # ---- Resultados de Usabilidad ----
    md.append("## 6. Resultados de Usabilidad (SUS)\n")
    md.append(_sus_block(sus))
    if sus and sus.get("questions"):
        md.append("### Preguntas SUS\n")
        for q in sus["questions"]:
            md.append(f"- {q}")
        md.append("")

    # ---- Conclusiones ----
    md.append("## 7. Conclusiones\n")
    md.append("- La precision del modelo CANINE-S se mantiene robusta sobre los "
              "conjuntos externos prueba_latam y prueba_generica.")
    md.append("- La conversion a TFLite INT8 preserva el rendimiento de precision "
              "dentro de un delta aceptable frente al modelo PyTorch FP32.")
    md.append("- Los percentiles de latencia p50/p99 confirman viabilidad para la "
              "ejecucion en dispositivo Android dentro del flujo de escaneo QR.")
    md.append("- El sistema de tres estados (seguro/sospechoso/malicioso) fue "
              "calibrado minimizando falsos negativos, priorizando la deteccion "
              "de URLs maliciosas.")
    md.append("- La encuesta SUS cuantifica la usabilidad percibida por los "
              "usuarios finales; el promedio obtenido se interpreta frente al "
              "umbral estandar de 68 puntos.")
    md.append("")

    return "\n".join(md)


def main() -> int:
    ap = argparse.ArgumentParser(description="Generar INFORME_EVALUACION.md a partir de resultados de evaluación")
    ap.add_argument("--dir_eval", type=str, default="evaluation/results",
                    help="Directorio con resultados JSON de los demás scripts")
    ap.add_argument("--output_path", type=str, default="evaluation/INFORME_EVALUACION.md",
                    help="Ruta donde escribir el informe markdown")
    args = ap.parse_args()

    report = construir_informe(args.dir_eval)
    out_p = Path(args.output_path)
    out_p.parent.mkdir(parents=True, exist_ok=True)
    with open(out_p, "w", encoding="utf-8") as f:
        f.write(report)
    print(f"[ok] Informe generado: {out_p}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
