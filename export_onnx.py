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
import sys
import os

import numpy as np
import torch
import torch.nn as nn
from transformers import CanineModel

# ---------------------------------------------------------------------------
# Definición del modelo — DEBE coincidir exactamente con el entrenamiento
# ---------------------------------------------------------------------------

PAD_IDX = 0  # Codepoint Unicode 0 usado como token de relleno

class RoBERTaModel(nn.Module):
    """
    Cabeza de clasificación/regresión basada en CANINE-S.

    Agrega el pooling de los embeddings de tokens CANINE mediante *media* y
    *máximo* enmascarados sobre la dimensión de secuencia, los concatena
    (768*2 == 1536), y aplica una cabeza LayerNorm → Dropout → Linear(1536, 1).
    """

    def __init__(
        self,
        nombre_modelo: str = "google/canine-s",
        dropout: float = 0.1,
    ) -> None:
        super().__init__()
        self.canine = CanineModel.from_pretrained(nombre_modelo)
        hidden = self.canine.config.hidden_size  # 768
        self.layer_norm = nn.LayerNorm(hidden * 2)
        self.dropout = nn.Dropout(dropout)
        self.classifier = nn.Linear(hidden * 2, 1)

    # ------------------------------------------------------------------
    # Funciones auxiliares de pooling enmascarado
    # ------------------------------------------------------------------
    @staticmethod
    def _masked_mean(
        tensor: torch.Tensor,
        mask: torch.Tensor,
        dim: int = 1,
    ) -> torch.Tensor:
        """Media sobre *dim*, ignorando posiciones donde mask==0."""
        mask = mask.unsqueeze(-1).float()                       # [B, L, 1]
        summed = (tensor * mask).sum(dim=dim)                    # [B, H]
        counts = mask.sum(dim=dim).clamp(min=1.0)                # [B, 1]
        return summed / counts                                   # [B, H]

    @staticmethod
    def _masked_max(
        tensor: torch.Tensor,
        mask: torch.Tensor,
        dim: int = 1,
    ) -> torch.Tensor:
        """Máximo sobre *dim*, ignorando posiciones donde mask==0 (se ponen a -1e9)."""
        mask = mask.unsqueeze(-1).float()                        # [B, L, 1]
        masked = tensor.masked_fill(mask == 0, -1e9)
        # Si una fila entera está enmascarada, el valor vuelve a 0
        row_all_masked = (mask.sum(dim=dim) == 0)                # [B, 1]
        maxed, _ = masked.max(dim=dim)                           # [B, H]
        maxed = maxed.masked_fill(row_all_masked, 0.0)
        return maxed

    # ------------------------------------------------------------------
    # Forward
    # ------------------------------------------------------------------
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """
        Args:
            x: tensor int64 [B, L] de codepoints Unicode (PAD_IDX == 0).
        Returns:
            torch.Tensor [B, 1] — logits / salida de regresión.
        """
        attention_mask = (x != PAD_IDX).long()                   # [B, L]
        outputs = self.canine(input_ids=x, attention_mask=attention_mask)
        last_hidden = outputs.last_hidden_state                 # [B, L, 768]

        mean = self._masked_mean(last_hidden, attention_mask, dim=1)  # [B, 768]
        mx = self._masked_max(last_hidden, attention_mask, dim=1)    # [B, 768]
        pooled = torch.cat([mean, mx], dim=-1)                       # [B, 1536]

        pooled = self.layer_norm(pooled)
        pooled = self.dropout(pooled)
        return self.classifier(pooled)                               # [B, 1]


# ---------------------------------------------------------------------------
# Lógica de exportación
# ---------------------------------------------------------------------------

def cargar_modelo(ruta_modelo: str, nombre_modelo: str = "google/canine-s") -> RoBERTaModel:
    """Instancia RoBERTaModel y carga los pesos entrenados desde *ruta_modelo*."""
    print(f"[INFO] Construyendo RoBERTaModel desde {nombre_modelo!r} ...")
    model = RoBERTaModel(nombre_modelo=nombre_modelo)

    print(f"[INFO] Cargando checkpoint desde {ruta_modelo} ...")
    if not os.path.isfile(ruta_modelo):
        raise FileNotFoundError(f"Checkpoint del modelo no encontrado: {ruta_modelo}")

    state = torch.load(ruta_modelo, map_location="cpu", weights_only=False)
    # Soportar tanto state_dict directo como wrappers {"model": state_dict, ...}
    if isinstance(state, dict) and not any(k.startswith("canine.") for k in state.keys()):
        # Probar claves de formatos wrapper comunes
        for key in ("model", "state_dict", "module", "model_state_dict"):
            if key in state:
                state = state[key]
                break

    missing, unexpected = model.load_state_dict(state, strict=False)

    # La cabeza clasificadora (layer_norm/classifier) NO viene del
    # from_pretrained — si el checkpoint no la trae, el modelo exportado
    # predice con pesos aleatorios. Abortar en vez de exportar basura.
    cabeza_faltante = [k for k in missing if k.startswith(("layer_norm.", "classifier."))]
    if cabeza_faltante:
        raise RuntimeError(
            f"El checkpoint no contiene los pesos de la cabeza clasificadora "
            f"({len(cabeza_faltante)} claves, ej. {cabeza_faltante[:3]}). "
            "Un checkpoint entrenado con un nombre de head distinto (p.ej. "
            "'clasificador') descarta esos pesos silenciosamente — verifica "
            "que el checkpoint proviene del mismo script de entrenamiento."
        )
    divergencia_head = [k for k in unexpected if k.startswith(("clasificador.",))]
    if divergencia_head:
        raise RuntimeError(
            f"El checkpoint usa el nombre de head 'clasificador' "
            f"({divergencia_head[:2]}...) pero este script espera 'classifier'. "
            "Reentrenar con la version actual de train_canine_s.py."
        )
    if missing:
        print(f"[AVISO] Claves faltantes: {len(missing)} (ej. {missing[:3]})")
    if unexpected:
        print(f"[AVISO] Claves inesperadas: {len(unexpected)} (ej. {unexpected[:3]})")

    model.eval()
    return model


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

    model = cargar_modelo(args.ruta_modelo, nombre_modelo=args.nombre_modelo)
    export_to_onnx(model, args.ruta_salida, max_len=args.max_len)
    validate_onnx(model, args.ruta_salida, max_len=args.max_len)
    print("[HECHO] Exportación y validación ONNX finalizadas.")


if __name__ == "__main__":
    main()
