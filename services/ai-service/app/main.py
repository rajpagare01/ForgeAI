from contextlib import asynccontextmanager
import logging
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from app.core.config import settings
from app.core.logging import setup_logging
from app.core.database import db_pool
from app.infrastructure.persistence.repositories import (
    AsyncpgCodeChunkRepository,
    AsyncpgGraphEdgeRepository,
)
from app.application.indexing_service import IndexingService
from app.workers.index_worker import IndexJobQueue
from app.presentation import router as ai_router

setup_logging()
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Initialize database connection pool
    logger.info("Initializing AI Service database pool...")
    try:
        await db_pool.init()
        pool = db_pool.pool
        from app.core.migration_runner import run_migrations
        await run_migrations(pool)

        # Initialize background index worker with durable job repository
        from app.infrastructure.persistence.repositories import AsyncpgIndexJobRepository
        chunk_repo = AsyncpgCodeChunkRepository(pool)
        edge_repo = AsyncpgGraphEdgeRepository(pool)
        job_repo = AsyncpgIndexJobRepository(pool)
        indexing_service = IndexingService(chunk_repo, edge_repo)
        worker = IndexJobQueue(indexing_service, job_repo=job_repo)
        await worker.start_worker()
        ai_router.index_worker = worker
    except Exception as e:
        logger.error(f"Startup initialization failed: {e}", exc_info=True)
        raise RuntimeError(f"AI Service startup failed: {e}") from e

    yield

    # Teardown
    if ai_router.index_worker:
        await ai_router.index_worker.stop_worker()
    await db_pool.close()
    logger.info("AI Service shutdown complete.")


from app.core.trace_middleware import TraceContextMiddleware

app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    lifespan=lifespan,
)

# Register W3C trace context middleware
app.add_middleware(TraceContextMiddleware)

# Include API router
app.include_router(ai_router.router)



from fastapi.responses import PlainTextResponse
from app.core.metrics import ai_metrics

@app.get("/health", tags=["Health"])
@app.get("/healthz", tags=["Health"])
@app.get("/api/v1/ai/health", tags=["Health"])
async def health_check():
    db_status = "UP"
    if db_pool._pool is not None:
        try:
            async with db_pool._pool.acquire() as conn:
                res = await conn.fetchval("SELECT 1")
                if res != 1:
                    db_status = "DOWN"
        except Exception as e:
            logger.warning(f"Database health check failed: {e}")
            db_status = "DOWN"

    has_gemini = bool(settings.GEMINI_API_KEY)
    has_groq = bool(settings.GROQ_API_KEY)

    provider_status = "UP" if has_gemini else ("DEGRADED" if has_groq else "UNCONFIGURED")
    overall_status = "UP" if db_status == "UP" else "DOWN"

    return {
        "status": overall_status,
        "service": "ai-service",
        "version": settings.VERSION,
        "dependencies": {
            "database": {"status": db_status},
            "gemini": {"status": "CONFIGURED" if has_gemini else "UNCONFIGURED"},
            "groq": {"status": "CONFIGURED" if has_groq else "UNCONFIGURED"},
            "provider": {"status": provider_status}
        }
    }

@app.get("/metrics", response_class=PlainTextResponse, tags=["Observability"])
async def get_metrics():
    return ai_metrics.to_prometheus_format()

@app.get("/api/v1/ai/metrics", tags=["Observability"])
async def get_metrics_json():
    return ai_metrics.get_stats()

