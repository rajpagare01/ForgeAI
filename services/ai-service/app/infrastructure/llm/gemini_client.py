import logging
from typing import List, Optional
from app.core.config import settings
from app.infrastructure.llm.provider import (
    ProviderResponse,
    LLMProvider,
    EmbeddingProvider,
)
from app.infrastructure.llm.embedding_provider import GeminiEmbeddingProvider
from app.infrastructure.llm.orchestrator import FailoverLLMOrchestrator

logger = logging.getLogger(__name__)


class GeminiClient:
    """
    Unified LLM and Embedding client for ForgeAI AI Service.
    Delegates to:
      - FailoverLLMOrchestrator (Gemini Primary + Groq Fallback)
      - GeminiEmbeddingProvider (Real 768-dim embeddings via text-embedding-004)
    """
    def __init__(
        self,
        llm_orchestrator: Optional[LLMProvider] = None,
        embedding_provider: Optional[EmbeddingProvider] = None,
    ):
        self.orchestrator = llm_orchestrator or FailoverLLMOrchestrator()
        self.embedding_provider = embedding_provider or GeminiEmbeddingProvider()
        self.model_name = settings.GEMINI_MODEL
        self.embedding_model = settings.GEMINI_EMBEDDING_MODEL

    async def generate_embedding(self, text: str) -> List[float]:
        """
        Generates 768-dimensional embedding vector for input text via GeminiEmbeddingProvider.
        """
        return await self.embedding_provider.generate_embedding(text)

    async def generate_text_with_metadata(
        self, prompt: str, system_instruction: Optional[str] = None
    ) -> ProviderResponse:
        """
        Generates text output via FailoverLLMOrchestrator with full provider metadata.
        """
        return await self.orchestrator.generate_text(prompt, system_instruction)

    async def generate_text(
        self, prompt: str, system_instruction: Optional[str] = None
    ) -> str:
        """
        Backwards-compatible helper returning raw text.
        """
        resp = await self.generate_text_with_metadata(prompt, system_instruction)
        return resp.text
