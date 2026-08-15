#!/usr/bin/env python3
"""
train_canine_s.py — Entrenamiento de CANINE-S para deteccion de URLs maliciosas
===============================================================================

Script autocontenido para ejecutar en Google Colab (GPU A100 80GB recomendada).

Arquitectura:
    - CANINE-S (google/canine-s) preentrenado, fine-tuning para clasificacion binaria
    - Clase envoltorio RoBERTaModel: CanineModel + LayerNorm + Dropout + Linear(1536,1)
    - Pooling: Media enmascarada + Max enmascarado concatenados (1536-dim)
    - Perdida: SmoothBCEWithLogitsLoss (label smoothing 0.05)
    - Optimizador: AdamW + ReduceLROnPlateau
    - Precision mixta: BF16 autocast

Uso:
    python train_canine_s.py --pipeline regionalizado
    python train_canine_s.py --pipeline generico

Salida:
    salida_roberta/
    ├── mejor_modelo.pt          (state_dict del mejor modelo)
    ├── tokenizador/             (tokenizador CANINE guardado)
    ├── metricas.json            (metricas finales)
    ├── matriz_confusion.png
    ├── curva_roc.png
    └── historial_entrenamiento.png
"""

import os
import re
import sys
import json
import time
import argparse
import warnings
from typing import Optional

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader

from sklearn.model_selection import train_test_split
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score, f1_score,
    roc_auc_score, average_precision_score, confusion_matrix,
    classification_report, roc_curve,
)

from transformers import CanineModel, CanineTokenizer

warnings.filterwarnings("ignore")

# ============================================================================
# CONFIGURACION
# ============================================================================
SEED = 42
DIR_SALIDA = "salida_roberta"
os.makedirs(DIR_SALIDA, exist_ok=True)

torch.manual_seed(SEED)
np.random.seed(SEED)

# Hiperparametros derivados del notebook "Transformer v2.ipynb"
MAX_LEN = 150           # percentil 95 = 101, minimo forzado 150
PAD_IDX = 0             # tokenizer.pad_token_id = 0
TAM_VOCAB = 1_200_000   # rango seguro Unicode

# Paths de los pipelines
CONFIGS_PIPELINE = {
    "regionalizado": {
        "csv_entrenamiento": "master_regionalizado/benigno_latam_50k.csv",
        "csv_entrenamiento_phishing": "master_regionalizado/phishing_latam_50k.csv",
        "test_benigno": "prueba_latam/benigno_latam_1500.csv",
        "test_phishing": "prueba_latam/phishing_latam_1500.csv",
        "col_etiqueta": "label",
    },
    "generico": {
        "csv_entrenamiento": "master_generico/benigno_generico_50k.csv",
        "csv_entrenamiento_phishing": "master_generico/phishing_generico_50k.csv",
        "test_benigno": "prueba_generica/benigno_generico_1500.csv",
        "test_phishing": "prueba_generica/phishing_generico_1500.csv",
        "col_etiqueta": "label",
    },
}

DISPOSITIVO = torch.device("cuda" if torch.cuda.is_available() else "cpu")

# ============================================================================
# UTILIDADES
# ============================================================================
_RE_PROTOCOLO = re.compile(r'^(?:https?|ftps?)://', re.IGNORECASE)
_RE_WWW = re.compile(r'^www\.', re.IGNORECASE)


def limpiar_url(url: str) -> str:
    """Quitar protocolo http/https/ftp/ftps y www. inicial."""
    url = str(url).strip()
    url = _RE_PROTOCOLO.sub('', url)
    url = _RE_WWW.sub('', url)
    return url


