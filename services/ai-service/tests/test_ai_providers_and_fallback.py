import pytest
from unittest.mock import AsyncMock, patch, MagicMock
from app.infrastructure.llm.provider import (
    ProviderResponse,
    ProviderUnavailableException,
    AIUnavailableException,
    EmbeddingProviderException,
)
from app.infrastructure.llm.gemini_provider import GeminiProvider
from app.infrastructure.llm.groq_provider import GroqProvider
from app.infrastructure.llm.embedding_provider import GeminiEmbeddingProvider
from app.infrastructure.llm.orchestrator import FailoverLLMOrchestrator
from app.core.config import settings


@pytest.mark.asyncio
async def test_gemini_provider_success():
    """Verifies that Gemini provider successfully returns ProviderResponse with GEMINI metadata."""
    provider = GeminiProvider()
    provider._configured = True
    provider.api_key = "valid-test-key"

    mock_response = MagicMock()
    mock_response.text = "Architectural review: Clean boundaries observed."

    with patch("asyncio.to_thread", new_callable=AsyncMock) as mock_thread:
        mock_thread.return_value = mock_response
        result = await provider.generate_text("Audit this commit", "System instruction")
        assert result.provider == "GEMINI"
        assert result.fallback_used is False
        assert "Clean boundaries" in result.text
        assert result.model == settings.GEMINI_MODEL


@pytest.mark.asyncio
async def test_gemini_provider_unconfigured_raises():
    """Verifies that Gemini provider raises ProviderUnavailableException when unconfigured."""
    provider = GeminiProvider()
    provider._configured = False
    provider.api_key = None

    with pytest.raises(ProviderUnavailableException):
        await provider.generate_text("Audit this commit")


@pytest.mark.asyncio
async def test_groq_provider_success():
    """Verifies that Groq fallback provider returns ProviderResponse with GROQ metadata and fallback_used=True."""
    provider = GroqProvider()
    provider.api_key = "gsk_valid_test_key"

    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "choices": [
            {"message": {"content": "Groq Fallback: Risk evaluation is LOW."}}
        ]
    }

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        mock_post.return_value = mock_resp
        result = await provider.generate_text("Audit PR", "System instruction")
        assert result.provider == "GROQ"
        assert result.fallback_used is True
        assert "Groq Fallback" in result.text
        assert result.model == settings.GROQ_MODEL


@pytest.mark.asyncio
async def test_orchestrator_gemini_primary_success():
    """Verifies orchestrator selects Gemini primary when available and does not invoke Groq."""
    primary = MagicMock()
    primary.generate_text = AsyncMock(
        return_value=ProviderResponse(
            text="Gemini Primary Output",
            provider="GEMINI",
            model="gemini-1.5-pro",
            fallback_used=False,
            latency_ms=120,
        )
    )
    fallback = MagicMock()
    fallback.generate_text = AsyncMock()

    orchestrator = FailoverLLMOrchestrator(primary_provider=primary, fallback_provider=fallback)
    result = await orchestrator.generate_text("Test prompt")

    assert result.provider == "GEMINI"
    assert result.fallback_used is False
    assert result.text == "Gemini Primary Output"
    primary.generate_text.assert_awaited_once()
    fallback.generate_text.assert_not_awaited()


@pytest.mark.asyncio
async def test_orchestrator_gemini_fails_failover_to_groq():
    """Verifies that when Gemini primary raises ProviderUnavailableException, orchestrator transparently fails over to Groq."""
    primary = MagicMock()
    primary.generate_text = AsyncMock(
        side_effect=ProviderUnavailableException("Gemini quota exceeded 429")
    )
    fallback = MagicMock()
    fallback.generate_text = AsyncMock(
        return_value=ProviderResponse(
            text="Groq Fallback Output",
            provider="GROQ",
            model="llama-3.3-70b-versatile",
            fallback_used=True,
            latency_ms=85,
        )
    )

    orchestrator = FailoverLLMOrchestrator(primary_provider=primary, fallback_provider=fallback)
    result = await orchestrator.generate_text("Test prompt")

    assert result.provider == "GROQ"
    assert result.fallback_used is True
    assert result.text == "Groq Fallback Output"
    primary.generate_text.assert_awaited_once()
    fallback.generate_text.assert_awaited_once()


@pytest.mark.asyncio
async def test_orchestrator_both_providers_fail_in_production():
    """Verifies that in production mode, if both Gemini and Groq fail, AIUnavailableException is raised."""
    primary = MagicMock()
    primary.generate_text = AsyncMock(
        side_effect=ProviderUnavailableException("Gemini network timeout")
    )
    fallback = MagicMock()
    fallback.generate_text = AsyncMock(
        side_effect=ProviderUnavailableException("Groq rate limited 429")
    )

    orchestrator = FailoverLLMOrchestrator(primary_provider=primary, fallback_provider=fallback)

    with patch.object(settings, "ENVIRONMENT", "production"), patch.object(settings, "ALLOW_TEST_FALLBACK", False), patch.object(settings, "GEMINI_API_KEY", "real-key"):
        with pytest.raises(AIUnavailableException):
            await orchestrator.generate_text("Test prompt")


@pytest.mark.asyncio
async def test_embedding_provider_no_silent_fallback_in_production():
    """Verifies that in production mode, unconfigured embeddings raise EmbeddingProviderException rather than silent pseudo-vectors."""
    provider = GeminiEmbeddingProvider()
    provider._configured = False
    provider.api_key = None

    with patch.object(settings, "ENVIRONMENT", "production"), patch.object(settings, "ALLOW_TEST_FALLBACK", False), patch.object(settings, "GEMINI_API_KEY", None):
        with pytest.raises(EmbeddingProviderException):
            await provider.generate_embedding("Code snippet to index")


@pytest.mark.asyncio
async def test_embedding_provider_test_mode_generates_observable_vector():
    """Verifies that in test mode, deterministic pseudo-vector is generated for unit testing."""
    provider = GeminiEmbeddingProvider()
    provider._configured = False
    provider.api_key = "test-api-key"

    with patch.object(settings, "ENVIRONMENT", "test"):
        vec = await provider.generate_embedding("function calculateTotal()")
        assert len(vec) == 768
        assert isinstance(vec[0], float)
        # Verify idempotency of deterministic vector
        vec2 = await provider.generate_embedding("function calculateTotal()")
        assert vec == vec2
