import time
import uuid
import logging
from datetime import datetime, timezone
from typing import Optional, Tuple
from uuid import UUID
import redis.asyncio as aioredis
from app.core.config import settings

logger = logging.getLogger(__name__)


class RedisAiRateLimiter:
    """
    Redis-backed rate limiter & token meter for AI service:
    1. Sliding-window log per minute (ZADD, ZREMRANGEBYSCORE, ZCARD)
    2. Daily token quota meter per 24 hours per organization (llm:quota:{org_id})
    Fail-safe: In-memory fallback if Redis is unavailable.
    """

    def __init__(self, redis_url: Optional[str] = None):
        self.redis_url = redis_url or settings.REDIS_URL
        self._redis: Optional[aioredis.Redis] = None
        self._local_sliding_window: dict[str, list[float]] = {}
        self._local_daily_tokens: dict[str, int] = {}
        self._local_daily_dates: dict[str, str] = {}
        self._local_window_reset: dict[str, float] = {}

    async def get_redis(self) -> aioredis.Redis:
        if self._redis is None:
            self._redis = aioredis.from_url(
                self.redis_url,
                encoding="utf-8",
                decode_responses=True,
                socket_timeout=2.0,
            )
        return self._redis

    async def close(self):
        if self._redis:
            await self._redis.close()
            self._redis = None

    async def check_and_consume_quota(
        self,
        org_id: UUID,
        estimated_tokens: int = 500,
        max_requests_per_minute: int = 60,
        daily_token_limit: int = 100_000,
    ) -> Tuple[bool, Optional[int]]:
        """
        Validates rate limit and daily quota.
        Returns:
            (allowed: bool, retry_after_seconds: Optional[int])
        """
        now = time.time()
        org_key = str(org_id)
        rate_key = f"llm:ratelimit:{org_key}"
        quota_key = f"llm:quota:{org_key}"

        # Try Redis first
        try:
            r = await self.get_redis()
            one_minute_ago = now - 60.0

            # 1. Check sliding window cardinality & daily quota BEFORE admitting request
            pipe = r.pipeline()
            pipe.zremrangebyscore(rate_key, 0, one_minute_ago)
            pipe.zcard(rate_key)
            pipe.get(quota_key)
            results = await pipe.execute()

            current_requests = results[1]
            if current_requests >= max_requests_per_minute:
                retry_after = 60 - int(now % 60)
                logger.warning(f"Org {org_id} exceeded RPM limit ({current_requests}/{max_requests_per_minute})")
                return False, max(1, retry_after)

            # Check daily quota
            daily_used_str = results[-1]
            daily_used = int(daily_used_str) if daily_used_str else 0

            if daily_used + estimated_tokens > daily_token_limit:
                retry_after = 86400 - int(now % 86400)
                logger.warning(f"Org {org_id} exceeded daily token limit ({daily_used}/{daily_token_limit})")
                return False, max(60, retry_after)

            # Record request + increment daily quota atomically
            pipe2 = r.pipeline()
            pipe2.zadd(rate_key, {f"{now}:{uuid.uuid4().hex[:8]}": now})
            pipe2.expire(rate_key, 120)
            pipe2.incrby(quota_key, estimated_tokens)
            pipe2.expire(quota_key, 86400)
            await pipe2.execute()

            return True, None

        except Exception as e:
            # Fail-safe: Fallback to local in-memory sliding window
            logger.warning(f"Redis unavailable ({e}), falling back to in-memory rate limiter")
            return self._local_fallback_check(org_key, estimated_tokens, max_requests_per_minute, daily_token_limit, now)

    def _local_fallback_check(
        self,
        org_key: str,
        estimated_tokens: int,
        max_rpm: int,
        daily_limit: int,
        now: float,
    ) -> Tuple[bool, Optional[int]]:
        one_min_ago = now - 60.0

        # Filter local timestamps
        timestamps = self._local_sliding_window.get(org_key, [])
        valid_timestamps = [t for t in timestamps if t > one_min_ago]

        if len(valid_timestamps) >= max_rpm:
            return False, 60

        # Daily token counter (reset automatically on UTC date rollover)
        today_utc = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        if self._local_daily_dates.get(org_key) != today_utc:
            self._local_daily_dates[org_key] = today_utc
            self._local_daily_tokens[org_key] = 0

        used = self._local_daily_tokens.get(org_key, 0)
        if used + estimated_tokens > daily_limit:
            return False, 3600

        valid_timestamps.append(now)
        self._local_sliding_window[org_key] = valid_timestamps
        self._local_daily_tokens[org_key] = used + estimated_tokens
        return True, None