def cargar_y_preprocesar(config: dict) -> pd.DataFrame:
    """Cargar, limpiar y combinar URLs benignas + phishing."""
    print("\n" + "=" * 70)
    print("ETAPA 1 — Carga del Dataset")
    print("=" * 70)

    # Cargar benignos
    df_benigno = pd.read_csv(config["csv_entrenamiento"])
    df_benigno = df_benigno.dropna(subset=["url", "label"]).reset_index(drop=True)

    # Cargar phishing
    df_phishing = pd.read_csv(config["csv_entrenamiento_phishing"])
    df_phishing = df_phishing.dropna(subset=["url", "label"]).reset_index(drop=True)

    # Combinar
    df = pd.concat([df_benigno, df_phishing], ignore_index=True)
    df = df.dropna(subset=["url", "label"]).reset_index(drop=True)

    # Limpiar URLs
    df["url"] = df["url"].astype(str).str.strip()
    df = df[df["url"].str.len() > 0].reset_index(drop=True)
    df["url"] = df["url"].apply(limpiar_url)
    df["label"] = df["label"].astype(int)

    print(f"  Total URLs: {len(df):,}")
    print(f"  Benignas (0): {(df['label'] == 0).sum():,}")
    print(f"  Maliciosas (1): {(df['label'] == 1).sum():,}")

    return df


# ============================================================================
# TOKENIZADOR + DATASET
# ============================================================================
def obtener_tokenizador():
    """Cargar tokenizador CANINE."""
    print("\n" + "=" * 70)
    print("ETAPA 2 — Tokenizador CANINE-S")
    print("=" * 70)
    tokenizador = CanineTokenizer.from_pretrained("google/canine-s")
    print(f"  PAD_IDX = {tokenizador.pad_token_id}")
    print(f"  Tamano vocab (aprox): {TAM_VOCAB}")
    return tokenizador


class DatasetURLs(Dataset):
    """Dataset PyTorch para URLs tokenizadas."""

    def __init__(self, urls, etiquetas, tokenizador, max_len=MAX_LEN):
        self.urls = urls
        self.etiquetas = etiquetas
        self.tokenizador = tokenizador
        self.max_len = max_len

    def __len__(self):
        return len(self.urls)

    def __getitem__(self, idx):
        codificado = self.tokenizador(
            self.urls[idx],
            padding="max_length",
            truncation=True,
            max_length=self.max_len,
            return_tensors="pt",
        )
        input_ids = codificado["input_ids"].squeeze(0)
        etiqueta = torch.tensor(self.etiquetas[idx], dtype=torch.float32)
        return input_ids, etiqueta


# ============================================================================
# MODELO
# ============================================================================
class RoBERTaModel(nn.Module):
    """
    Clase envoltorio que implementa la arquitectura CANINE-S preentrenada.

    Mantiene el nombre 'RoBERTaModel' por compatibilidad con los scripts
    de exportacion (export_onnx.py, export_tflite.py).

    Arquitectura:
        - CANINE-S (google/canine-s) → (B, L, 768)
        - Media enmascarada + Max enmascarado → (B, 1536)
        - LayerNorm(1536) + Dropout + Linear(1536, 1) → logits (B,)
    """

    def __init__(self, dropout: float = 0.3, pad_idx: int = PAD_IDX):
        super().__init__()
        self.pad_idx = pad_idx

        # CANINE-S preentrenado
        self.canine = CanineModel.from_pretrained("google/canine-s")
        hidden = self.canine.config.hidden_size  # 768

        # Cabezal de clasificacion — el nombre `classifier` DEBE coincidir
        # con export_onnx.py / export_tflite.py para que los checkpoints
        # carguen con las claves de state_dict correctas.
        self.layer_norm = nn.LayerNorm(hidden * 2)
        self.dropout = nn.Dropout(dropout)
        self.classifier = nn.Linear(hidden * 2, 1)

    def forward(self, x):
        # x: (B, L) — indices de caracteres
        mascara_atencion = (x != self.pad_idx).long()

        # CANINE forward
        salidas = self.canine(input_ids=x, attention_mask=mascara_atencion)
        salida = salidas.last_hidden_state  # (B, L, 768)

        # Mascaras de padding
        mascara_pad = (x == self.pad_idx)  # (B, L)
        mascara_no_pad = ~mascara_pad
        mascara = mascara_no_pad.unsqueeze(-1).to(salida.dtype)  # (B, L, 1)

        # Media enmascarada (Masked Mean Pooling)
        suma_embeddings = torch.sum(salida * mascara, dim=1)
        suma_mascara = torch.clamp(mascara.sum(dim=1), min=1e-9)
        media_agrupada = suma_embeddings / suma_mascara  # (B, 768)

        # Max enmascarado (Masked Max Pooling)
        salida_enmascarada = salida.masked_fill(mascara_pad.unsqueeze(-1), -1e9)
        max_agrupado = salida_enmascarada.max(dim=1).values  # (B, 768)

        # Concatenar
        salida_cls = torch.cat([media_agrupada, max_agrupado], dim=1)  # (B, 1536)

        # Cabezal
        salida_cls = self.layer_norm(salida_cls)
        salida_cls = self.dropout(salida_cls)
        logits = self.classifier(salida_cls).squeeze(1)  # (B,)
        return logits


