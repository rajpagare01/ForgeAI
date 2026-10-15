import pytest
from unittest.mock import AsyncMock, MagicMock
from app.core.migration_runner import run_migrations, MigrationException


@pytest.mark.asyncio
async def test_migration_runner_ordered_execution_and_tracking(tmp_path, monkeypatch):
    """Verifies migrations run in alphabetical order and record tracking versions."""
    # 1. Create temporary migration files
    m1 = tmp_path / "001_initial.sql"
    m1.write_text("CREATE TABLE test_one (id INT);", encoding="utf-8")
    m2 = tmp_path / "002_update.sql"
    m2.write_text("ALTER TABLE test_one ADD COLUMN name TEXT;", encoding="utf-8")

    # Point runner to temporary directory
    monkeypatch.setattr(
        "app.core.migration_runner.os.path.abspath",
        lambda p: str(tmp_path),
    )

    executed_sqls = []
    applied_records = []

    mock_conn = AsyncMock()

    async def mock_execute(sql, *args):
        executed_sqls.append(sql.strip())
        if "INSERT INTO schema_migrations" in sql and args:
            applied_records.append(args[0])

    mock_conn.execute.side_effect = mock_execute
    # Initially no applied migrations
    mock_conn.fetch.return_value = []

    # Mock transaction context manager
    mock_tx = MagicMock()
    mock_tx.__aenter__ = AsyncMock(return_value=None)
    mock_tx.__aexit__ = AsyncMock(return_value=None)
    mock_conn.transaction = MagicMock(return_value=mock_tx)

    mock_pool = MagicMock()
    mock_acquire = AsyncMock()
    mock_acquire.__aenter__.return_value = mock_conn
    mock_acquire.__aexit__.return_value = None
    mock_pool.acquire.return_value = mock_acquire

    # Run migrations
    await run_migrations(mock_pool)

    # Assert tracking table created
    assert any("CREATE TABLE IF NOT EXISTS schema_migrations" in sql for sql in executed_sqls)
    # Assert both migrations applied in order
    assert applied_records == ["001_initial.sql", "002_update.sql"]
    assert any("CREATE TABLE test_one" in sql for sql in executed_sqls)
    assert any("ALTER TABLE test_one" in sql for sql in executed_sqls)


@pytest.mark.asyncio
async def test_migration_runner_skips_already_applied(tmp_path, monkeypatch):
    """Verifies already applied migrations are skipped."""
    m1 = tmp_path / "001_initial.sql"
    m1.write_text("CREATE TABLE test_one (id INT);", encoding="utf-8")

    monkeypatch.setattr(
        "app.core.migration_runner.os.path.abspath",
        lambda p: str(tmp_path),
    )

    executed_sqls = []
    mock_conn = AsyncMock()
    mock_conn.execute.side_effect = lambda sql, *args: executed_sqls.append(sql)
    # Migration 001 already in tracking table
    mock_conn.fetch.return_value = [{"version": "001_initial.sql"}]

    mock_pool = MagicMock()
    mock_acquire = AsyncMock()
    mock_acquire.__aenter__.return_value = mock_conn
    mock_acquire.__aexit__.return_value = None
    mock_pool.acquire.return_value = mock_acquire

    await run_migrations(mock_pool)

    # 001_initial.sql should NOT have been executed
    assert not any("CREATE TABLE test_one" in sql for sql in executed_sqls)


@pytest.mark.asyncio
async def test_migration_runner_fails_fast_on_error(tmp_path, monkeypatch):
    """Verifies that migration failure raises MigrationException and fails startup."""
    m1 = tmp_path / "001_corrupt.sql"
    m1.write_text("INVALID SQL SYNTAX !!!", encoding="utf-8")

    monkeypatch.setattr(
        "app.core.migration_runner.os.path.abspath",
        lambda p: str(tmp_path),
    )

    mock_conn = AsyncMock()
    mock_conn.fetch.return_value = []

    async def mock_execute(sql, *args):
        if "INVALID SQL" in sql:
            raise Exception("syntax error at or near 'INVALID'")

    mock_conn.execute.side_effect = mock_execute

    mock_tx = MagicMock()
    mock_tx.__aenter__ = AsyncMock(return_value=None)
    mock_tx.__aexit__ = AsyncMock(return_value=None)
    mock_conn.transaction = MagicMock(return_value=mock_tx)

    mock_pool = MagicMock()
    mock_acquire = AsyncMock()
    mock_acquire.__aenter__.return_value = mock_conn
    mock_acquire.__aexit__.return_value = None
    mock_pool.acquire.return_value = mock_acquire

    with pytest.raises(MigrationException) as exc_info:
        await run_migrations(mock_pool)

    assert "001_corrupt.sql" in str(exc_info.value)
