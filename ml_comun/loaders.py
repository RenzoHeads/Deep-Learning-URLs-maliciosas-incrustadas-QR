"""Loaders consolidados — `cargar_modelo` (state_dict) y `cargar_tflite`.

Consolida duplicaciones detectadas:

  - `export_onnx.py::cargar_modelo` (L110):
      version completa con mensajes de error detallados sobre cabeza
      clasificadora faltante / divergente. Usada como baseline.
  - `export_tflite.py::cargar_modelo` (L88):
      version menos completa (mensajes mas breves). Eliminada.
  - `eval_model.py::cargar_modelo_tflite` (L175) y
    `eval_latency.py::cargar_tflite` (L77):
      variantes nominales de carga TFLite con fallback
      `tflite_runtime` -> `tensorflow.lite`. Consolidadas en
      `cargar_tflite`.

Limitacion conocida (NO consolidada en este refactor):
  - `eval_model.py::cargar_modelo_pytorch` (L151) hace `torch.load()`
    sin instanciar `RoBERTaModel` primero — asume que el checkpoint es
    el modelo pickleado (no `state_dict` suelto). Diverge de
    `train_canine_s.py` que guarda `state_dict()` (L534). Requiere fix
    downstream: instanciar `RoBERTaModel` + `load_state_dict` en vez de
    `torch.load` directo.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Union

import torch
import torch.nn as nn


def cargar_modelo(
    ruta: Union[str, Path],
    modelo_base: nn.Module,
    *,
    map_location: str = "cpu",
    strict: bool = False,
) -> nn.Module:
    """Carga un `state_dict` desde *ruta* dentro de *modelo_base*.

    Soporta tanto `state_dict` directo como wrappers comunes:
    `{"model": ...}`, `{"state_dict": ...}`, `{"module": ...}`,
    `{"model_state_dict": ...}`.

    Args:
        ruta: ruta al checkpoint `.pt` (state_dict o wrapper).
        modelo_base: instancia de `nn.Module` recien construida (con
            pesos aleatorios de `from_pretrained`). Se cargan los pesos
            del checkpoint en este modulo.
        map_location: device target para `torch.load` (default "cpu").
        strict: si True, `load_state_dict(strict=True)` aborta en
            cualquier missing/unexpected key (util cuando el caller
            quiere garantia fuerte de compatibilidad). Si False
            (default), tolera missing keys no criticas y solo aborta
            si faltan las claves de la cabeza clasificadora
            (`layer_norm.*`, `classifier.*`) o si el checkpoint usa el
            nombre divergente legacy `clasificador.*`.

    Returns:
        `nn.Module` — `modelo_base` con pesos cargados y en modo `eval()`.

    Raises:
        FileNotFoundError: si *ruta* no existe.
        RuntimeError: si `strict=False` y faltan las claves de la cabeza
            clasificadora, o si el checkpoint usa `clasificador.*` (legacy).
        RuntimeError: si `strict=True` y hay cualquier missing/unexpected
            key (lanzado por `torch.nn.Module.load_state_dict`).
    """
    ruta_str = str(ruta)
    if not os.path.isfile(ruta_str):
        raise FileNotFoundError(f"Checkpoint del modelo no encontrado: {ruta_str}")

    print(f"[INFO] Cargando checkpoint desde {ruta_str} ...")
    state = torch.load(ruta_str, map_location=map_location, weights_only=False)

    # Soportar tanto state_dict directo como wrappers comunes
    if isinstance(state, dict) and not any(k.startswith("canine.") for k in state.keys()):
        for key in ("model", "state_dict", "module", "model_state_dict"):
            if key in state:
                state = state[key]
                break

    missing, unexpected = modelo_base.load_state_dict(state, strict=strict)

    if not strict:
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

    modelo_base.eval()
    return modelo_base


def cargar_tflite(ruta: Union[str, Path], **kwargs):
    """Carga un interprete TFLite desde *ruta*.

    Consolida `eval_model.py::cargar_modelo_tflite` y
    `eval_latency.py::cargar_tflite`. Ambas son identicas salvo por
    `num_threads=1` en `eval_latency` (latency benchmark).

    Args:
        ruta: ruta al modelo `.tflite`.
        **kwargs: kwargs extra pasados al `Interpreter` (p.ej.
            `num_threads=1` para benchmarks de latencia single-thread).

    Returns:
        `tflite_runtime.interpreter.Interpreter` (o el equivalente de
        `tensorflow.lite.Interpreter`) con tensores ya allocados.
        Devuelve `None` si ni `tflite_runtime` ni `tensorflow` estan
        instalados — el caller debe manejar el caso.
    """
    try:
        import tflite_runtime.interpreter as tflite  # type: ignore
    except ImportError:
        try:
            import tensorflow.lite as tflite  # type: ignore
        except ImportError:
            print("[aviso] tflite_runtime/tensorflow no instalado - "
                  "no se puede cargar el modelo TFLite.", file=sys.stderr)
            return None
    interp = tflite.Interpreter(ruta_modelo=str(ruta), **kwargs)
    interp.allocate_tensors()
    return interp
