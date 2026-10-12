import logging
import hashlib
from typing import List, Optional
import google.generativeai as genai
from app.core.config import settings

logger = logging.getLogger(__name__)


class GeminiClient:
    def __init__(self):
        if settings.GEMINI_API_KEY and settings.GEMINI_API_KEY != "test-api-key":
            genai.configure(api_key=settings.GEMINI_API_KEY)
        self.model_name = settings.GEMINI_MODEL
        self.embedding_model = settings.GEMINI_EMBEDDING_MODEL

    async def generate_embedding(self, text: str) -> List[float]:
        """
        Generates 768-dimensional embedding vector for input text.
        Falls back to a deterministic 768-dim normalized pseudo-vector in offline/test mode.
        """
        if not settings.GEMINI_API_KEY or settings.GEMINI_API_KEY == "test-api-key":
            return self._generate_deterministic_vector(text)

        try:
            result = genai.embed_content(
                model=self.embedding_model,
                content=text,
                task_type="retrieval_document",
            )
            embedding = result.get("embedding", [])
            # Pad or slice to 768 dimensions
            if len(embedding) < 768:
                embedding = embedding + [0.0] * (768 - len(embedding))
            return embedding[:768]
        except Exception as e:
            logger.warning(f"Gemini API embed call failed ({e}); falling back to deterministic vector.")
            return self._generate_deterministic_vector(text)

    async def generate_text(self, prompt: str, system_instruction: Optional[str] = None) -> str:
        """
        Generates text output via Gemini.
        """
        if not settings.GEMINI_API_KEY or settings.GEMINI_API_KEY == "test-api-key":
            return (
                "### Impact Analysis & Architectural Summary (Simulated)\n"
                "- High-level impact: Modifications detected in core boundary classes.\n"
                "- Risk Assessment: LOW risk to existing domain invariants.\n"
                "- Recommendations: Ensure end-to-end regression tests are verified."
            )

        try:
            model = genai.GenerativeModel(
                model_name=self.model_name,
                system_instruction=system_instruction,
            )
            response = model.generate_content(prompt)
            return response.text
        except Exception as e:
            logger.error(f"Error calling Gemini generate_content: {e}")
            raise

    def _generate_deterministic_vector(self, text: str) -> List[float]:
        """
        Generates a deterministic 768-dimensional float vector based on sha256 of text
        for offline testing and development.
        """
        h = hashlib.sha256(text.encode("utf-8")).digest()
        vector = []
        for i in range(768):
            byte_val = h[i % len(h)]
            # normalize between -1.0 and 1.0
            vector.append((float(byte_val) / 128.0) - 1.0)
        # normalize Euclidean length to 1.0
        norm = sum(x * x for x in vector) ** 0.5
        if norm > 0:
            vector = [x / norm for x in vector]
        return vector
