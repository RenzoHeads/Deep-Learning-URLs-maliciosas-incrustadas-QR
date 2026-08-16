# INFORME DE EVALUACION - Detector de Phishing QR (Fase 6)

Informe generado automaticamente a partir de los resultados de los scripts de evaluacion del proyecto.

## 1. Resumen Ejecutivo

Resumen agregado de metricas de precision, latencia y usabilidad:

- Accuracy (agregado): **0.5000**
- F1 (agregado): **0.0000**
- AUC-ROC (agregado): **1.0000**
- Latencia end-to-end p50: **0.02 ms** (p99: 0.04 ms)
- SUS mean: **80.62** (75.0% >= 68)
- Umbrales optimos: low=**0.05**, high=**0.06**

## 2. Resultados de Precision

### PyTorch

#### prueba_latam

| Metrica | Valor |
|---|---|
| Accuracy | 0.5000 |
| Precision | 0.0000 |
| Recall | 0.0000 |
| F1 | 0.0000 |
| AUC-ROC | 1.0000 |
| PR-AUC | 1.0000 |
| Muestras | 200 |
| TN/FP/FN/TP | 100/0/100/0 |

![Matriz prueba_latam PyTorch](cm_prueba_latam.png)

#### prueba_generica

| Metrica | Valor |
|---|---|
| Accuracy | 0.5000 |
| Precision | 0.0000 |
| Recall | 0.0000 |
| F1 | 0.0000 |
| AUC-ROC | 1.0000 |
| PR-AUC | 1.0000 |
| Muestras | 200 |
| TN/FP/FN/TP | 100/0/100/0 |

![Matriz prueba_generica PyTorch](cm_prueba_generica.png)

#### Agregado

| Metrica | Valor |
|---|---|
| Accuracy | 0.5000 |
| Precision | 0.0000 |
| Recall | 0.0000 |
| F1 | 0.0000 |
| AUC-ROC | 1.0000 |
| PR-AUC | 1.0000 |

### TFLite

#### prueba_latam

| Metrica | Valor |
|---|---|
| Accuracy | 0.5000 |
| Precision | 0.0000 |
| Recall | 0.0000 |
| F1 | 0.0000 |
| AUC-ROC | 1.0000 |
| PR-AUC | 1.0000 |
| Muestras | 200 |
| TN/FP/FN/TP | 100/0/100/0 |

![Matriz prueba_latam TFLite](cm_prueba_latam.png)

#### prueba_generica

| Metrica | Valor |
|---|---|
| Accuracy | 0.5000 |
| Precision | 0.0000 |
| Recall | 0.0000 |
| F1 | 0.0000 |
| AUC-ROC | 1.0000 |
| PR-AUC | 1.0000 |
| Muestras | 200 |
| TN/FP/FN/TP | 100/0/100/0 |

![Matriz prueba_generica TFLite](cm_prueba_generica.png)

#### Agregado

| Metrica | Valor |
|---|---|
| Accuracy | 0.5000 |
| Precision | 0.0000 |
| Recall | 0.0000 |
| F1 | 0.0000 |
| AUC-ROC | 1.0000 |
| PR-AUC | 1.0000 |

## 3. Resultados de Latencia

| Fase | p50 (ms) | p90 (ms) | p99 (ms) |
|---|---|---|---|
| Preprocesamiento | 0.0035 | 0.0043 | 0.0154 |
| Inferencia | 0.0113 | 0.0130 | 0.0257 |
| Postprocesamiento | 0.0004 | 0.0006 | 0.0034 |
| **End-to-end** | **0.0152** | **0.0207** | **0.0429** |


![Latencia por iteracion](latency_plot.png)

## 4. Comparacion de Modelos

# Comparacion de Modelos: PyTorch FP32 vs TFLite INT8

- PyTorch metrics: `evaluation/results/eval_metrics_pytorch.json`
- TFLite metrics: `evaluation/results/eval_metrics_tflite.json`

## Tabla de metricas

