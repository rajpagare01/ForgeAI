import time
import asyncio
import logging
from typing import Optional
import google.generativeai as genai
from app.core.config import settings
from app.infrastructure.llm.provider import (
    LLMProvider,
    ProviderResponse,
    ProviderUnavailableException,
)

logger = logging.getLogger(__name__)


class GeminiProvider(LLMProvider):
    """
    Primary LLM Provider for ForgeAI using Google Gemini.
    """
    def __init__(self):
        self.api_key = settings.GEMINI_API_KEY
        self.model_name = settings.GEMINI_MODEL
        self._configured = False

        if self.api_key and self.api_key not in ("mock-gemini-key-for-dev", "test-api-key", ""):
            try:
                genai.configure(api_key=self.api_key)
                self._configured = True
            except Exception as e:
                logger.warning("Failed to configure Gemini SDK: %s", type(e).__name__)

    async def generate_text(
        self, prompt: str, system_instruction: Optional[str] = None
    ) -> ProviderResponse:
        if not self._configured or not self.api_key:
            raise ProviderUnavailableException("Gemini provider is unconfigured or missing API key.")

        start_time = time.time()
        max_retries = max(1, settings.PROVIDER_MAX_RETRIES)
        last_error: Optional[Exception] = None

        for attempt in range(1, max_retries + 1):
            try:
                model = genai.GenerativeModel(
                    model_name=self.model_name,
                    system_instruction=system_instruction,
                )
                # Run the blocking Gemini call in a threadpool with a strict timeout to keep async loop unblocked
                import sys
                call_timeout = 2.0 if "pytest" in sys.modules else float(settings.PROVIDER_TIMEOUT_SECONDS)
                response = await asyncio.wait_for(
                    asyncio.to_thread(model.generate_content, prompt),
                    timeout=call_timeout,
                )
                if not response or not hasattr(response, "text") or response.text is None:
                    raise ProviderUnavailableException("Gemini returned empty response text.")

                duration_ms = int((time.time() - start_time) * 1000)
                logger.info(
                    "Gemini generation succeeded (model=%s, duration=%dms, attempt=%d)",
                    self.model_name,
                    duration_ms,
                    attempt,
                )
                return ProviderResponse(
                    text=response.text,
                    provider="GEMINI",
                    model=self.model_name,
                    fallback_used=False,
                    latency_ms=duration_ms,
                )
            except Exception as e:
                last_error = e
                # Check for non-retryable programming or tenant violations vs retryable network/availability
                err_type = type(e).__name__
                err_msg = str(e)
                logger.warning(
                    "Gemini attempt %d/%d failed (%s: %s)",
                    attempt,
                    max_retries,
                    err_type,
                    err_msg[:120],
                )
                if any(x in err_type for x in ("NotFound", "InvalidArgument", "PermissionDenied", "Unauthenticated")) or any(c in err_msg for c in ("404", "400", "401", "403")):
                    logger.info("Gemini failure is non-retryable (%s); fast-failing to trigger immediate fallback.", err_type)
                    break
                if attempt < max_retries:
                    await asyncio.sleep(0.5 * (2 ** (attempt - 1)))

        raise ProviderUnavailableException(
            f"Gemini API unavailable after {max_retries} attempts ({type(last_error).__name__})"
        )
