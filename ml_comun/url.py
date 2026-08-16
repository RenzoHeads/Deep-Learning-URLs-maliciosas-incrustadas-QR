"""Limpieza de URLs — implementacion unica consolidada.

Consolida 6 implementaciones previas con semanticas divergentes en una
sola funcion `limpiar_url` con aliases de retro-compatibilidad (`clean_url`,
`remove_protocol`, `limpiar`).

Semanticas previas auditadas y su divergencia:

  C.1 `train_canine_s.py::limpiar_url` (L103):
      2 regex separadas, IGNORECASE, soporta ftp/ftps, soporta www.
  C.2 `evaluation/eval_model.py::clean_url` (L67):
      1 regex + manual, IGNORECASE, soporta ftp/ftps, www via lower().startswith
  C.3 `evaluation/eval_latency.py::clean_url` (L38):
      identica a C.2
  C.4 `colab_lstm/convert_tflite.py::remove_protocol` (L72):
      1 regex combinada `^(?:https?://)?(?:www\\.)?` — NO soporta ftp/ftps
  C.5 `colab_lstm/convert_tflite.py::clean_url` (L125):
      identica a C.1
  C.6 `colab_lstm/test_inference.py::limpiar` (L23):
      manual, case-SENSITIVE (anomalia — no limpiaba HTTP://)

La implementacion unificada toma el baseline mas completo (C.1 — 2 regex
separadas con IGNORECASE) y lo hace case-insensitive de forma estricta,
corrigiendo la anomalia C.6.
"""

import re

# Compiladas a nivel modulo para evitar el costo de re-compile en cada call.
# IGNORECASE至关重要: corrige el bug case-sensitive de `test_inference.py`.
_RE_PROTOCOLO = re.compile(r"^(?:https?|ftps?)://", re.IGNORECASE)
_RE_WWW = re.compile(r"^www\.", re.IGNORECASE)


def limpiar_url(url: str) -> str:
    """Quita el prefijo de protocolo (http/https/ftp/ftps) y `www.` inicial.

    Args:
        url: URL cruda. Acepta None, str, o cualquier objeto str-able.

    Returns:
        URL limpia (sin protocolo, sin `www.` inicial). Nunca None.
        Maneja None/vacio retornando "".
    """
    if url is None:
        return ""
    url = str(url).strip()
    url = _RE_PROTOCOLO.sub("", url)
    url = _RE_WWW.sub("", url)
    return url


# Aliases de retro-compatibilidad — preservan callsites existentes sin
# renombrarlos uno por uno. Cada alias apunta a la misma implementacion.
clean_url = limpiar_url       # evaluation/eval_model.py, evaluation/eval_latency.py
remove_protocol = limpiar_url # colab_lstm/convert_tflite.py
limpiar = limpiar_url          # colab_lstm/test_inference.py
