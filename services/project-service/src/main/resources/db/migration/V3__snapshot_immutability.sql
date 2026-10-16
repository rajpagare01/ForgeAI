-- V3__snapshot_immutability.sql
-- Enforce historical governance snapshot immutability at the database level.
-- INSERT remains allowed. UPDATE and DELETE are both blocked.

CREATE OR REPLACE FUNCTION prevent_traceability_snapshot_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Traceability snapshots are immutable historical governance records and cannot be deleted. (id: %)', OLD.id
            USING ERRCODE = 'P0001';
    ELSE
        RAISE EXCEPTION 'Traceability snapshots are immutable historical governance records and cannot be updated. (id: %)', NEW.id
            USING ERRCODE = 'P0001';
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_traceability_snapshot_mutation ON traceability_snapshots;
CREATE TRIGGER trg_prevent_traceability_snapshot_mutation
BEFORE UPDATE OR DELETE ON traceability_snapshots
FOR EACH ROW
EXECUTE FUNCTION prevent_traceability_snapshot_mutation();
