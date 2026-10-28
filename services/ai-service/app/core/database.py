import asyncio
import asyncpg
import logging
from typing import Optional
from app.core.config import settings

logger = logging.getLogger(__name__)


class DatabasePool:
    def __init__(self):
        self._pool: Optional[asyncpg.Pool] = None
        self._init_lock: Optional[asyncio.Lock] = None

    def _get_lock(self) -> asyncio.Lock:
        if self._init_lock is None:
            self._init_lock = asyncio.Lock()
        return self._init_lock

    async def init(self, dsn: Optional[str] = None):
        if self._pool is not None:
            return

        db_url = dsn or settings.DATABASE_URL
        # Ensure raw postgresql:// for asyncpg
        if db_url.startswith("postgresql+asyncpg://"):
            db_url = db_url.replace("postgresql+asyncpg://", "postgresql://", 1)

        import re
        masked_url = re.sub(r"://([^:]+):([^@]+)@", r"://\1:***@", db_url)
        logger.info(f"Connecting to database pool at {masked_url}")
        self._pool = await asyncpg.create_pool(
            dsn=db_url,
            min_size=settings.DB_POOL_MIN_SIZE,
            max_size=settings.DB_POOL_MAX_SIZE,
            command_timeout=60,
        )

    async def get_pool(self, dsn: Optional[str] = None) -> asyncpg.Pool:
        if self._pool is None:
            async with self._get_lock():
                if self._pool is None:
                    await self.init(dsn)
        return self._pool

    async def close(self):
        if self._pool is not None:
            await self._pool.close()
            self._pool = None
            logger.info("Database connection pool closed.")

    @property
    def pool(self) -> asyncpg.Pool:
        if self._pool is None:
            raise RuntimeError("Database pool has not been initialized. Call init() or get_pool() first.")
        return self._pool


db_pool = DatabasePool()


async def get_db_pool() -> asyncpg.Pool:
    return await db_pool.get_pool()
