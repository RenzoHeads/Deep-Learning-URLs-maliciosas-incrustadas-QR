#!/usr/bin/env python3
"""
export_tflite.py — Convierte el RoBERTaModel CANINE-S a TFLite con
cuantización (INT8 dinámico, INT8 completo, o float16).

Estrategia:
    1. Preferir **ai-edge-torch** (PyTorch → TFLite directamente).   ← mejor ruta en Colab
    2. Caer a ONNX → TF SavedModel → TFLite usando **onnx2tf**.

Uso:
    # Cuantización de rango dinámico (por defecto, no necesita datos de calibración)
    python export_tflite.py --ruta_modelo outputs_roberta/best_model.pt \
                            --ruta_onnx model.onnx \
                            --ruta_salida model_dynamic.tflite \
                            --quantize dynamic

    # Cuantización INT8 completa (usa datos sintéticos de calibración)
    python export_tflite.py ... --quantize int8

    # float16
    python export_tflite.py ... --quantize float16

Requisitos (Colab):
    pip install torch transformers onnxruntime onnx ai-edge-torch
    # O para la ruta de respuesto:
    pip install onnx2tf tensorflow
"""

import argparse
import os
import sys
import tempfile

import numpy as np
import torch

# Asegurar que el directorio raiz del proyecto este en sys.path para que
# los imports `from ml_comun...` funcionen sea cual sea el CWD.
_PROYECTO_RAIZ = os.path.dirname(os.path.abspath(__file__))
if _PROYECTO_RAIZ not in sys.path:
    sys.path.insert(0, _PROYECTO_RAIZ)

from ml_comun.modelo import RoBERTaModel
from ml_comun.loaders import cargar_modelo

PAD_IDX = 0

# ---------------------------------------------------------------------------
# Funciones auxiliares de cuantización
# ---------------------------------------------------------------------------

def representative_dataset(max_len: int, num_samples: int = 20, pad_frac: float = 0.1):
    """Produce muestras representativas para la calibración INT8 completa."""
    rng = np.random.default_rng(42)
    for _ in range(num_samples):
        sample = rng.integers(
            low=1, high=0x10FFFF, size=(1, max_len), dtype=np.int64
        )
        n_pad = max(1, int(max_len * pad_frac))
        sample[:, -n_pad:] = PAD_IDX
        yield [sample]


# ---------------------------------------------------------------------------
# Ruta 1: ai-edge-torch (PyTorch → TFLite directamente)
# ---------------------------------------------------------------------------

def convert_with_ai_edge_torch(
    model: RoBERTaModel,
    ruta_salida: str,
    max_len: int,
    quantize: str,
) -> bool:
    """Intenta convertir vía ai-edge-torch. Devuelve True si tiene éxito."""
    try:
        import ai_edge_torch
        import tensorflow as tf
        from ai_edge_torch import convert
        from ai_edge_torch.quantize import quant_config
    except ImportError:
        print("[INFO] ai-edge-torch no disponible — se probará la ruta ONNX.")
        return False

    print("[INFO] Intentando conversión vía ai-edge-torch ...")

    # La entrada de muestra debe ser una tupla de tensores para ai-edge-torch
    dummy_input = (
        torch.randint(low=1, high=0x10FFFF, size=(1, max_len), dtype=torch.long),
    )
    # Insertar algo de relleno (nota: esto es una tupla, el tensor es el elemento [0])
    dummy_input[0][:, -5:] = PAD_IDX

    # Construir el wrapper TorchScript — ai-edge-torch espera un modelo cuyo
    # forward coincida con la firma de entrada proporcionada
    # Envolver forward para que acepte un solo argumento tensor (el elemento de la tupla)
    try:
        torch_model = _WrapSingleInput(model)

        # ------------------------------------------------------------------
        # Configuración opcional de cuantización para ai-edge-torch.
        # ai-edge-torch soporta un QuantConfig por tensor; aquí aplicamos una
        # cuantización broad int8 solo-pesos (equivalente a rango dinámico) cuando
        # se solicita, y pesos float16 para el modo float16.
        # ------------------------------------------------------------------
        qconfig = None
        if quantize in ("int8", "dynamic"):
            try:
                from ai_edge_torch.quantize import quant_config
                # Estilo rango dinámico: cuantizar pesos a int8, activaciones float
                qconfig = quant_config.QuantConfig(
                    weight_precision=8,
                )
            except Exception:
                qconfig = None  # caer a post-cuantización TFLite

        # Firma de ai-edge-torch `convert()`:
        #   convert(model, args, quant_config=None)
        edge_model = convert.convert(torch_model, dummy_input, quant_config=qconfig)
        raw_bytes = edge_model.bytes  # flatbuffer TFLite crudo

        with open(ruta_salida, "wb") as f:
            f.write(raw_bytes)
        print(f"[INFO] Conversión ai-edge-torch → {ruta_salida}")
        return True
    except Exception as e:
        print(f"[AVISO] Conversión ai-edge-torch falló: {e}")
        return False


