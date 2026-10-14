from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    PROJECT_NAME: str = "ForgeAI AI Service"
    VERSION: str = "1.0.0"
    API_V1_STR: str = "/api/v1/ai"
    PORT: int = 8084

    # Database settings (PostgreSQL 16 with pgvector)
    DATABASE_URL: str = "postgresql://postgres:postgres@localhost:5432/ai_db"
    ASYNC_DATABASE_URL: Optional[str] = None
    DB_POOL_MIN_SIZE: int = 2
    DB_POOL_MAX_SIZE: int = 20

    # Redis settings (Token bucket & rate limiting)
    REDIS_URL: str = "redis://redis:6379/0"

    # Gemini LLM settings
    GEMINI_API_KEY: Optional[str] = "test-api-key"
    GEMINI_MODEL: str = "gemini-1.5-pro"
    GEMINI_EMBEDDING_MODEL: str = "models/text-embedding-004"
    EMBEDDING_DIMENSION: int = 768

    # Tenant header settings
    ORG_HEADER_NAME: str = "X-Org-Id"
    USER_HEADER_NAME: str = "X-User-Id"
    PERMISSIONS_HEADER_NAME: str = "X-Permissions"

    def get_async_db_url(self) -> str:
        if self.ASYNC_DATABASE_URL:
            return self.ASYNC_DATABASE_URL
        url = self.DATABASE_URL
        if url.startswith("postgresql://"):
            return url.replace("postgresql://", "postgresql+asyncpg://", 1)
        return url

    class Config:
        env_file = ".env"
        case_sensitive = True


settings = Settings()
