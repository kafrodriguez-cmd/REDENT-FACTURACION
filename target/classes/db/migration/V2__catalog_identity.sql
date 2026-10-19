ALTER TABLE catalog ADD COLUMN identity_key VARCHAR(150);
CREATE UNIQUE INDEX idx_catalog_identity ON catalog(kind,identity_key);
