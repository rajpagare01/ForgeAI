-- V3__snapshot_immutability.sql
-- Enforce historical governance snapshot immutability at the database level

CREATE OR REPLACE FUNCTION prevent_traceability_snapshot_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Traceability snapshots are immutable historical governance records and cannot be updated.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_traceability_snapshot_mutation ON traceability_snapshots;
CREATE TRIGGER trg_prevent_traceability_snapshot_mutation
BEFORE UPDATE ON traceability_snapshots
FOR EACH ROW
EXECUTE FUNCTION prevent_traceability_snapshot_mutation();
