# Model Card: CANINE-S URL Malicious Detector

> ## ⚠️ ACTUALIZACIÓN (2026-08-15) — el modelo desplegado en la app NO es CANINE-S
>
> La app Android empaqueta un **LSTM char-level real** (~830 KB) en
> `android/app/src/main/assets/ml/lstm_model.tflite`, cargado por
> `MotorInferenciaReal` (fallback GPU → NNAPI → CPU). Sus parámetros
> reales están en `assets/ml/model_metadata.json`:
>
> | Atributo | Valor real (desplegado) |
> |----------|--------------------------|
> | Arquitectura | Embedding(124→64) → LSTM(hidden 128, 2 capas, dropout 0.5) → Linear |
> | Pipeline | `A_regionalizado` |
> | MAX_LEN | **100** (percentil 95 del corpus; no 150) |
> | Vocab | 124 chars (codepoint-level, PAD=0, UNK=1) |
> | Salida | logit crudo (aplicar sigmoid) |
> | Umbrales | SEGURO < 0.3 ≤ SOSPECHOSO < 0.7 ≤ MALICIOSO |
> | Distribución | bundled en assets — **sin** Play Asset Delivery |
>
> El CANINE-S de ~500 MB descrito abajo es el **plan histórico** del
> notebook (`colab_lstm/` contiene la exportación TFLite del LSTM actual).
> Este documento se conserva como referencia del experimento Transformer.

## Modelo Details

| Atributo | Valor |
|----------|-------|
| **Nombre** | CANINE-S URL Malicious Detector |
| **Version** | 1.0 |
| **Fecha** | 2026-07-23 |
| **Arquitectura** | CANINE-S (Character-level Transformer, Google) + cabeza clasificadora binaria |
| **Modelo base** | `google/canine-s` (Clark et al., 2022) |
| **Parametros** | ~500M (CANINE-S) + ~1.2M (cabeza clasificadora) |
| **Tipo** | Clasificacion binaria (benigno / malicioso) |
| **Dominio** | Deteccion de URLs maliciosas (phishing) incrustadas en codigos QR |
| **Licencia modelo base** | Apache 2.0 (CANINE-S, Google) |

## Arquitectura

```
Input: URL (string, caracteres Unicode)
  ↓
CanineTokenizer (character-level, codepoint -> ID, sin subword BPE)
  ↓
CanineModel (google/canine-s, 12 capas Transformer, hidden_size=768)
  → last_hidden_state (B, L, 768)
  ↓
Masked Mean Pooling + Masked Max Pooling
  → concatenacion (B, 768*2 = 1536)
  ↓
LayerNorm(1536)
  ↓
Dropout (rate configurable, 0.1-0.5)
  ↓
Linear(1536, 1)
  → logit (float)
  ↓
sigmoid(logit) → probabilidad de URL maliciosa [0, 1]
```

### Hiperparametros (optimizados con Optuna TPE)

| Hiperparametro | Rango de busqueda | Valor optimo |
|----------------|-------------------|--------------|
| `lr` | [2e-5, 1e-4] (log) | [medir tras entrenamiento] |
| `batch_size` | {256, 512, 1024} | [medir tras entrenamiento] |
| `weight_decay` | [1e-4, 1e-2] (log) | [medir tras entrenamiento] |
| `dropout` | [0.1, 0.5] | [medir tras entrenamiento] |

### Configuracion de entrenamiento

| Parametro | Valor |
|-----------|-------|
| Optimizador | AdamW |
| Precision mixta | bf16 (autocast) |
| Loss | SmoothBCEWithLogitsLoss (smoothing=0.05) |
| Grad clip | max_norm=1.0 |
| Warmup | 3 epocas lineales |
| Scheduler | ReduceLROnPlateau (patience=2, factor=0.5, min_lr=1e-6) |
| Max epocas | 50 (early stopping patience=5) |
| Seed | 42 |
| Optuna trials | 50 |
| Optuna epocas por trial | 4 |
| Pruner | MedianPruner (n_startup=5, n_warmup=1) |
| CV | Stratified k-Fold k=3 |
| MAX_LEN | 150 (percentil 95 = 101, min forzado 150, max 1024) |

## Datos de Entrenamiento

| Corpus | Benignos | Phishing | Total |
|--------|----------|----------|-------|
| master_regionalizado (LatAm) | 50,000 | 50,000 | 100,000 |
| master_generico (Global) | 50,000 | 50,000 | 100,000 |
| **Total** | **100,000** | **100,000** | **200,000** |

### Division

- Train: 70% (estratificado por `estrato` = pais + label)
- Validation: 15%
- Test interno: 15%
- Tests externos: prueba_latam (3,000 URLs), prueba_generica (3,000 URLs)

### Preprocesamiento

1. `clean_url()`: strip(), eliminar protocolo (`http://`, `https://`, `ftp://`, `ftps://`) y `www.`
2. Tokenizacion CANINE: caracter -> Unicode codepoint ID (sin vocabulario externo)
3. Truncamiento: max 150 caracteres
4. Padding: dinamico por batch (pad_sequence, PAD_IDX=0)