class _WrapSingleInput(nn.Module):
    """Adaptador para que forward(input_ids) coincida con la firma de entrada única de ai-edge-torch."""
    def __init__(self, inner: RoBERTaModel):
        super().__init__()
        self.inner = inner

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.inner(x)


# ---------------------------------------------------------------------------
# Ruta 2: ONNX → TF SavedModel → TFLite (vía onnx2tf)
# ---------------------------------------------------------------------------

def convert_onnx_to_tflite(
    ruta_onnx: str,
    ruta_salida: str,
    max_len: int,
    quantize: str,
) -> bool:
    """Convierte un modelo ONNX existente a TFLite vía onnx2tf."""
    try:
        import tensorflow as tf  # noqa: F401
    except ImportError:
        print("[ERROR] TensorFlow no instalado — no se puede convertir vía la ruta ONNX.")
        return False

    try:
        import onnx2tf  # noqa: F401
    except ImportError:
        print("[ERROR] onnx2tf no está instalado (pip install onnx2tf).")
        return False

    with tempfile.TemporaryDirectory(prefix="tflite_conv_") as tmpdir:
        saved_model_dir = os.path.join(tmpdir, "saved_model")

        try:
            import onnx2tf
            tf_model = onnx2tf.convert(
                input_onnx_file_path=ruta_onnx,
                output_folder_path=saved_model_dir,
                non_verbose=True,
            )
        except Exception as e:
            # Respuesto CLI de onnx2tf
            try:
                print(f"[INFO] API Python de onnx2tf falló ({e}); probando CLI ...")
                import subprocess
                subprocess.check_call(
                    ["onnx2tf", "-i", ruta_onnx, "-o", saved_model_dir]
                )
            except Exception as e2:
                print(f"[ERROR] onnx2tf falló (tanto API como CLI): {e2}")
                return False

        # ----- TF SavedModel → TFLite cuantizado -----
        print(f"[INFO] Exportando TFLite cuantizado ({quantize}) ...")
        converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)

        if quantize == "float16":
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            converter.target_spec.supported_types = [tf.float16]
        elif quantize == "dynamic":
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            # Cuantización de rango dinámico — no necesita dataset representativo
        elif quantize == "int8":
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            converter.target_spec.supported_ops = [
                tf.lite.OpsSet.TFLITE_BUILTINS_INT8,
            ]
            converter.inference_input_type = tf.int8
            converter.inference_output_type = tf.int8
            converter.representative_dataset = lambda: representative_dataset(max_len)
        else:
            print(f"[AVISO] quantize={quantize!r} desconocido; usando sin optimización.")

        try:
            tflite_bytes = converter.convert()
        except Exception as e:
            print(f"[ERROR] Conversión TFLite falló: {e}")
            # Reintentar solo con optimizaciones DEFAULT y relajar restricciones INT8
            converter2 = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
            converter2.optimizations = [tf.lite.Optimize.DEFAULT]
            # Quitar la restricción de tipo de entrada int8 — caer a entrada float
            if quantize == "int8":
                converter2.target_spec.supported_ops = [
                    tf.lite.OpsSet.TFLITE_BUILTINS,
                    tf.lite.OpsSet.SELECT_TF_OPS,
                ]
                # todavía proporcionar datos representativos para cuantización de pesos
                converter2.representative_dataset = lambda: representative_dataset(max_len)
                try:
                    tflite_bytes = converter2.convert()
                    print("[INFO] Recuperado con respuesto SELECT_TF_OPS.")
                except Exception as e2:
                    print(f"[ERROR] Conversión relajada también falló: {e2}")
                    return False
            else:
                return False

        with open(ruta_salida, "wb") as f:
            f.write(tflite_bytes)
        print(f"[INFO] TFLite guardado → {ruta_salida} ({len(tflite_bytes)} bytes)")
        return True


# ---------------------------------------------------------------------------
# Validación
# ---------------------------------------------------------------------------

