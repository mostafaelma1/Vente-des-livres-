# Image officielle Playwright Python : Chromium + dépendances déjà installés.
FROM mcr.microsoft.com/playwright/python:v1.47.0-jammy

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Port d'écoute :
# - Hugging Face Spaces attend 7860 (valeur par défaut ci-dessous) ;
# - Render/Fly/… fournissent la variable PORT, qui est alors utilisée.
ENV PORT=7860
EXPOSE 7860

CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-7860}"]
