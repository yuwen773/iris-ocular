"""Official source adapters."""

from collections.abc import AsyncIterator
from typing import Protocol

from iris_news_worker.contracts import CollectionWindow, Material


class SourceAdapter(Protocol):
    def pages(self, window: CollectionWindow) -> AsyncIterator[list[Material]]: ...
