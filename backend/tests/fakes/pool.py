"""Fake asyncpg Pool — ``acquire()`` es async context manager."""
from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncIterator

from tests.fakes.connection import FakeConnection


class FakePool:
    """Pool asyncpg falso: ``acquire()`` es async context manager."""

    def __init__(self, store: dict[str, list[dict]]):
        self._store = store
        self._conn = FakeConnection(store)

    @asynccontextmanager
    async def acquire(self) -> AsyncIterator[FakeConnection]:
        yield self._conn
