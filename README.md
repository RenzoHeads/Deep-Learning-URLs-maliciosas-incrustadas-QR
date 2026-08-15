# Detección de URLs Maliciosas Incrustadas en Códigos QR mediante Deep Learning

## Estructura del repositorio

Además de los notebooks de investigación, el repo contiene el **producto completo**:

- **`android/`** — App Android (Kotlin + Jetpack Compose + Hilt + Room
  offline-first + WorkManager). Escanea QR con CameraX/ML Kit, clasifica la
  URL on-device con un **LSTM char-level TFLite** (~830 KB en
  `app/src/main/assets/ml/`), y sincroniza historial/URLs bloqueadas con el
  backend vía outbox (`pending_ops`) con idempotencia y delta-sync.
- **`backend/`** — API FastAPI + asyncpg sobre PostgreSQL (Neon), desplegada
  en Vercel (`qr-guardian-api.vercel.app`). Auth por token bearer, bcrypt,
  rate limiting. Ver `backend/README` y `backend/vercel.json`.
- **Notebooks y pipelines ML** — este README (abajo) documenta la parte de
  investigación: entrenamiento/comparación de 4 arquitecturas (LSTM, BiLSTM,
  CNN-LSTM, Transformer CANINE-S). El modelo desplegado en la app es el LSTM
  (ver `MODEL_CARD.md` — sección de actualización).

## Resumen

Este proyecto implementa y evalúa cuatro arquitecturas de Deep Learning para la clasificación binaria de URLs maliciosas (phishing) incrustadas en códigos QR. Los modelos se entrenan y evalúan sobre dos corpus paralelos ,uno **regionalizado** (enfocado en Latinoamérica) y otro **genérico** (global), con el objetivo de estudiar el impacto de la regionalización lingüística y geográfica en la capacidad de generalización del detector.

Cada arquitectura se optimiza con **Optuna (Búsqueda de Estructura de Árboles — TPE)** y se valida mediante **Validación Cruzada Estratificada de k pliegues**. La evaluación final se realiza sobre cuatro conjuntos de prueba externos, asegurando una comparación justa entre dominios.

---

## 1. Metodología

### 1.1 Arquitecturas de Modelos

Se evalúan cuatro arquitecturas de Deep Learning para clasificación de URL a nivel de carácter:

| Notebook | Arquitectura | Fundamento | Vectorización | GPU |
|----------|-------------|------------|---------------|-----|
| `LSTM v2.ipynb` | LSTM unidireccional | Hochreiter & Schmidhuber (1997) | Carácter | L4 |
| `BiLSTM v2.ipynb` | LSTM bidireccional | Schuster & Paliwal (1997); Subashini & Narmatha (2024) | Carácter | L4 |
| `CNN-LSTM v2.ipynb` | CNN + LSTM híbrido | Zonyfar, Lee & Kim (2023) | Carácter | L4 |
| `Transformer v2.ipynb` | CANINE-S (Character Transformer) | Clark et al. (2022) — Google | Carácter (preentrenado) | A100 |

#### Detalles de cada arquitectura

**LSTM (unidireccional).** Embedding de caracteres → LSTM (N capas apiladas) → estado oculto de la última capa → Dropout → capa lineal (clasificador binario). El clasificador recibe `hidden_dim` unidades.

**BiLSTM (bidireccional).** Misma estructura que el LSTM, pero con `bidirectional=True`. El clasificador recibe `hidden_dim * 2` unidades, al concatenar los estados ocultos *forward* (`hn[-2]`) y *backward* (`hn[-1]`).

**CNN-LSTM (híbrido).** Embedding de caracteres → dos bloques Conv1d (emb→64, k=3, ReLU, MaxPool1d(2)) y (64→128, k=3, ReLU, MaxPool1d(2)) → LSTM apilado (128→hidden_dim) → Dropout → clasificador. La CNN extrae patrones locales (n-gramas de caracteres) y la LSTM modela dependencias secuenciales sobre la secuencia reducida.

**Transformer (CANINE-S).** Modelo preentrenado `google/canine-s` (Clark et al., 2022), que opera directamente sobre caracteres Unicode sin tokenización por subpalabras. Se adapta a clasificación binaria con un head lineal. La optimización se realiza con **AdamW**, **precisión mixta bf16** y **warmup de learning rate**. Para compatibilidad con el pipeline unificado, el *wrapper* interno se denomina `RoBERTaModel`.

### 1.2 Preprocesamiento

Todos los notebooks aplican un preprocesamiento idéntico para garantizar comparabilidad:

