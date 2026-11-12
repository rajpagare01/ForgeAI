import asyncio
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

        candidate_models = list(
            dict.fromkeys([self.embedding_model, "models/gemini-embedding-001", "models/text-embedding-004"])
        )

        last_err = None
        for candidate in candidate_models:
            if not candidate:
                continue
            try:
                def _call_embed():
                    try:
                        return genai.embed_content(
                            model=candidate,
                            content=text,
                            task_type="retrieval_document",
                            output_dimensionality=self.dimension,
                        )
                    except TypeError:
                        return genai.embed_content(
                            model=candidate,
                            content=text,
                            task_type="retrieval_document",
                        )

                result = await asyncio.wait_for(
                    asyncio.to_thread(_call_embed),
                    timeout=float(settings.PROVIDER_TIMEOUT_SECONDS),
                )
                embedding = list(result.get("embedding", [])) if isinstance(result, dict) else []
                if not embedding:
                    continue

                # Pad or slice to dimension (768) and L2-normalize
                if len(embedding) < self.dimension:
                    embedding = embedding + [0.0] * (self.dimension - len(embedding))
                embedding = [float(x) for x in embedding[: self.dimension]]
                norm = sum(x * x for x in embedding) ** 0.5
                if norm > 0:
                    embedding = [x / norm for x in embedding]
                return embedding
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
        Generates a 768-dimensional L2-normalized token-aware feature-hashed vector
        (splitting camelCase, PascalCase, and snake_case identifiers + bigrams).
        """
        import re
        raw_words = re.findall(r"[A-Za-z0-9]+", text or "")
        tokens: List[str] = []
        for w in raw_words:
            parts = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", w).lower().split()
            tokens.extend(parts)
            if len(parts) > 1:
                tokens.append(w.lower())
        if not tokens:
            tokens = ["empty"]

        features = list(tokens) + [f"{tokens[i]}_{tokens[i+1]}" for i in range(len(tokens) - 1)]
        vector = [0.0] * self.dimension
        for feat in features:
            digest = hashlib.sha256(feat.encode("utf-8")).digest()
            for j in range(0, 16, 2):
                idx = ((digest[j] << 8) | digest[j + 1]) % self.dimension
                sign = 1.0 if (digest[(j + 2) % 32] & 1) == 0 else -1.0
                vector[idx] += sign

        norm = sum(x * x for x in vector) ** 0.5
        if norm > 0:
            vector = [x / norm for x in vector]
        return vector
