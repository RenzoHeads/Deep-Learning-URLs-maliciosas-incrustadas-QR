"""ml_comun — Codigo ML compartido para el pipeline CANINE-S.

Consolida las 3 categorias de duplicaciones detectadas en la auditoria
previa:

  - Limpieza de URL unica: 6 implementaciones previas con semanticas
    divergentes (case-sensitive vs insensitive, con/sin ftp|ftps, etc.).
    Ver `ml_comun.url`.

  - RoBERTaModel unico: 3 copias previas (`train_canine_s.py`,
    `export_onnx.py`, `export_tflite.py`) con arquitecturas equivalentes
    pero firmas distintas. Ver `ml_comun.modelo`.

  - `cargar_modelo` compartido: 2 copias previas del loader state_dict-style
    (`export_onnx.py::cargar_modelo` y `export_tflite.py::cargar_modelo`) mas
    variantes nominales (`eval_model.py::cargar_modelo_tflite`,
    `eval_latency.py::cargar_tflite`). Ver `ml_comun.loaders`.

Re-exports publicos: `limpiar_url`, `clean_url`, `remove_protocol`,
`limpiar` (URL), `RoBERTaModel` (modelo) y `cargar_modelo`,
`cargar_tflite` (loaders).

Los imports de `.modelo` y `.loaders` son diferidos (PEP 562) para que
`ml_comun.url` sea importable sin `torch` instalado — util en entornos
ligeros (Android-side tooling, tests locales sin dependencias ML). Los
simbolos pesados (`RoBERTaModel`, `cargar_modelo`, `cargar_tflite`) se
cargan bajo demanda al primer acceso via `__getattr__`.
"""

from .url import limpiar_url, clean_url, remove_protocol, limpiar

__all__ = [
    "limpiar_url",
    "clean_url",
    "remove_protocol",
    "limpiar",
    "RoBERTaModel",
    "cargar_modelo",
    "cargar_tflite",
]


def __getattr__(name: str):
    """Carga diferida de simbolos pesados (`modelo`, `loaders`).

    Evita que `import ml_comun` (o `from ml_comun.url import ...`) arrastre
    `torch` cuando el caller solo necesita limpieza de URLs.
    """
    if name == "RoBERTaModel":
        from .modelo import RoBERTaModel
        return RoBERTaModel
    if name == "cargar_modelo":
        from .loaders import cargar_modelo
        return cargar_modelo
    if name == "cargar_tflite":
        from .loaders import cargar_tflite
        return cargar_tflite
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
