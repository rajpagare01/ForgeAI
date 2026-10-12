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
        # Initialize background index worker
        pool = db_pool.pool
        chunk_repo = AsyncpgCodeChunkRepository(pool)
        edge_repo = AsyncpgGraphEdgeRepository(pool)
        indexing_service = IndexingService(chunk_repo, edge_repo)
        worker = IndexJobQueue(indexing_service)
        await worker.start_worker()
        ai_router.index_worker = worker
    except Exception as e:
        logger.warning(f"Could not connect to database on startup: {e}. Tests can initialize manually.")

    yield

    # Teardown
    if ai_router.index_worker:
        await ai_router.index_worker.stop_worker()
    await db_pool.close()
    logger.info("AI Service shutdown complete.")


app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    lifespan=lifespan,
)

# Include API router
app.include_router(ai_router.router)


@app.get("/health", tags=["Health"])
async def health_check():
    return {
        "status": "UP",
        "service": "ai-service",
        "version": settings.VERSION,
    }