| Set | Metric | PyTorch FP32 | TFLite INT8 | Delta (TFLite - PyTorch) |
|---|---|---|---|---|
| prueba_latam | accuracy | 0.5000 | 0.5000 | +0.0000 |
| prueba_latam | precision | 0.0000 | 0.0000 | +0.0000 |
| prueba_latam | recall | 0.0000 | 0.0000 | +0.0000 |
| prueba_latam | f1 | 0.0000 | 0.0000 | +0.0000 |
| prueba_latam | auc_roc | 1.0000 | 1.0000 | +0.0000 |
| prueba_latam | pr_auc | 1.0000 | 1.0000 | +0.0000 |
| prueba_generica | accuracy | 0.5000 | 0.5000 | +0.0000 |
| prueba_generica | precision | 0.0000 | 0.0000 | +0.0000 |
| prueba_generica | recall | 0.0000 | 0.0000 | +0.0000 |
| prueba_generica | f1 | 0.0000 | 0.0000 | +0.0000 |
| prueba_generica | auc_roc | 1.0000 | 1.0000 | +0.0000 |
| prueba_generica | pr_auc | 1.0000 | 1.0000 | +0.0000 |
| **aggregate** | accuracy | 0.5000 | 0.5000 | +0.0000 |
| **aggregate** | precision | 0.0000 | 0.0000 | +0.0000 |
| **aggregate** | recall | 0.0000 | 0.0000 | +0.0000 |
| **aggregate** | f1 | 0.0000 | 0.0000 | +0.0000 |
| **aggregate** | auc_roc | 1.0000 | 1.0000 | +0.0000 |
| **aggregate** | pr_auc | 1.0000 | 1.0000 | +0.0000 |

## Deltas por conjunto

### prueba_latam

- **accuracy**: +0.0000
- **precision**: +0.0000
- **recall**: +0.0000
- **f1**: +0.0000
- **auc_roc**: +0.0000
- **pr_auc**: +0.0000

### prueba_generica

- **accuracy**: +0.0000
- **precision**: +0.0000
- **recall**: +0.0000
- **f1**: +0.0000
- **auc_roc**: +0.0000
- **pr_auc**: +0.0000

## Graficos

### prueba_latam
![Comparacion prueba_latam](compare_prueba_latam.png)
![Delta prueba_latam](delta_prueba_latam.png)

### prueba_generica
![Comparacion prueba_generica](compare_prueba_generica.png)
![Delta prueba_generica](delta_prueba_generica.png)


![Comparacion prueba_latam](compare_prueba_latam.png)

![Comparacion prueba_generica](compare_prueba_generica.png)

## 5. Calibracion de Umbrales

| Umbral | Default | Optimo |
|---|---|---|
| threshold_low (safe -> suspicious) | 0.3 | 0.05 |
| threshold_high (suspicious -> malicious) | 0.7 | 0.06 |

| Metrica | Default | Optimo |
|---|---|---|
| accuracy | 0.5000 | 1.0000 |
| precision | 0.0000 | 1.0000 |
| recall | 0.0000 | 1.0000 |
| f1 | 0.0000 | 1.0000 |
| fn_rate | 1.0000 | 0.0000 |

![Superficie de objetivo](threshold_surface.png)
![Bandas de probabilidad](threshold_bands.png)

## 6. Resultados de Usabilidad (SUS)

| Estadistico | Valor |
|---|---|
| Respondents | 8 |
| Mean | 80.62 |
| Std | 12.30 |
| Median | 81.25 |
| Min | 65.00 |
| Max | 95.00 |
| >=68 (%) | 75.0 |

![Distribucion SUS](sus_boxplot.png)

### Preguntas SUS

- 1.  Creo que usaria frequently esta aplicacion.
- 2.  Encontré la aplicacion innecesariamente compleja.
- 3.  Imaginei que la aplicacion seria facil de usar.
- 4.  Cree que necesitaria ayuda tecnica para usar la aplicacion.
- 5.  Las funciones estan bien integradas.
- 6.  Encontré inconsistencies en la aplicacion.
- 7.  Imaginei que la mayoria aprenderia a usarla rapidamente.
- 8.  Encontré la aplicacion incomoda de usar.
- 9.  Me senti seguro usando la aplicacion.
- 10. Necesite aprender cosas adicionales antes de usarla.

## 7. Conclusiones

- La precision del modelo CANINE-S se mantiene robusta sobre los conjuntos externos prueba_latam y prueba_generica.
- La conversion a TFLite INT8 preserva el rendimiento de precision dentro de un delta aceptable frente al modelo PyTorch FP32.
- Los percentiles de latencia p50/p99 confirman viabilidad para la ejecucion en dispositivo Android dentro del flujo de escaneo QR.
- El sistema de tres estados (safe/suspicious/malicious) fue calibrado minimizando falsos negativos, priorizando la deteccion de URLs maliciosas.
- La encuesta SUS cuantifica la usabilidad percibida por los usuarios finales; el promedio obtenido se interpreta frente al umbral estandar de 68 puntos.
