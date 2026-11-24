"""Sanitized error boundary for reports and CLI output."""

from iris_news_worker.contracts import FailureCode


class WorkerError(Exception):
    def __init__(self, code: FailureCode, *, retryable: bool = False):
        super().__init__(code.value)
        self.code = code
        self.retryable = retryable