# ============================================================================
# PERDIDA
# ============================================================================
class SmoothBCEWithLogitsLoss(nn.Module):
    """BCEWithLogitsLoss con label smoothing."""

    def __init__(self, smoothing=0.05, peso_positivo: Optional[torch.Tensor] = None):
        super().__init__()
        self.smoothing = smoothing
        self.peso_positivo = peso_positivo

    def forward(self, logits, objetivos):
        objetivos_suavizados = objetivos * (1 - self.smoothing) + 0.5 * self.smoothing
        return F.binary_cross_entropy_with_logits(
            logits, objetivos_suavizados, pos_weight=self.peso_positivo
        )


# ============================================================================
# ENTRENAMIENTO
# ============================================================================
def ejecutar_epoca(cargador, modelo, criterio, optimizador, entrenar: bool):
    """Ejecutar una epoca de entrenamiento o validacion."""
    if entrenar:
        modelo.train()
    else:
        modelo.eval()

    perdida_total, correctos, total = 0.0, 0, 0

    ctx = torch.enable_grad() if entrenar else torch.no_grad()
    with ctx:
        for lote_x, lote_y in cargador:
            lote_x = lote_x.to(DISPOSITIVO, non_blocking=True)
            lote_y = lote_y.to(DISPOSITIVO, non_blocking=True)

            if entrenar:
                optimizador.zero_grad()

            # Precision mixta BF16 (A100)
            with torch.amp.autocast(device_type="cuda", dtype=torch.bfloat16):
                logits = modelo(lote_x)
                perdida = criterio(logits, lote_y)

            if entrenar:
                perdida.backward()
                nn.utils.clip_grad_norm_(modelo.parameters(), max_norm=1.0)
                optimizador.step()

            perdida_total += perdida.item() * len(lote_y)
            probs = torch.sigmoid(logits).cpu().numpy()
            predicciones = (probs >= 0.5).astype(int)
            correctos += (predicciones == lote_y.cpu().numpy().astype(int)).sum()
            total += len(lote_y)

    perdida_promedio = perdida_total / total
    exactitud = correctos / total
    return perdida_promedio, exactitud


def evaluar_en_test(modelo, cargador_test):
    """Evaluar modelo en el conjunto de prueba externo."""
    modelo.eval()
    todas_predicciones, todas_probs, todas_etiquetas = [], [], []

    with torch.no_grad():
        for lote_x, lote_y in cargador_test:
            lote_x = lote_x.to(DISPOSITIVO, non_blocking=True)
            with torch.amp.autocast(device_type="cuda", dtype=torch.bfloat16):
                logits = modelo(lote_x)
            probs = torch.sigmoid(logits).cpu().numpy()
            predicciones = (probs >= 0.5).astype(int)
            todas_predicciones.extend(predicciones)
            todas_probs.extend(probs)
            todas_etiquetas.extend(lote_y.numpy().astype(int))

    return (
        np.array(todas_predicciones),
        np.array(todas_probs),
        np.array(todas_etiquetas),
    )


# ============================================================================
# VISUALIZACION
# ============================================================================
def guardar_matriz_confusion(y_real, y_pred, titulo, ruta):
    cm = confusion_matrix(y_real, y_pred)
    fig, ax = plt.subplots(figsize=(6, 5))
    im = ax.imshow(cm, cmap="Blues")
    ax.set_xticks([0, 1])
    ax.set_yticks([0, 1])
    ax.set_xticklabels(["Benigno", "Malicioso"])
    ax.set_yticklabels(["Benigno", "Malicioso"])
    ax.set_xlabel("Prediccion")
    ax.set_ylabel("Real")
    ax.set_title(titulo)
    for i in range(2):
        for j in range(2):
            ax.text(j, i, str(cm[i, j]), ha="center", va="center",
                    color="white" if cm[i, j] > cm.max() / 2 else "black")
    fig.colorbar(im)
    plt.tight_layout()
    fig.savefig(ruta, dpi=150)
    plt.close()