1. **Limpieza de URL**: se eliminan el protocolo (`http://`, `https://`, `ftp://`, `ftps://`) y el prefijo `www.` mediante expresiones regulares.
2. **Tokenización a nivel de carácter** (LSTM/BiLSTM/CNN-LSTM): se construye un vocabulario sobre el conjunto de entrenamiento con `Counter`, con tokens especiales `<PAD>` (índice 0) y `<UNK>`. La longitud máxima se fija en el **percentil 95** de las longitudes de URL del *train set*.
3. **Tokenización CANINE-S** (Transformer): se utiliza el `CanineTokenizer` de Hugging Face; `MAX_LEN` se ajusta a un mínimo de 150 y un máximo de 1024 caracteres (techo para evitar OOM en A100 80 GB).

### 1.3 División de Datos y Estratificación

Los datasets se unen y generan **estratos** combinando la etiqueta (benigno/phishing) y el país de procedencia (en el corpus regionalizado) o un marcador genérico (en el corpus genérico). Esta estratificación permite que la división train/val/test respete la distribución geográfica y de clases.

- **Train**: 70 %
- **Validación**: 15 %
- **Test interno**: 15 %
- **División**: `train_test_split` con `stratify=estrato` y `random_state=42`

### 1.4 Optimización de Hiperparámetros (Optuna)

Se emplea **Optuna** con el **TPESampler** (Tree-structured Parzen Estimator) para la búsqueda Bayesiana de hiperparámetros, junto con un **MedianPruner** que descarta trials subóptimos prematuramente.

| Hiperparámetro | LSTM / BiLSTM / CNN-LSTM | Transformer (CANINE-S) |
|----------------|---------------------------|-------------------------|
| `hidden_dim` | {64, 128, 256} | — |
| `num_layers` | [1, 3] | — |
| `emb_dim` | {32, 64, 128} | 768 (fijo CANINE) |
| `dropout` | [0.3, 0.6] (smoothing 0.05) | [0.1, 0.5] |
| `lr` | [1e-4, 1e-2] (log) | [2e-5, 1e-4] (log) |
| `batch_size` | {32, 64, 128, 256} | {256, 512, 1024} |
| `weight_decay` | [1e-5, 1e-2] (log) | [1e-4, 1e-2] (log) |
| Optimizador | Adam | AdamW (bf16) |
| Épocas por trial | 10 (early stop prune) | 4 (warmup → bf16) |
| Trials | 50 | 50 |
| Pruner | MedianPruner(n_startup=5, n_warmup=3) | MedianPruner(n_startup=5, n_warmup=1) |

**Justificación del *smoothing* de etiquetas.** Se aplica `SmoothBCEWithLogitsLoss` con `smoothing=0.05` para mitigar el sobreajuste y mejorar la calibración de las probabilidades.


### 1.5 Entrenamiento Final

- `MAX_EPOCHS_FINAL = 50` (early stopping por `val_loss` con `patience = patience` configurable).
- **Gradiente recortado** (`clip_grad_norm_`): `max_norm = 5.0` (LSTM/BiLSTM/CNN-LSTM), `max_norm = 1.0` (Transformer).
- **Semilla**: `SEED = 42` en `numpy` y `torch` para reproducibilidad.

### 1.6 Evaluación

Cada pipeline reporta las siguientes métricas:

- **Accuracy**, **Precision**, **Recall**, **F1-score** (umbral 0.5)
- **Matriz de confusión** y `classification_report`
- Curvas **ROC** y **Precision-Recall**

Adicionalmente, se comparan resultados sobre **cuatro conjuntos externos** (desconocidos para el modelo y fuera del *split* interno):

| Variable | Conjunto | Origen |
|----------|----------|--------|
| `EXT_BEN_LATAM` / `EXT_MAL_LATAM` | Latam (benigno + phishing) | `prueba_latam/` |
| `EXT_BEN_GEN` / `EXT_MAL_GEN` | Genérico (benigno + phishing) | `prueba_generica/` |


---

## 2. Datasets

El proyecto mantiene **dos corpus paralelos** (master) y **cuatro conjuntos de prueba externos**. La designación *"regionalizado"* indica URLs con etiqueta geográfica de Latinoamérica; *"genérico"* indica URLs globales sin etiqueta regional.

### 2.1 Estructura de Directorios

```
master_regionalizado/               ← Corpus de master LATAM (100k URL)
  benigno_latam_50k.csv               50 000 URLs benignas, columna `pais`
  phishing_latam_50k.csv             50 000 URLs de phishing, columna `pais`

master_generico/                    ← Corpus de master global (100k URL)
  benigno_generico_50k.csv           50 000 URLs benignas (sin país)
  phishing_generico_50k.csv          50 000 URLs de phishing (sin país)

prueba_latam/                       ← Conjunto de evaluación Latam (3 000 URL)
  benigno_latam_1500.csv             1 500 URLs benignas con `pais` y `source`
  phishing_latam_1500.csv             1 500 URLs de phishing con `pais` y `tld`

prueba_generica/                    ← Conjunto de evaluación global (3 000 URL)
  benigno_generico_1500.csv         1 500 URLs benignas (sin `label`)
  phishing_generico_1500.csv         1 500 URLs de phishing (sin `label`)
```


