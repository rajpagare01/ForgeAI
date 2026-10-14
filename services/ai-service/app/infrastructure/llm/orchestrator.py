import logging
from typing import Optional
from app.core.config import settings
from app.infrastructure.llm.provider import (
    LLMProvider,
    ProviderResponse,
    ProviderUnavailableException,
    AIUnavailableException,
)
from app.infrastructure.llm.gemini_provider import GeminiProvider
from app.infrastructure.llm.groq_provider import GroqProvider

logger = logging.getLogger(__name__)


class FailoverLLMOrchestrator(LLMProvider):
    """
    Architecture-compliant AI Provider Orchestrator:
    - Primary: Google Gemini (GeminiProvider)
    - Fallback: Groq Cloud (GroqProvider)
    - Enforces honest provider metadata: provider, model, fallback_used.
    - Transparently reports Groq fallback when primary fails.
    - Disallows Groq as an embedding provider.
    - Prevents silent fake responses in production mode.
    """
    def __init__(
        self,
        primary_provider: Optional[LLMProvider] = None,
        fallback_provider: Optional[LLMProvider] = None,
    ):
        self.primary_provider = primary_provider or GeminiProvider()
        self.fallback_provider = fallback_provider or GroqProvider()

    async def generate_text(
        self, prompt: str, system_instruction: Optional[str] = None
    ) -> ProviderResponse:
        is_test_mode = (
            settings.ENVIRONMENT == "test"
            or settings.ALLOW_TEST_FALLBACK
            or settings.GEMINI_API_KEY in ("mock-gemini-key-for-dev", "test-api-key")
        )

        # 1. Attempt Primary Provider: Gemini
        try:
            response = await self.primary_provider.generate_text(
                prompt=prompt,
                system_instruction=system_instruction,
            )
            return response
        except ProviderUnavailableException as gemini_err:
            logger.warning(
                "Gemini primary provider unavailable (%s); initiating failover to Groq fallback.",
                str(gemini_err),
            )

        # 2. Attempt Fallback Provider: Groq
        try:
            response = await self.fallback_provider.generate_text(
                prompt=prompt,
                system_instruction=system_instruction,
            )
            logger.info(
                "Groq fallback provider succeeded for prompt (fallback_used=True)."
            )
            return response
        except ProviderUnavailableException as groq_err:
            logger.warning(
                "Groq fallback provider also unavailable (%s).",
                str(groq_err),
            )

        # 3. Both Providers Unavailable
        if is_test_mode:
            logger.info(
                "[TEST_FALLBACK] Providing explicit development test response because all providers are unavailable in test/dev mode."
            )
            return ProviderResponse(
                text=(
                    "### Impact Analysis & Architectural Summary (Test Fallback)\n"
                    "- Provider Status: Gemini and Groq offline; test fallback engaged.\n"
                    "- Blast Radius: Graph evaluation completed via AST recursive CTE.\n"
                    "- Note: This is an observable TEST response and must not be used in production."
                ),
                provider="TEST_FALLBACK",
                model="test-fallback",
                fallback_used=True,
                latency_ms=0,
            )

        logger.error(
            "AI generation failed: All configured providers (Gemini primary, Groq fallback) are unavailable in production."
        )
        raise AIUnavailableException(
            "All configured AI providers (Gemini primary, Groq fallback) are currently unavailable."
        )
