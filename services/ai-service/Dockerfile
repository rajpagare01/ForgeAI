FROM python:3.12-slim

WORKDIR /app

# Install system dependencies (build tools for tree-sitter, libpq for PostgreSQL)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements and install python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application source code and migrations
COPY migrations/ ./migrations/
COPY app/ ./app/
COPY pytest.ini .

# Expose FastAPI port
EXPOSE 8084

# Run FastAPI app via uvicorn
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8084"]
