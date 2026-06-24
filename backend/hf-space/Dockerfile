# Hugging Face Space (Docker) — version 2 fichiers (Dockerfile + app.py).
# Image officielle Playwright : Chromium + dépendances déjà installés.
FROM mcr.microsoft.com/playwright/python:v1.47.0-jammy

WORKDIR /app

RUN pip install --no-cache-dir fastapi "uvicorn[standard]" beautifulsoup4 lxml pydantic regex

COPY app.py .

# Hugging Face attend le port 7860.
ENV PORT=7860
EXPOSE 7860

CMD ["sh", "-c", "uvicorn app:app --host 0.0.0.0 --port ${PORT:-7860}"]
