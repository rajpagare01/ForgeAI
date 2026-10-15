import os
import logging
import asyncpg

logger = logging.getLogger(__name__)


class MigrationException(Exception):
    pass


async def run_migrations(pool: asyncpg.Pool) -> None:
    """
    Idempotent, ordered, and tracked database migration runner for ForgeAI AI Service.
    Guarantees:
      - Creates 'schema_migrations' tracking table if not present.
      - Executes migrations in alphanumeric sorted order.
      - Records each applied migration within a transaction.
      - Skips already-applied migrations on restarts.
      - Fails fast (raises MigrationException) if any migration fails.
    """
    migrations_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "migrations"))
    if not os.path.exists(migrations_dir):
        logger.warning("Migrations directory not found at: %s", migrations_dir)
        return

    async with pool.acquire() as conn:
        # 1. Ensure tracking table exists
        await conn.execute("""
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version VARCHAR(128) PRIMARY KEY,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
        """)

        # 2. Get set of already applied migrations
        rows = await conn.fetch("SELECT version FROM schema_migrations;")
        applied_versions = {r["version"] for r in rows}

        # 3. Read migration files in sorted order
        migration_files = sorted([f for f in os.listdir(migrations_dir) if f.endswith(".sql")])

        for mf in migration_files:
            if mf in applied_versions:
                logger.debug("Migration %s already applied; skipping.", mf)
                continue

            filepath = os.path.join(migrations_dir, mf)
            try:
                with open(filepath, "r", encoding="utf-8") as f:
                    sql_content = f.read()

                logger.info("Applying database migration %s...", mf)
                async with conn.transaction():
                    await conn.execute(sql_content)
                    await conn.execute(
                        "INSERT INTO schema_migrations (version, applied_at) VALUES ($1, NOW());",
                        mf,
                    )
                logger.info("Database migration %s executed and tracked successfully.", mf)
            except Exception as e:
                logger.error("Failed to apply migration %s: %s", mf, e)
                raise MigrationException(f"Database migration {mf} failed: {e}") from e