## Evaluacion

### Metricas

Las siguientes metricas se reportan tras el entrenamiento (Fase 6):

| Metrica | Definicion | Objetivo |
|---------|-----------|----------|
| Accuracy | (TP + TN) / Total | > 0.90 |
| Precision | TP / (TP + FP) | > 0.90 |
| Recall | TP / (TP + FN) | > 0.85 |
| F1-Score | 2 * P * R / (P + R) | > 0.88 |
| AUC-ROC | Area bajo curva ROC | > 0.93 |
| PR-AUC | Area bajo curva Precision-Recall | > 0.90 |

### Sets de Evaluacion

| Set | Origen | URLs | Descripcion |
|-----|--------|------|-------------|
| Test interno (15%) | Split de master | ~30,000 | Evaluacion durante desarrollo |
| Test LATAM (externo) | prueba_latam/ | 3,000 | Independiente, URLs con etiqueta geografica LatAm |
| Test Generico (externo) | prueba_generica/ | 3,000 | Independiente, URLs globales sin etiqueta regional |

## Conversion a TFLite

| Paso | Formato | Tamano estimado |
|------|---------|-----------------|
| 1. Entrenamiento | PyTorch `.pt` (FP32/bf16) | ~2 GB |
| 2. Export ONNX | `.onnx` | ~2 GB |
| 3. Conversion TFLite | `.tflite` (FP32) | ~2 GB |
| 4. Cuantizacion INT8 (dynamic range) | `model_int8.tflite` | ~500 MB |
| 5. (Opcional) Full INT8 + NNAPI | `model_int8_full.tflite` | ~500 MB |

### Estrategia de despliegue movil

- **Modelo unico:** CANINE-S INT8 (sin modelos alternativos)
- **Delegate:** NNAPI o GPU (seleccion automatica segun dispositivo)
- **Distribucion:** Play Asset Delivery (descarga on-demand, ~500MB)
- **Latencia objetivo:** 150-300 ms por URL (gama media con delegate)
- **Modo:** Offline 100% (sin conexion a servidor)

## Limitaciones

1. **Tamano:** ~500MB tras cuantizacion INT8. Requiere Play Asset Delivery.
2. **Latencia:** CANINE-S es un modelo de 500M parametros. Sin delegate NNAPI/GPU, la latencia puede superar 2s en CPU.
3. **Sesgo geografico:** El corpus regionalizado tiene sesgo hacia Colombia (45.9%) y Brasil (29.8%). Mexico, Argentina, Chile y Peru estan sub-representados.
4. **Sub-representacion:** 6 paises LatAm (Panama, Nicaragua, Honduras, El Salvador, Guatemala, Bolivia) tienen <100 URLs cada uno.
5. **MAX_LEN=150:** URLs mas largas se truncan. Solo ~5% del corpus supera 101 caracteres; 150 cubre >95%.
6. **Offline unicamente:** No hay modo online. Si el modelo falla, no hay respaldo.

## Auditoria del Corpus (Fase 0.0)

### Calidad del etiquetado geografico
- **Phishing LatAm:** 99.2% bien etiquetado (199 URLs de 25,000 sin referencia LatAm apreciable)
- **Benignos LatAm:** 99.8% bien etiquetado (52 URLs con TLDs no-LatAm)
- **Pais Generico (benigno):** 0% tienen TLD LatAm → confirmados como benignos globales genuine

### Hallazgos de balance
- Concentracion severa: 75.7% Colombia + Brasil
- 6 paises con <100 URLs (estadisticamente irrelevantes)
- Requiere aumento de corpus (~80,250 URLs adicionales) para balancear

## Referencias

- Clark, J. T., Garrette, D., Turban, L., & Ruder, S. (2022). *Ultimate Tokenization with Canine: Pre-training with Characters*. arXiv:2203.09193.
- Akiba, T., Sano, S., Yanase, T., Ohta, T., & Koyama, M. (2019). *Optuna: A Next-generation Hyperparameter Optimization Framework*. KDD.

## Como Reproducir

```bash
# 1. Entrenar modelo (requiere GPU A100 80GB o similar)
python train_canine_s.py --pipeline regionalizado --output_dir outputs_roberta

# 2. Exportar a ONNX
python export_onnx.py --model_path outputs_roberta/best_model.pt --output_path outputs_roberta/model.onnx

# 3. Convertir a TFLite INT8
python export_tflite.py --model_path outputs_roberta/best_model.pt --quantize dynamic --output_path outputs_roberta/model_int8.tflite

# 4. Evaluar
python evaluation/eval_model.py --model_path outputs_roberta/model_int8.tflite --model_type tflite --output_dir evaluation/results
```

---

**Autor:** [Nombre del estudiante]
**Proyecto:** Deep-Learning-URLs-maliciosas-incrustadas-QR
**Seminario 2:** Aplicacion Movil para la Deteccion de URLs Maliciosas Incrustadas en Codigos QR