def guardar_curva_roc(y_real, y_probs, titulo, ruta):
    fpr, tpr, _ = roc_curve(y_real, y_probs)
    auc = roc_auc_score(y_real, y_probs)
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(fpr, tpr, label=f"AUC = {auc:.4f}")
    ax.plot([0, 1], [0, 1], "k--", alpha=0.3)
    ax.set_xlabel("FPR")
    ax.set_ylabel("TPR")
    ax.set_title(titulo)
    ax.legend()
    plt.tight_layout()
    fig.savefig(ruta, dpi=150)
    plt.close()


def guardar_historial_entrenamiento(historial, ruta):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 5))
    ax1.plot(historial["perdida_train"], label="Train")
    ax1.plot(historial["perdida_val"], label="Val")
    ax1.set_xlabel("Epoca")
    ax1.set_ylabel("Perdida")
    ax1.set_title("Perdida")
    ax1.legend()
    ax2.plot(historial["exactitud_train"], label="Train")
    ax2.plot(historial["exactitud_val"], label="Val")
    ax2.set_xlabel("Epoca")
    ax2.set_ylabel("Exactitud")
    ax2.set_title("Exactitud")
    ax2.legend()
    plt.tight_layout()
    fig.savefig(ruta, dpi=150)
    plt.close()


