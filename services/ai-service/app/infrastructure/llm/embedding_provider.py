import hashlib
import logging
from typing import List
import google.generativeai as genai
from app.core.config import settings
from app.infrastructure.llm.provider import (
    EmbeddingProvider,
    EmbeddingProviderException,
)

import sys

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

        if self.api_key and not any(self.api_key.startswith(p) for p in ("mock", "test")):
            try:
                genai.configure(api_key=self.api_key)
                self._configured = True
            except Exception as e:
                logger.warning("Failed to configure Gemini embedding SDK: %s", type(e).__name__)

    async def generate_embedding(self, text: str) -> List[float]:
        # Production requirement: Must use real embedding provider unless test mode is explicitly enabled
        is_production = settings.ENVIRONMENT == "production"
        is_test_mode = (
            not is_production
            and (
                settings.ENVIRONMENT in ("test", "dev", "development")
                or settings.ALLOW_TEST_FALLBACK
                or bool(self.api_key and any(self.api_key.startswith(p) for p in ("mock", "test")))
                or "pytest" in sys.modules
            )
        )

        if "pytest" in sys.modules or not self._configured or not self.api_key:
            if is_test_mode:
                logger.info("[TEST_FALLBACK] Generating deterministic 768-dim pseudo-vector in test/dev environment.")
                return self._generate_deterministic_vector(text)
            raise EmbeddingProviderException(
                "Real embedding provider is unconfigured in production mode (GEMINI_API_KEY required)."
            )

        # Gemini model name may accept either "models/text-embedding-004" or "text-embedding-004"
        candidate_models = [self.embedding_model]
        if self.embedding_model.startswith("models/"):
            candidate_models.append(self.embedding_model.replace("models/", ""))
        else:
            candidate_models.append(f"models/{self.embedding_model}")

        last_err = None
        for candidate in candidate_models:
            try:
                result = genai.embed_content(
                    model=candidate,
                    content=text,
                    task_type="retrieval_document",
                )
                embedding = result.get("embedding", [])
                if not embedding:
                    continue

                # Pad or slice to dimension (768)
                if len(embedding) < self.dimension:
                    embedding = embedding + [0.0] * (self.dimension - len(embedding))
                return embedding[:self.dimension]
            except Exception as e:
                last_err = e
                logger.debug("Failed embedding with candidate model '%s': %s", candidate, type(e).__name__)

        if is_test_mode:
            logger.warning(
                "[TEST_FALLBACK] Gemini embedding call failed (%s); using deterministic vector for offline test.",
                type(last_err).__name__ if last_err else "Unknown",
            )
            return self._generate_deterministic_vector(text)

        logger.error("Real embedding generation failed in production: %s", type(last_err).__name__ if last_err else "Unknown")
        raise EmbeddingProviderException(f"Failed to generate vector embedding: {type(last_err).__name__}") from last_err

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
