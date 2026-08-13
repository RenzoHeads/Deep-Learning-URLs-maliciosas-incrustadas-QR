"""Fake asyncpg Record — indexable dict wrapper imitating ``asyncpg.Record``."""
from __future__ import annotations

from typing import Any


class FakeRecord:
    """Imita un ``asyncpg.Record``: indexable, con ``.get()`` y claves."""

    def __init__(self, data: dict[str, Any]):
        self._data = dict(data)

    def __getitem__(self, key: str) -> Any:
        return self._data[key]

    def get(self, key: str, default: Any = None) -> Any:
        return self._data.get(key, default)

    def keys(self):
        return self._data.keys()

    def items(self):
        return self._data.items()

    def values(self):
        return self._data.values()

    def __iter__(self):
        return iter(self._data)

    def __contains__(self, key: str) -> bool:
        return key in self._data

    def __repr__(self) -> str:  # pragma: no cover - debug only
        return f"FakeRecord({self._data!r})"
