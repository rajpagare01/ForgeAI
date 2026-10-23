from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import Optional


class Settings(BaseSettings):
    PROJECT_NAME: str = "ForgeAI AI Service"
    VERSION: str = "1.0.0"
    API_V1_STR: str = "/api/v1/ai"
    PORT: int = 8084
    ENVIRONMENT: str = "development"
    ALLOW_TEST_FALLBACK: bool = False

    # Database settings (PostgreSQL 16 with pgvector)
    DATABASE_URL: str = "postgresql://postgres:postgres@localhost:5432/ai_db"
    ASYNC_DATABASE_URL: Optional[str] = None
    DB_POOL_MIN_SIZE: int = 2
    DB_POOL_MAX_SIZE: int = 20

    # Redis settings (Token bucket & rate limiting)
    REDIS_URL: str = "redis://redis:6379/0"

    # Source Control & Testing Service Integration
    SOURCE_CONTROL_SERVICE_URL: str = "http://source-control-service:8082"
    TESTING_SERVICE_URL: str = "http://testing-service:8083"

    # Gemini LLM settings (PRIMARY)
    GEMINI_API_KEY: Optional[str] = None
    GEMINI_MODEL: str = "gemini-3.8-flash"
    GEMINI_EMBEDDING_MODEL: str = "models/gemini-embedding-001"
    EMBEDDING_DIMENSION: int = 768

    # Groq LLM settings (FALLBACK ONLY - NOT FOR EMBEDDINGS)
    GROQ_API_KEY: Optional[str] = None
    GROQ_MODEL: str = "qwen/qwen3.8-27b"
    GROQ_API_URL: str = "https://api.groq.com/openai/v1/chat/completions"

    # Provider Orchestration
    AI_PROVIDER_ORDER: str = "gemini,groq"
    PROVIDER_TIMEOUT_SECONDS: float = 30.0
    PROVIDER_MAX_RETRIES: int = 2

    # Tenant header settings
    ORG_HEADER_NAME: str = "X-Org-Id"
    USER_HEADER_NAME: str = "X-User-Id"
    PERMISSIONS_HEADER_NAME: str = "X-Permissions"

    # Internal attestation settings (ADV2-006)
    ENFORCE_INTERNAL_MARKER: bool = True
    INTERNAL_MARKER_HEADER: str = "X-ForgeAI-Internal"
    INTERNAL_MARKER_EXPECTED: str = "attested"

    # Cross-Repository & Distributed Intelligence Settings
    MAX_CROSS_REPO_DEPTH: int = 2

    def get_async_db_url(self) -> str:
        if self.ASYNC_DATABASE_URL:
            return self.ASYNC_DATABASE_URL
        url = self.DATABASE_URL
        if url.startswith("postgresql://"):
            return url.replace("postgresql://", "postgresql+asyncpg://", 1)
        return url

    model_config = SettingsConfigDict(
        env_file=".env",
        case_sensitive=True,
        extra="ignore",
    )


settings = Settings()

