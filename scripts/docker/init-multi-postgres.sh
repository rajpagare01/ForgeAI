#!/bin/sh
set -e

# Creates individual databases and extensions required for ForgeAI microservices
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE forgeai_identity;
    CREATE DATABASE forgeai_project;
    CREATE DATABASE source_control_db;
    CREATE DATABASE testing_db;
    CREATE DATABASE ai_db;

    GRANT ALL PRIVILEGES ON DATABASE forgeai_identity TO $POSTGRES_USER;
    GRANT ALL PRIVILEGES ON DATABASE forgeai_project TO $POSTGRES_USER;
    GRANT ALL PRIVILEGES ON DATABASE source_control_db TO $POSTGRES_USER;
    GRANT ALL PRIVILEGES ON DATABASE testing_db TO $POSTGRES_USER;
    GRANT ALL PRIVILEGES ON DATABASE ai_db TO $POSTGRES_USER;
EOSQL

# Enable pgvector on ai_db
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "ai_db" <<-EOSQL
    CREATE EXTENSION IF NOT EXISTS vector;
EOSQL
