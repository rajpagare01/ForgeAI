# syntax=docker/dockerfile:1
# ------------------------------------------------------------------------------
# Stage 1: Build & Compile Dependencies (Tree-Sitter grammars, C-extensions)
# ------------------------------------------------------------------------------
FROM python:3.12-slim AS builder
WORKDIR /build

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# Install C/C++ compilation tools and system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    gcc \
    g++ \
    python3-dev \
    libpq-dev \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

# Pre-compile wheels & packages into isolated /install prefix
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir --prefix=/install -r requirements.txt

# Copy source and verify tree-sitter bindings pre-compilation
COPY . .
RUN PYTHONPATH=/install/lib/python3.12/site-packages python -c \
    "import tree_sitter, tree_sitter_python, tree_sitter_java; print('Tree-Sitter grammars pre-compiled successfully')"

# ------------------------------------------------------------------------------
# Stage 2: Minimal Production Slim Runtime
# ------------------------------------------------------------------------------
FROM python:3.12-slim AS runtime
WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8084

# Install runtime shared libraries and curl for health checks
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    libpq5 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 1000 forgeai \
    && useradd -u 1000 -g forgeai -s /bin/bash -m forgeai

# Copy pre-compiled Python libraries from builder
COPY --from=builder /install /usr/local

# Copy application source code with non-root ownership
COPY --chown=forgeai:forgeai . /app

USER forgeai:forgeai

EXPOSE 8084

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8084"]
