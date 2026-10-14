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
        import os
        migration_path = os.path.join(os.path.dirname(__file__), "..", "migrations", "001_initial_schema.sql")
        if os.path.exists(migration_path):
            try:
                with open(migration_path, "r") as f:
                    schema_sql = f.read()
                async with pool.acquire() as conn:
                    await conn.execute(schema_sql)
                logger.info("Database schema initialized successfully.")
            except Exception as se:
                logger.warning(f"Schema migration skipped or failed: {se}")
        # Initialize background index worker
        pool = db_pool.pool
        chunk_repo = AsyncpgCodeChunkRepository(pool)
        edge_repo = AsyncpgGraphEdgeRepository(pool)
        indexing_service = IndexingService(chunk_repo, edge_repo)
        worker = IndexJobQueue(indexing_service)
        await worker.start_worker()
        ai_router.index_worker = worker
    except Exception as e:
        logger.warning(f"Could not connect to database on startup: {e}. Endpoints will initialize on demand.")

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



@app.get("/health", tags=["Health"])
@app.get("/healthz", tags=["Health"])
@app.get("/api/v1/ai/health", tags=["Health"])
async def health_check():
    return {
        "status": "UP",
        "service": "ai-service",
        "version": settings.VERSION,
    }
