-- Migration 005: Engineering Intelligence & Change Impact Engine
-- Extends analyses table with deterministic risk scoring, confidence, risk factors, and structured impact metadata

ALTER TABLE analyses ADD COLUMN IF NOT EXISTS risk_score NUMERIC(5,2) DEFAULT 0.0;
ALTER TABLE analyses ADD COLUMN IF NOT EXISTS confidence VARCHAR(16) DEFAULT 'MEDIUM';
ALTER TABLE analyses ADD COLUMN IF NOT EXISTS risk_factors JSONB DEFAULT '[]'::jsonb;
ALTER TABLE analyses ADD COLUMN IF NOT EXISTS impact_metadata JSONB DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_analyses_risk_level_score
    ON analyses (organization_id, risk_level, risk_score);
