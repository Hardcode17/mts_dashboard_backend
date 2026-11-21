ALTER TABLE deadline_risk
    DROP CONSTRAINT ck_deadline_risk_score,
    ADD CONSTRAINT ck_deadline_risk_score CHECK (risk <> 'unknown' OR score IS NULL);
