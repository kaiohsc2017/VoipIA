-- =============================================================================
-- V90__cleanup_legacy_segmented_tables.sql
-- Remocao de tabelas legadas do modulo de automacao de agentes (agents-platform),
-- segmentado e descontinuado do ecossistema VoipIA.
-- Garante que o PostgreSQL contenha estritamente tabelas ativas e mapeadas pelo sistema.
-- =============================================================================

DROP TABLE IF EXISTS alerts CASCADE;
DROP TABLE IF EXISTS agent_secrets CASCADE;
DROP TABLE IF EXISTS agent_memory CASCADE;
DROP TABLE IF EXISTS agent_evolution_snapshots CASCADE;
DROP TABLE IF EXISTS execution_logs CASCADE;
DROP TABLE IF EXISTS executions CASCADE;
DROP TABLE IF EXISTS knowledge_docs CASCADE;
DROP TABLE IF EXISTS retention_config CASCADE;
DROP TABLE IF EXISTS agents CASCADE;
DROP TABLE IF EXISTS servers CASCADE;
