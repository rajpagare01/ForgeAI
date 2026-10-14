import hashlib
import logging
from typing import List
import google.generativeai as genai
from app.core.config import settings
from app.infrastructure.llm.provider import (
    EmbeddingProvider,
    EmbeddingProviderException,
)

logger = logging.getLogger(__name__)


class GeminiEmbeddingProvider(EmbeddingProvider):
    """
    Real Embedding Provider using Google Gemini text-embedding-004 (768 dimensions).
    Does NOT use Groq (Groq is text-generation only).
    Does NOT silently substitute pseudo-vectors in production.
    """
    def __init__(self):
        self.api_key = settings.GEMINI_API_KEY
        self.embedding_model = settings.GEMINI_EMBEDDING_MODEL
        self.dimension = settings.EMBEDDING_DIMENSION
        self._configured = False

        if self.api_key and self.api_key not in ("mock-gemini-key-for-dev", "test-api-key", ""):
            try:
                genai.configure(api_key=self.api_key)
                self._configured = True
            except Exception as e:
                logger.warning("Failed to configure Gemini embedding SDK: %s", type(e).__name__)

    async def generate_embedding(self, text: str) -> List[float]:
        # Production requirement: Must use real embedding provider unless test mode is explicitly enabled
        is_test_mode = (
            settings.ENVIRONMENT == "test"
            or settings.ALLOW_TEST_FALLBACK
            or settings.GEMINI_API_KEY in ("mock-gemini-key-for-dev", "test-api-key")
        )

        if not self._configured or not self.api_key:
            if is_test_mode:
                logger.info("[TEST_FALLBACK] Generating deterministic 768-dim pseudo-vector in test/dev environment.")
                return self._generate_deterministic_vector(text)
            raise EmbeddingProviderException(
                "Real embedding provider is unconfigured in production mode (GEMINI_API_KEY required)."
            )

        try:
            result = genai.embed_content(
                model=self.embedding_model,
                content=text,
                task_type="retrieval_document",
            )
            embedding = result.get("embedding", [])
            if not embedding:
                raise EmbeddingProviderException("Gemini returned empty embedding vector.")

            # Pad or slice to dimension (768)
            if len(embedding) < self.dimension:
                embedding = embedding + [0.0] * (self.dimension - len(embedding))
            return embedding[:self.dimension]

        except Exception as e:
            if is_test_mode:
                logger.warning(
                    "[TEST_FALLBACK] Gemini embedding call failed (%s); using deterministic vector for offline test.",
                    type(e).__name__,
                )
                return self._generate_deterministic_vector(text)
            logger.error("Real embedding generation failed in production: %s", type(e).__name__)
            raise EmbeddingProviderException(f"Failed to generate vector embedding: {type(e).__name__}") from e

    def _generate_deterministic_vector(self, text: str) -> List[float]:
        """
        Generates a deterministic 768-dimensional float vector based on sha256 of text
        strictly for offline unit/integration testing and explicit development fallback.
        """
        h = hashlib.sha256(text.encode("utf-8")).digest()
        vector = []
        for i in range(self.dimension):
            byte_val = h[i % len(h)]
            vector.append((float(byte_val) / 128.0) - 1.0)
        norm = sum(x * x for x in vector) ** 0.5
        if norm > 0:
            vector = [x / norm for x in vector]
        return vector
