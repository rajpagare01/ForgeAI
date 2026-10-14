from abc import ABC, abstractmethod
from typing import Optional, List
from pydantic import BaseModel


class ProviderResponse(BaseModel):
    text: str
    provider: str  # "GEMINI" | "GROQ" | "TEST_FALLBACK"
    model: str
    fallback_used: bool = False
    latency_ms: int = 0


class ProviderUnavailableException(Exception):
    """Raised when an AI provider is unreachable, times out, or fails on retryable error."""
    pass


class AIUnavailableException(Exception):
    """Raised when all configured AI providers (Primary and Fallback) have failed."""
    pass


class EmbeddingProviderException(Exception):
    """Raised when the real embedding provider fails or is unconfigured in production."""
    pass


class LLMProvider(ABC):
    @abstractmethod
    async def generate_text(
        self, prompt: str, system_instruction: Optional[str] = None
    ) -> ProviderResponse:
        pass


class EmbeddingProvider(ABC):
    @abstractmethod
    async def generate_embedding(self, text: str) -> List[float]:
        pass
