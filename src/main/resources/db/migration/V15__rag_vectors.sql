-- Flyway owns the vector schema; PgVectorStore.initializeSchema remains false.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;

CREATE TABLE rag_vector_store (
    id uuid DEFAULT public.uuid_generate_v4() PRIMARY KEY,
    content text,
    metadata json,
    embedding public.vector(1536)
);
CREATE INDEX idx_rag_vector_cosine ON rag_vector_store USING hnsw (embedding public.vector_cosine_ops);
