"""RoBERTaModel unico — cabeza de clasificacion CANINE-S consolidada.

Consolida 3 copias previas con arquitecturas equivalentes pero firmes
distintas:

  - `train_canine_s.py::RoBERTaModel` (L184):
      __init__(dropout, pad_idx) — backbone hardcoded a `google/canine-s`,
      pooling inline (sin metodos estaticos).
  - `export_onnx.py::RoBERTaModel` (L32):
      __init__(nombre_modelo, dropout) — backbone configurable,
      pooling como metodos estaticos `_masked_mean`/`_masked_max`.
  - `export_tflite.py::RoBERTaModel` (L45):
      identica a export_onnx (duplicada "para ser autocontenido").

La firma unificada acepta todos los callers previos con defaults sensatos:

    __init__(nombre_modelo="google/canine-s", dropout=0.1, pad_idx=0)

  - `nombre_modelo`: backbone HuggingFace (default `"google/canine-s"`)
  - `dropout`: tasa de dropout en el clasificador (default `0.1`)
  - `pad_idx`: token ID de padding (default `0`). Se usa solo para construir
    `attention_mask` cuando el caller no la pasa explicita. Como la mascara
    de padding enmascara estos tokens en runtime, el default `0` es seguro
    incluso si el tokenizer usa otro pad_idx.

El nombre `RoBERTaModel` se mantiene por compatibilidad con los scripts de
exportacion (`export_onnx.py`, `export_tflite.py`) y para que las claves
de `state_dict` (`canine.*`, `layer_norm.*`, `classifier.*`) carguen sin
renombrado desde checkpoints pre-existentes.

Arquitectura (preservada intacta):
    CANINE-S (google/canine-s) -> (B, L, 768)
    masked mean pooling + masked max pooling -> concat -> (B, 1536)
    LayerNorm(1536) -> Dropout -> Linear(1536, 1) -> logits (B, 1)
"""

import torch
import torch.nn as nn
from transformers import CanineModel


class RoBERTaModel(nn.Module):
    """Cabeza de clasificacion CANINE-S con masked mean+max pooling.

    Forward:
        input_ids: int64 [B, L] de codepoints Unicode (PAD_IDX == 0).
        attention_mask (opcional): int64 [B, L], 1 para tokens reales,
            0 para padding. Si es None, se calcula como
            `(input_ids != self.pad_idx).long()`.

    Salida: float32 [B, 1] — logits / salida de regresion.
    """

    def __init__(
        self,
        nombre_modelo: str = "google/canine-s",
        dropout: float = 0.1,
        pad_idx: int = 0,
    ) -> None:
        super().__init__()
        self.pad_idx = pad_idx
        self.canine = CanineModel.from_pretrained(nombre_modelo)
        hidden = self.canine.config.hidden_size  # 768
        # Los nombres `layer_norm` y `classifier` DEben coincidir con los
        # guardados por `train_canine_s.py` para que los checkpoints carguen
        # con las claves de `state_dict` correctas.
        self.layer_norm = nn.LayerNorm(hidden * 2)
        self.dropout = nn.Dropout(dropout)
        self.classifier = nn.Linear(hidden * 2, 1)

    # ------------------------------------------------------------------
    # Funciones auxiliares de pooling enmascarado (estilo export_onnx.py)
    # ------------------------------------------------------------------
    @staticmethod
    def _masked_mean(
        tensor: torch.Tensor,
        mask: torch.Tensor,
        dim: int = 1,
    ) -> torch.Tensor:
        """Media sobre *dim*, ignorando posiciones donde mask==0."""
        mask = mask.unsqueeze(-1).float()                    # [B, L, 1]
        summed = (tensor * mask).sum(dim=dim)                 # [B, H]
        counts = mask.sum(dim=dim).clamp(min=1.0)             # [B, 1]
        return summed / counts                               # [B, H]

    @staticmethod
    def _masked_max(
        tensor: torch.Tensor,
        mask: torch.Tensor,
        dim: int = 1,
    ) -> torch.Tensor:
        """Maximo sobre *dim*, ignorando posiciones donde mask==0 (se ponen a -1e9)."""
        mask = mask.unsqueeze(-1).float()                    # [B, L, 1]
        masked = tensor.masked_fill(mask == 0, -1e9)
        row_all_masked = (mask.sum(dim=dim) == 0)            # [B, 1]
        maxed, _ = masked.max(dim=dim)                       # [B, H]
        maxed = maxed.masked_fill(row_all_masked, 0.0)
        return maxed

    # ------------------------------------------------------------------
    # Forward
    # ------------------------------------------------------------------
    def forward(
        self,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor = None,
    ) -> torch.Tensor:
        """Args:
            input_ids: tensor int64 [B, L] de codepoints Unicode (PAD_IDX == 0).
            attention_mask: tensor int64 [B, L], 1 para tokens reales,
                0 para padding. Si es None, se calcula como
                (input_ids != self.pad_idx).long().

        Returns:
            torch.Tensor [B, 1] — logits / salida de regresion.
        """
        if attention_mask is None:
            attention_mask = (input_ids != self.pad_idx).long()

        outputs = self.canine(input_ids=input_ids, attention_mask=attention_mask)
        last_hidden = outputs.last_hidden_state              # [B, L, 768]

        mean = self._masked_mean(last_hidden, attention_mask, dim=1)  # [B, 768]
        mx = self._masked_max(last_hidden, attention_mask, dim=1)    # [B, 768]
        pooled = torch.cat([mean, mx], dim=-1)                       # [B, 1536]

        pooled = self.layer_norm(pooled)
        pooled = self.dropout(pooled)
        return self.classifier(pooled)                               # [B, 1]