def validate_tflite(ruta_salida: str, max_len: int) -> None:
    """Carga el modelo TFLite y ejecuta un forward pass para confirmar que produce salida."""
    try:
        # TFLite Interpreter es parte del paquete `tflite_runtime` o `tensorflow`
        try:
            from tflite_runtime.interpreter import Interpreter
        except ImportError:
            from tensorflow.lite.python.interpreter import Interpreter
    except ImportError:
        print("[ERROR] No se puede importar TFLite Interpreter — "
              "instalar `tensorflow` o `tflite_runtime`.")
        sys.exit(1)

    print(f"[INFO] Validando modelo TFLite: {ruta_salida}")
    if not os.path.isfile(ruta_salida):
        raise FileNotFoundError(f"Modelo TFLite no encontrado: {ruta_salida}")

    interp = Interpreter(ruta_modelo=ruta_salida)
    interp.allocate_tensors()

    in_det = interp.get_input_details()[0]
    out_det = interp.get_output_details()[0]
    print(f"[INFO] entrada  = {in_det['name']!r}  shape={in_det['shape']}  dtype={in_det['dtype']}")
    print(f"[INFO] salida   = {out_det['name']!r} shape={out_det['shape']} dtype={out_det['dtype']}")

    # Construir entrada de prueba coincidiendo shape/dtype esperado (int8 para int8 completo, sino int64/float32)
    input_dtype = in_det["dtype"]
    input_shape = in_det["shape"]
    if np.issubdtype(input_dtype, np.integer):
        x = np.random.randint(
            low=1, high=min(0x10FFFF, np.iinfo(input_dtype).max - 1),
            size=tuple(input_shape),
        ).astype(input_dtype)
    else:  # float
        # escalar codepoints int a [0,1] o pasarlos directamente — el modelo espera ints
        # pero para modelos cuantizados de entrada float los proveemos como float
        x = np.random.randint(
            low=1, high=0x10FFFF,
            size=tuple(input_shape),
        ).astype(np.float32)
    # Relleno (últimos 5 tokens)
    x[..., -5:] = PAD_IDX
    # Algunos conversores pueden esperar NHWC; TFLite maneja codepoints int como entradas planas

    interp.set_tensor(in_det["index"], x)
    interp.invoke()
    y = interp.get_tensor(out_det["index"])
    print(f"[INFO] Inferencia ejecutada; shape de salida={y.shape}")
    print(f"[INFO] Muestra de salida (primera fila): {y.flatten()[:8]}")
    assert y is not None and y.size > 0, "¡Salida vacía!"
    print("[OK] Validación TFLite exitosa.")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def construir_parser_args() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Convertir CANINE-S RoBERTaModel → TFLite "
                    "(con cuantización INT8/dinámica/float16)."
    )
    p.add_argument("--ruta_modelo", type=str, default="salida_roberta/mejor_modelo.pt",
                   help="Ruta al checkpoint PyTorch entrenado (.pt).")
    p.add_argument("--ruta_onnx", type=str, default="model.onnx",
                   help="Ruta al modelo ONNX exportado (usado por la ruta de respuesto).")
    p.add_argument("--ruta_salida", type=str, default="model.tflite",
                   help="Ruta donde escribir el modelo TFLite.")
    p.add_argument("--max_len", type=int, default=512,
                   help="Longitud máxima de secuencia (por defecto 512).")
    p.add_argument("--quantize", type=str,
                   choices=["dynamic", "int8", "float16"], default="dynamic",
                   help="Modo de cuantización (por defecto 'dynamic').")
    p.add_argument("--nombre_modelo", type=str, default="google/canine-s",
                   help="Nombre en HF Hub del backbone CANINE-S.")
    p.add_argument("--force_onnx", action="store_true",
                   help="Saltar ai-edge-torch y usar la ruta ONNX directamente.")
    return p


def main() -> None:
    args = construir_parser_args().parse_args()

    success = False

    # -------------------------------------------------------------------
    # Ruta 1: ai-edge-torch (preferida)
    # -------------------------------------------------------------------
    if not args.force_onnx:
        print("[INFO] Cargando modelo PyTorch para la ruta ai-edge-torch ...")
        try:
            modelo_base = RoBERTaModel(nombre_modelo=args.nombre_modelo)
            model = cargar_modelo(args.ruta_modelo, modelo_base)
            success = convert_with_ai_edge_torch(
                model, args.ruta_salida, args.max_len, args.quantize
            )
        except Exception as e:
            print(f"[AVISO] Ruta ai-edge-torch lanzó: {e}")
            success = False

    # -------------------------------------------------------------------
    # Ruta 2: ONNX → TF SavedModel → TFLite (respuesto)
    # -------------------------------------------------------------------
    if not success:
        print("[INFO] Cayendo a ruta ONNX → TFLite ...")
        if not os.path.isfile(args.ruta_onnx):
            print(f"[ERROR] Modelo ONNX no encontrado: {args.ruta_onnx}. "
                  "Ejecutar export_onnx.py primero o proveer --ruta_onnx.")
            sys.exit(1)
        success = convert_onnx_to_tflite(
            args.ruta_onnx, args.ruta_salida, args.max_len, args.quantize
        )

    # -------------------------------------------------------------------
    # ¿Listo?
    # -------------------------------------------------------------------
    if not success:
        print("[ERROR] Todas las rutas de conversión fallaron.")
        print("[HINT] En Colab instalar: "
              "`pip install ai-edge-torch` O `pip install onnx2tf tensorflow`.")
        sys.exit(1)

    # -------------------------------------------------------------------
    # Validar
    # -------------------------------------------------------------------
    validate_tflite(args.ruta_salida, args.max_len)
    print("[HECHO] Conversión y validación TFLite finalizadas.")


if __name__ == "__main__":
    main()