# ============================================================================
# MAIN
# ============================================================================
def main():
    parser = argparse.ArgumentParser(
        description="Entrenar CANINE-S para deteccion de URLs maliciosas"
    )
    parser.add_argument("--pipeline", choices=["regionalizado", "generico"],
                        default="regionalizado", help="Pipeline a ejecutar")
    parser.add_argument("--epochs", type=int, default=8, help="Numero de epocas")
    parser.add_argument("--batch_size", type=int, default=32, help="Tamano de lote")
    parser.add_argument("--lr", type=float, default=2e-5, help="Tasa de aprendizaje")
    parser.add_argument("--dropout", type=float, default=0.3, help="Tasa de dropout")
    parser.add_argument("--weight_decay", type=float, default=0.01, help="Weight decay")
    args = parser.parse_args()

    config = CONFIGS_PIPELINE[args.pipeline]
    print(f"\n{'=' * 70}")
    print(f"ENTRENAMIENTO CANINE-S — Pipeline: {args.pipeline}")
    print(f"Dispositivo: {DISPOSITIVO}")
    print(f"Epocas: {args.epochs} | Lote: {args.batch_size} | LR: {args.lr}")
    print(f"{'=' * 70}")

    # 1. Cargar datos
    df = cargar_y_preprocesar(config)

    # 2. Division train/val
    print("\n" + "=" * 70)
    print("ETAPA 3 — Division Train/Val")
    print("=" * 70)
    df_train, df_val = train_test_split(
        df, test_size=0.2, random_state=SEED, stratify=df["label"]
    )
    print(f"  Train: {len(df_train):,} | Val: {len(df_val):,}")

    # 3. Cargar conjunto de prueba externo
    print("\n" + "=" * 70)
    print("ETAPA 4 — Carga de Test Externo")
    print("=" * 70)
    df_test_ben = pd.read_csv(config["test_benigno"])
    df_test_mal = pd.read_csv(config["test_phishing"])
    # Normalizar columnas (algunos conjuntos usan 'dominio' en vez de 'url')
    col_url = "url" if "url" in df_test_ben.columns else "dominio"
    df_test = pd.concat([
        df_test_ben[["url", "label"]] if "url" in df_test_ben.columns
        else df_test_ben[[col_url, "label"]].rename(columns={col_url: "url"}),
        df_test_mal[["url", "label"]] if "url" in df_test_mal.columns
        else df_test_mal[[col_url, "label"]].rename(columns={col_url: "url"}),
    ], ignore_index=True)
    df_test = df_test.dropna(subset=["url", "label"]).reset_index(drop=True)
    df_test["url"] = df_test["url"].astype(str).apply(limpiar_url)
    df_test["label"] = df_test["label"].astype(int)
    print(f"  Test externo: {len(df_test):,}")

    # 4. Tokenizador
    tokenizador = obtener_tokenizador()

    # 5. Datasets y DataLoaders
    print("\n" + "=" * 70)
    print("ETAPA 5 — DataLoaders")
    print("=" * 70)
    ds_train = DatasetURLs(df_train["url"].tolist(), df_train["label"].tolist(), tokenizador)
    ds_val = DatasetURLs(df_val["url"].tolist(), df_val["label"].tolist(), tokenizador)
    ds_test = DatasetURLs(df_test["url"].tolist(), df_test["label"].tolist(), tokenizador)

    cargador_train = DataLoader(ds_train, batch_size=args.batch_size, shuffle=True,
                                num_workers=2, pin_memory=True)
    cargador_val = DataLoader(ds_val, batch_size=args.batch_size, shuffle=False,
                              num_workers=2, pin_memory=True)
    cargador_test = DataLoader(ds_test, batch_size=args.batch_size, shuffle=False,
                               num_workers=2, pin_memory=True)

    print(f"  Lotes train: {len(cargador_train)}")
    print(f"  Lotes val:   {len(cargador_val)}")
    print(f"  Lotes test:  {len(cargador_test)}")

    # 6. Modelo
    print("\n" + "=" * 70)
    print("ETAPA 6 — Modelo CANINE-S")
    print("=" * 70)
    modelo = RoBERTaModel(dropout=args.dropout).to(DISPOSITIVO)

    total_params = sum(p.numel() for p in modelo.parameters())
    params_entrenables = sum(p.numel() for p in modelo.parameters() if p.requires_grad)
    print(f"  Params totales:     {total_params:,}")
    print(f"  Params entrenables: {params_entrenables:,}")

    # 7. Perdida y Optimizador
    # Peso positivo para desbalance de clases
    conteo_pos = (df_train["label"] == 1).sum()
    conteo_neg = (df_train["label"] == 0).sum()
    peso_positivo = torch.tensor([conteo_neg / conteo_pos]).to(DISPOSITIVO)
    print(f"  peso_positivo: {peso_positivo.item():.4f}")

    criterio = SmoothBCEWithLogitsLoss(smoothing=0.05, peso_positivo=peso_positivo)
    optimizador = torch.optim.AdamW(
        modelo.parameters(), lr=args.lr, weight_decay=args.weight_decay
    )
    planificador = torch.optim.lr_scheduler.ReduceLROnPlateau(
        optimizador, mode="min", patience=2, factor=0.5, min_lr=1e-6
    )

    # 8. Loop de entrenamiento
    print("\n" + "=" * 70)
    print(f"ETAPA 7 — Entrenamiento ({args.epochs} epocas)")
    print("=" * 70)

    historial = {
        "perdida_train": [], "perdida_val": [],
        "exactitud_train": [], "exactitud_val": []
    }
    mejor_perdida_val = float("inf")
    ruta_mejor_modelo = os.path.join(DIR_SALIDA, "mejor_modelo.pt")

    for epoca in range(1, args.epochs + 1):
        t0 = time.time()

        # Warmup lineal
        if epoca == 1:
            for g in optimizador.param_groups:
                g["lr"] = args.lr * 0.1
        elif epoca == 2:
            for g in optimizador.param_groups:
                g["lr"] = args.lr

        perdida_train, exactitud_train = ejecutar_epoca(
            cargador_train, modelo, criterio, optimizador, entrenar=True
        )
        perdida_val, exactitud_val = ejecutar_epoca(
            cargador_val, modelo, criterio, optimizador, entrenar=False
        )

        planificador.step(perdida_val)

        historial["perdida_train"].append(perdida_train)
        historial["perdida_val"].append(perdida_val)
        historial["exactitud_train"].append(exactitud_train)
        historial["exactitud_val"].append(exactitud_val)

        transcurrido = time.time() - t0
        lr_actual = optimizador.param_groups[0]["lr"]
        print(f"  Epoca {epoca}/{args.epochs} — "
              f"perdida: {perdida_train:.4f}/{perdida_val:.4f} — "
              f"exact: {exactitud_train:.4f}/{exactitud_val:.4f} — "
              f"lr: {lr_actual:.2e} — "
              f"({transcurrido:.1f}s)")

        if perdida_val < mejor_perdida_val:
            mejor_perdida_val = perdida_val
            torch.save(modelo.state_dict(), ruta_mejor_modelo)
            print(f"    ★ Mejor modelo guardado (perdida_val={perdida_val:.4f})")

    # 9. Evaluacion en test externo
    print("\n" + "=" * 70)
    print("ETAPA 8 — Evaluacion en Test Externo")
    print("=" * 70)

    # Cargar mejor modelo
    modelo.load_state_dict(torch.load(ruta_mejor_modelo, map_location=DISPOSITIVO))

    y_pred, y_probs, y_real = evaluar_en_test(modelo, cargador_test)

    # Metricas
    metricas = {
        "pipeline": args.pipeline,
        "accuracy": float(accuracy_score(y_real, y_pred)),
        "precision": float(precision_score(y_real, y_pred)),
        "recall": float(recall_score(y_real, y_pred)),
        "f1": float(f1_score(y_real, y_pred)),
        "auc_roc": float(roc_auc_score(y_real, y_probs)),
        "auc_pr": float(average_precision_score(y_real, y_probs)),
    }

    print(f"\n  Accuracy:  {metricas['accuracy']:.4f}")
    print(f"  Precision: {metricas['precision']:.4f}")
    print(f"  Recall:    {metricas['recall']:.4f}")
    print(f"  F1:        {metricas['f1']:.4f}")
    print(f"  AUC-ROC:   {metricas['auc_roc']:.4f}")
    print(f"  AUC-PR:    {metricas['auc_pr']:.4f}")

    print(f"\n  Matriz de Confusion:")
    cm = confusion_matrix(y_real, y_pred)
    print(f"    TN={cm[0,0]}  FP={cm[0,1]}")
    print(f"    FN={cm[1,0]}  TP={cm[1,1]}")

    print(f"\n  Reporte de Clasificacion:")
    print(classification_report(y_real, y_pred, target_names=["Benigno", "Malicioso"]))

    # 10. Guardar artefactos
    print("\n" + "=" * 70)
    print("ETAPA 9 — Guardando artefactos")
    print("=" * 70)

    # Guardar tokenizador
    tokenizador.save_pretrained(os.path.join(DIR_SALIDA, "tokenizador"))

    # Guardar metricas
    ruta_metricas = os.path.join(DIR_SALIDA, "metricas.json")
    with open(ruta_metricas, "w", encoding="utf-8") as f:
        json.dump(metricas, f, indent=2, ensure_ascii=False)
    print(f"  ✓ {ruta_metricas}")

    # Guardar graficas
    ruta_cm = os.path.join(DIR_SALIDA, "matriz_confusion.png")
    guardar_matriz_confusion(y_real, y_pred, f"Matriz de Confusion — {args.pipeline}", ruta_cm)
    print(f"  ✓ {ruta_cm}")

    ruta_roc = os.path.join(DIR_SALIDA, "curva_roc.png")
    guardar_curva_roc(y_real, y_probs, f"Curva ROC — {args.pipeline}", ruta_roc)
    print(f"  ✓ {ruta_roc}")

    ruta_historial = os.path.join(DIR_SALIDA, "historial_entrenamiento.png")
    guardar_historial_entrenamiento(historial, ruta_historial)
    print(f"  ✓ {ruta_historial}")

    print(f"\n  Modelo: {ruta_mejor_modelo}")
    print(f"\n{'=' * 70}")
    print("ENTRENAMIENTO COMPLETADO ✓")
    print(f"{'=' * 70}\n")

    # Imprimir como JSON para parseo programatico
    print("---METRICAS_JSON---")
    print(json.dumps(metricas))


if __name__ == "__main__":
    main()
