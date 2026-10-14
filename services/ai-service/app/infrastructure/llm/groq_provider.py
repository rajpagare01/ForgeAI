import time
import asyncio
import logging
from typing import Optional
import httpx
from app.core.config import settings
from app.infrastructure.llm.provider import (
    LLMProvider,
    ProviderResponse,
    ProviderUnavailableException,
)

logger = logging.getLogger(__name__)


class GroqProvider(LLMProvider):
    """
    Fallback LLM Provider for ForgeAI using Groq Cloud API.
    Used ONLY when Gemini primary is unavailable or fails on retryable error.
    NOT an embedding provider.
    """
    def __init__(self):
        self.api_key = settings.GROQ_API_KEY
        self.model_name = settings.GROQ_MODEL
        self.api_url = settings.GROQ_API_URL
        self.timeout = settings.PROVIDER_TIMEOUT_SECONDS

    async def generate_text(
        self, prompt: str, system_instruction: Optional[str] = None
    ) -> ProviderResponse:
        if not self.api_key or self.api_key in ("mock-groq-key-for-dev", "test-groq-key", ""):
            raise ProviderUnavailableException("Groq fallback provider is unconfigured or missing API key.")

        start_time = time.time()
        max_retries = max(1, settings.PROVIDER_MAX_RETRIES)
        last_error: Optional[Exception] = None

        messages = []
        if system_instruction:
            messages.append({"role": "system", "content": system_instruction})
        messages.append({"role": "user", "content": prompt})

        payload = {
            "model": self.model_name,
            "messages": messages,
            "temperature": 0.2,
        }

        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            for attempt in range(1, max_retries + 1):
                try:
                    response = await client.post(
                        self.api_url,
                        json=payload,
                        headers=headers,
                    )

                    if response.status_code == 200:
                        data = response.json()
                        choices = data.get("choices", [])
                        if not choices:
                            raise ProviderUnavailableException("Groq returned 200 with empty choices.")
                        content = choices[0].get("message", {}).get("content", "")
                        duration_ms = int((time.time() - start_time) * 1000)
                        logger.info(
                            "Groq fallback generation succeeded (model=%s, duration=%dms, attempt=%d)",
                            self.model_name,
                            duration_ms,
                            attempt,
                        )
                        return ProviderResponse(
                            text=content,
                            provider="GROQ",
                            model=self.model_name,
                            fallback_used=True,
                            latency_ms=duration_ms,
                        )

                    # Handle retryable HTTP status codes
                    if response.status_code in (429, 500, 502, 503, 504):
                        logger.warning(
                            "Groq returned retryable status %d on attempt %d/%d",
                            response.status_code,
                            attempt,
                            max_retries,
                        )
                        if attempt < max_retries:
                            await asyncio.sleep(0.5 * (2 ** (attempt - 1)))
                            continue
                        raise ProviderUnavailableException(f"Groq returned HTTP {response.status_code}")

                    # Non-retryable client error (e.g. 401 Unauthorized, 400 Bad Request)
                    logger.error(
                        "Groq returned non-retryable status %d: %s",
                        response.status_code,
                        response.text[:200],
                    )
                    raise ProviderUnavailableException(f"Groq API error HTTP {response.status_code}")

                except httpx.RequestError as e:
                    last_error = e
                    logger.warning(
                        "Groq network error on attempt %d/%d (%s)",
                        attempt,
                        max_retries,
                        type(e).__name__,
                    )
                    if attempt < max_retries:
                        await asyncio.sleep(0.5 * (2 ** (attempt - 1)))

        raise ProviderUnavailableException(
            f"Groq fallback unavailable after {max_retries} attempts ({type(last_error).__name__ if last_error else 'Timeout'})"
        )
