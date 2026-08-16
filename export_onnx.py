#!/usr/bin/env python3
"""
export_onnx.py — Exporta el modelo PyTorch CANINE-S entrenado a formato ONNX.

Arquitectura (RoBERTaModel):
    - CanineModel.from_pretrained('google/canine-s') como backbone
    - nn.LayerNorm(768*2)
    - nn.Dropout(p)
    - nn.Linear(770*2, 1)
  Forward: masked mean pooling + masked max pooling → concat → LayerNorm → Dropout → Linear

Uso:
    python export_onnx.py --ruta_modelo outputs_roberta/best_model.pt \
                          --ruta_salida model.onnx --max_len 512
"""

import argparse
import os
import sys

import numpy as np
import torch

# Asegurar que el directorio raiz del proyecto este en sys.path para que
# los imports `from ml_comun...` funcionen sea cual sea el CWD.
_PROYECTO_RAIZ = os.path.dirname(os.path.abspath(__file__))
if _PROYECTO_RAIZ not in sys.path:
    sys.path.insert(0, _PROYECTO_RAIZ)

from ml_comun.modelo import RoBERTaModel
from ml_comun.loaders import cargar_modelo

PAD_IDX = 0  # Codepoint Unicode 0 usado como token de relleno


# ---------------------------------------------------------------------------
# Lógica de exportación
# ---------------------------------------------------------------------------


def export_to_onnx(
    model: RoBERTaModel,
    ruta_salida: str,
    max_len: int,
    batch_size: int = 1,
) -> None:
    """Exporta *model* a ONNX con dimensiones dinámicas de batch y secuencia."""
    # Entrada dummy: codepoints int64 [B, L]
    dummy = torch.randint(low=1, high=0x10FFFF, size=(batch_size, max_len), dtype=torch.long)
    # Insertar algo de relleno
    dummy[:, -5:] = PAD_IDX

    print(f"[INFO] Exportando a ONNX (shape={tuple(dummy.shape)}) → {ruta_salida}")
    torch.onnx.export(
        model,
        dummy,
        ruta_salida,
        export_params=True,          # embeber pesos
        opset_version=18,
        do_constant_folding=True,
        input_names=["input_ids"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "seq_len"},
            "logits":    {0: "batch"},
        },
    )
    print(f"[INFO] Exportación ONNX completa → {ruta_salida}")


def validate_onnx(
    model: RoBERTaModel,
    onnx_path: str,
    max_len: int,
    batch_size: int = 4,
    tol: float = 1e-4,
) -> None:
    """Ejecuta tanto PyTorch como ONNXRuntime con la misma entrada aleatoria y compara."""
    import onnx
    import onnxruntime as ort  # noqa: E402

    print("[INFO] Validando modelo ONNX contra PyTorch ...")
    onnx.checker.check_model(onnx_path)

    dummy = torch.randint(low=1, high=0x10FFFF, size=(batch_size, max_len), dtype=torch.long)
    dummy[:, -7:] = PAD_IDX  # algo de relleno

    # ----- PyTorch -----
    with torch.no_grad():
        py_out = model(dummy).cpu().numpy()

    # ----- ONNX Runtime -----
    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name
    onnx_out = sess.run(None, {input_name: dummy.numpy()})[0]

    max_diff = float(np.max(np.abs(py_out - onnx_out)))
    print(f"[INFO] Shape salida PyTorch: {py_out.shape}")
    print(f"[INFO] Shape salida ONNX:    {onnx_out.shape}")
    print(f"[INFO] Diferencia absoluta máxima: {max_diff:.2e}")

    if max_diff < tol:
        print(f"[OK] Validación ONNX exitosa (diff < {tol:.0e}).")
    else:
        print(f"[FALLO] Validación ONNX FALLÓ (diff {max_diff:.2e} >= {tol:.0e}).")
        sys.exit(1)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def construir_parser_args() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Exportar CANINE-S RoBERTaModel → ONNX")
    p.add_argument(
        "--ruta_modelo", type=str, default="salida_roberta/mejor_modelo.pt",
        help="Ruta al checkpoint PyTorch entrenado (.pt).",
    )
    p.add_argument(
        "--ruta_salida", type=str, default="model.onnx",
        help="Dónde escribir el modelo ONNX exportado.",
    )
    p.add_argument(
        "--max_len", type=int, default=512,
        help="Longitud máxima de secuencia para la entrada dummy de exportación (por defecto 512).",
    )
    p.add_argument(
        "--nombre_modelo", type=str, default="google/canine-s",
        help="Nombre en HF Hub del backbone CANINE-S (por defecto 'google/canine-s').",
    )
    return p


def main() -> None:
    args = construir_parser_args().parse_args()

    modelo_base = RoBERTaModel(nombre_modelo=args.nombre_modelo)
    model = cargar_modelo(args.ruta_modelo, modelo_base)
    export_to_onnx(model, args.ruta_salida, max_len=args.max_len)
    validate_onnx(model, args.ruta_salida, max_len=args.max_len)
    print("[HECHO] Exportación y validación ONNX finalizadas.")


if __name__ == "__main__":
    main()
