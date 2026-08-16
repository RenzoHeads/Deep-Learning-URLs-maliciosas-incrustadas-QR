#!/usr/bin/env python3
"""
sus_survey.py - Análisis de encuesta SUS (System Usability Scale).

Define las 10 preguntas estándar SUS, lee respuestas Likert (1-5) desde un CSV
(columnas q1..q10), calcula el puntaje SUS de cada encuestado usando la fórmula
estándar, reporta estadísticas descriptivas + interpretación, y genera un
diagrama de caja (box plot).

Puntuación SUS:
  - Ítems impares: contribución = respuesta - 1
  - Ítems pares: contribución = 5 - respuesta
  - Puntaje SUS = suma(contribuciones) * 2.5   (rango 0-100, 68 = promedio)

Uso:
    python sus_survey.py \
        --input_csv ./evaluation/sus_responses.csv \
        --output_dir ./evaluation/results

Autor: Fase 6 - Detector de Seguridad QR
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Dict, List

import numpy as np
import pandas as pd

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except Exception:
    plt = None  # type: ignore


# ---------------------------------------------------------------------------
# 10 preguntas estándar SUS (español, contextualizadas a la app)
# ---------------------------------------------------------------------------
SUS_QUESTIONS = [
    "1.  Creo que usaria frecuentemente esta aplicacion.",           # impar  (positiva)
    "2.  Encontré la aplicacion innecesariamente compleja.",        # par (negativa)
    "3.  Imagine que la aplicacion seria facil de usar.",            # impar
    "4.  Cree que necesitari ajuda tecnica para usar la aplicacion.",# par
    "5.  Las funciones estan bien integradas.",                      # impar
    "6.  Encontré inconsistencias en la aplicacion.",               # par
    "7.  Imagine que la mayoria aprenderia a usarla rapidamente.",   # impar
    "8.  Encontré la aplicacion incomoda de usar.",                  # par
    "9.  Me senti seguro usando la aplicacion.",                     # impar
    "10. Necesite aprender cosas adicionales antes de usarla.",     # par
]

# Índices base-1 que son "impares" (positivos) para la fórmula de puntaje
ODD_INDICES = [1, 3, 5, 7, 9]
EVEN_INDICES = [2, 4, 6, 8, 10]

# Bandas de calificación adjetiva SUS (Bangor et al.)
ADJECTIVE_RATINGS = [
    (0, 25), (25, 39), (39, 52), (52, 73), (73, 78), (78, 85), (85, 100),
]
ADJECTIVE_LABELS = [
    "Peor imagenable", "Muy pobre", "Pobre", "Promedio", "Bueno", "Excelente", "Mejor imagenable",
]


# ---------------------------------------------------------------------------
# Puntuación
# ---------------------------------------------------------------------------
def calcular_puntaje_sus(respuestas: List[int]) -> float:
    """Calcula un puntaje SUS a partir de una lista de 10 respuestas Likert.

    Args:
        respuestas: lista de 10 ints en [1,5].

    Returns:
        Puntaje SUS en [0,100].
    """
    if len(respuestas) != 10:
        raise ValueError(f"Se esperaban 10 respuestas, se obtuvieron {len(respuestas)}")
    contrib = 0
    for i, r in enumerate(respuestas, start=1):
        r = int(r)
        if r < 1 or r > 5:
            raise ValueError(f"Respuesta {i} fuera de rango [1,5]: {r}")
        if i in ODD_INDICES:
            contrib += r - 1
        else:
            contrib += 5 - r
    return contrib * 2.5


def interpret(puntaje: float) -> str:
    """Devuelve interpretación textual de un puntaje SUS."""
    banda = "Promedio"
    for (lo, hi), lab in zip(ADJECTIVE_RATINGS, ADJECTIVE_LABELS):
        if lo <= puntaje < hi:
            banda = lab
            break
    nivel = "por encima del promedio" if puntaje >= 68 else "por debajo del promedio"
    return f"{banda} ({nivel})"


def analizar(df: pd.DataFrame) -> Dict:
    """Calcula puntajes por encuestado y estadísticas descriptivas."""
    qcols = [f"q{i}" for i in range(1, 11)]
    missing = [c for c in qcols if c not in df.columns]
    if missing:
        raise ValueError(f"CSV faltan columnas requeridas: {missing}")

    scores: List[float] = []
    per_resp = []
    for idx, row in df.iterrows():
        r = [int(row[c]) for c in qcols]
        s = calcular_puntaje_sus(r)
        scores.append(s)
        per_resp.append({"respondent": int(idx), "score": s, "interpretation": interpret(s)})

    arr = np.array(scores, dtype=np.float64)
    stats = {
        "n_respondents": int(len(arr)),
        "mean": float(arr.mean()) if len(arr) else float("nan"),
        "std": float(arr.std(ddof=1)) if len(arr) > 1 else 0.0,
        "median": float(np.median(arr)) if len(arr) else float("nan"),
        "min": float(arr.min()) if len(arr) else float("nan"),
        "max": float(arr.max()) if len(arr) else float("nan"),
        "above_average_count": int((arr >= 68).sum()),
        "below_average_count": int((arr < 68).sum()),
        "percent_above_68": float((arr >= 68).mean() * 100) if len(arr) else float("nan"),
    }
    return {
        "questions": SUS_QUESTIONS,
        "individual_scores": per_resp,
        "stats": stats,
        "all_scores": scores,
    }


def graficar_boxplot(scores: List[float], ruta: str) -> None:
    if plt is None:
        return
    fig, ax = plt.subplots(figsize=(5, 5))
    ax.boxplot(scores, orientation="vertical", patch_artist=True,
               boxprops=dict(facecolor="lightblue"))
    ax.axhline(68, color="green", linestyle="--", label="Promedio (68)")
    ax.set_ylabel("Puntaje SUS")
    ax.set_title("Distribucion de puntajes SUS")
    ax.set_ylim(0, 105)
    ax.legend(loc="lower right", fontsize=8)
    fig.tight_layout(); fig.savefig(ruta, dpi=150); plt.close(fig)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser(description="Análisis de encuesta SUS")
    ap.add_argument("--input_csv", type=str, required=True, help="CSV con columnas q1..q10 (Likert 1-5)")
    ap.add_argument("--output_dir", type=str, default="evaluation/results")
    args = ap.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if not os.path.isfile(args.input_csv):
        print(f"[error] CSV de entrada no encontrado: {args.input_csv}", file=sys.stderr)
        return 1

    df = pd.read_csv(args.input_csv)
    result = analizar(df)

    # JSON
    json_path = out_dir / "sus_results.json"
    # Evitar duplicar la lista `all_scores` (ya está dentro de stats)
    out_obj = {k: v for k, v in result.items() if k != "all_scores"}
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(out_obj, f, indent=2, ensure_ascii=False)

    # Diagrama de caja
    graficar_boxplot(result["all_scores"], os.path.join(str(out_dir), "sus_boxplot.png"))

    # Resumen por consola
    st = result["stats"]
    print(f"[ok] N={st['n_respondents']}  media={st['mean']:.2f}  std={st['std']:.2f}")
    print(f"     min={st['min']:.1f}  mediana={st['median']:.1f}  max={st['max']:.1f}")
    print(f"     >=68: {st['above_average_count']} ({st['percent_above_68']:.1f}%)")
    print(f"[ok] Resultados guardados en {json_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
