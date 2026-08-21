-- Amplia a coluna para acomodar o valor cifrado (prefixo "enc:v1:" + base64 de IV+ciphertext+tag
-- do AES-256-GCM) — a cifragem em si acontece na camada Java (EncryptedSecretConverter), nunca em
-- SQL estático, para a chave de cifragem (SSO_SECRET_ENCRYPTION_KEY) nunca aparecer numa migration.
ALTER TABLE sso_configurations ALTER COLUMN client_secret TYPE VARCHAR(512);
