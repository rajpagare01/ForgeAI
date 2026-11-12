import uuid
import pytest
from unittest.mock import AsyncMock, patch, MagicMock
from fastapi import HTTPException
from app.infrastructure.resilience.redis_rate_limiter import RedisAiRateLimiter
from app.presentation.dependencies import check_ai_quota


@pytest.mark.asyncio
async def test_in_memory_fallback_rpm_limit():
    limiter = RedisAiRateLimiter(redis_url="redis://invalid-host:6379/0")
    org_id = uuid.uuid4()

    # Max 3 requests per minute
    allowed1, retry1 = await limiter.check_and_consume_quota(
        org_id=org_id, estimated_tokens=10, max_requests_per_minute=3, daily_token_limit=1000
    )
    assert allowed1 is True
    assert retry1 is None

    allowed2, retry2 = await limiter.check_and_consume_quota(
        org_id=org_id, estimated_tokens=10, max_requests_per_minute=3, daily_token_limit=1000
    )
    assert allowed2 is True

    allowed3, retry3 = await limiter.check_and_consume_quota(
        org_id=org_id, estimated_tokens=10, max_requests_per_minute=3, daily_token_limit=1000
    )
    assert allowed3 is True

    # 4th request must be rejected
    allowed4, retry4 = await limiter.check_and_consume_quota(
        org_id=org_id, estimated_tokens=10, max_requests_per_minute=3, daily_token_limit=1000
    )
    assert allowed4 is False
    assert retry4 is not None
    assert retry4 > 0


@pytest.mark.asyncio
async def test_in_memory_fallback_daily_token_limit():
    limiter = RedisAiRateLimiter(redis_url="redis://invalid-host:6379/0")
    org_id = uuid.uuid4()

    # First request: 600 tokens out of 1000 limit -> Allowed
    allowed1, _ = await limiter.check_and_consume_quota(
        org_id=org_id, estimated_tokens=600, max_requests_per_minute=10, daily_token_limit=1000
    )
    assert allowed1 is True

    # Second request: 500 tokens -> 600 + 500 = 1100 > 1000 -> Exceeded
    allowed2, retry2 = await limiter.check_and_consume_quota(
        org_id=org_id, estimated_tokens=500, max_requests_per_minute=10, daily_token_limit=1000
    )
    assert allowed2 is False
    assert retry2 is not None
    assert retry2 > 0


@pytest.mark.asyncio
async def test_redis_quota_exceeded():
    limiter = RedisAiRateLimiter(redis_url="redis://localhost:6379/0")
    org_id = uuid.uuid4()

    mock_redis = MagicMock()
    mock_pipeline = MagicMock()
    mock_redis.pipeline.return_value = mock_pipeline

    # Pipeline 1: [zremrangebyscore, zcard, get_daily]
    # Simulate 10 requests out of 60 limit, but daily quota used is 99,500 / 100,000
    mock_pipeline.execute = AsyncMock(return_value=[0, 10, "99500"])
    limiter.get_redis = AsyncMock(return_value=mock_redis)

    # Request with 1000 tokens exceeds 100,000 daily limit (99,500 + 1,000 = 100,500)
    allowed, retry_after = await limiter.check_and_consume_quota(
        org_id=org_id, estimated_tokens=1000, max_requests_per_minute=60, daily_token_limit=100000
    )

    assert allowed is False
    assert retry_after is not None
    assert retry_after > 0


@pytest.mark.asyncio
async def test_check_ai_quota_fastapi_dependency_raises_429():
    org_id = uuid.uuid4()

    with patch("app.presentation.dependencies.ai_rate_limiter.check_and_consume_quota", new_callable=AsyncMock) as mock_check:
        mock_check.return_value = (False, 120)

        with pytest.raises(HTTPException) as exc_info:
            await check_ai_quota(org_id=org_id)

        assert exc_info.value.status_code == 429
        assert "quota or rate limit exceeded" in exc_info.value.detail.lower()
        assert exc_info.value.headers == {"Retry-After": "120"}