### 2.3 Estratificación

En el corpus **regionalizado**, cada URL se asigna a un estrato según:

- `pais` (Latam con países grandes: `Colombia`, `Perú`, `Argentina`, `Brasil`, `México`, `Chile`, etc. → `<pais>_benigno` o `<pais>_phishing`)
- `pais ∈ {El Salvador, Guatemala, Honduras, Panamá, Nicaragua}` → `Latam_pocos_paises_<clase>`
- `pais = "Generico"` o `NaN` → `generico_<clase>`

En el corpus **genérico**, todos los registros se asignan a `generico_benigno` o `generico_phishing`.

---

## 3. Diseño Experimental

Cada notebook ejecuta **dos pipelines idénticos y consecutivos** para un mismo modelo:

| Pipeline | Datos de entrenamiento | Conjuntos de prueba externos |
|----------|------------------------|----------------------------|
| **Pipeline A — Regionalizado** | `master_regionalizado/` | `prueba_latam/` + `prueba_generica/` |
| **Pipeline B — Genérico** | `master_generico/` | `prueba_latam/` + `prueba_generica/` |



## 4. Reproducibilidad

### 4.1 Entorno

Los notebooks fueron diseñados para ejecutar en **Google Colab** con GPU. Las rutas internas son **relativas**, por lo que basta con clonar/subir el repositorio completo al directorio de trabajo de Colab.

| Notebook | GPU recomendada | Arquitectura equivalente |
|----------|----------------|--------------------------|
| BiLSTM, LSTM, CNN-LSTM | NVIDIA L4 (T4 compatible) | ~16 GB VRAM |
| Transformer (CANINE-S) | NVIDIA A100 80 GB | Uso intensivo de bf16 |

### 4.2 Dependencias

```text
python >= 3.10
torch
pandas
numpy
matplotlib
scikit-learn
optuna
transformers                 # solo Transformer v2.ipynb
```

### 4.3 Pasos para Reproducir

1. Subir el repositorio a Google Colab (o clonarlo en `/content/`).
2. Abrir uno de los notebooks `<Modelo> v2.ipynb`.
3. Ejecutar las celdas en orden. El primer bloque (celdas 1-5) ejecuta el **Pipeline A — Regionalizado**; las celdas siguientes (6-11) ejecutan el **Pipeline B — Genérico** que sobreescribe el dataset unificado (`dataset_merged2.csv`).
4. La semilla global `SEED = 42` controla todas las fuentes de aleatoriedad (`numpy`, `torch`, `train_test_split`, `StratifiedKFold`, `TPESampler`).


### 4.4 Reproducibilidad de la Búsqueda Optuna

El `TPESampler` se inicializa con `seed=SEED`. Los `MedianPruner` deterministas garantizan que los 50 trials produzcan la misma secuencia de configuraciones evaluadas en re-ejecuciones, siempre que el dispositivo y la versión de PyTorch sean idénticos.

---

## 5. Estructura del Repositorio

```
Deep-Learning-URLs-maliciosas-incrustadas-QR/
├── README.md
├── BiLSTM v2.ipynb
├── CNN-LSTM v2.ipynb
├── LSTM v2.ipynb
├── Transformer v2.ipynb
├── master_regionalizado/
│   ├── benigno_latam_50k.csv
│   └── phishing_latam_50k.csv
├── master_generico/
│   ├── benigno_generico_50k.csv
│   └── phishing_generico_50k.csv
├── prueba_latam/
│   ├── benigno_latam_1500.csv
│   └── phishing_latam_1500.csv
└── prueba_generica/
    ├── benigno_generico_1500.csv
    └── phishing_generico_1500.csv
```

---


## 6. Referencias

- Clark, J. T., Garrette, D., Turban, L., & Ruder, S. (2022). *Ultimate Tokenization with Canine: Pre-training with Characters*. arXiv:2203.09193.
- Hochreiter, S., & Schmidhuber, J. (1997). *Long Short-Term Memory*. Neural Computation, 9(8), 1735–1780.
- Schuster, M., & Paliwal, K. K. (1997). *Bidirectional Recurrent Neural Networks*. IEEE Transactions on Signal Processing, 45(11), 2673–2681.
- Subashini, R., & Narmatha, T. (2024). *Detecting Malicious URLs Using BiLSTM-Based Deep Learning Model*.
- Zonyfar, M., Lee, T., & Kim, J. (2023). *Detecting Malicious URLs Using Hybrid CNN-LSTM Architecture*.
- Akiba, T., Sano, S., Yanase, T., Ohta, T., & Koyama, M. (2019). *Optuna: A Next-generation Hyperparameter Optimization Framework*. KDD.
