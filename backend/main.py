"""API FastAPI du backend PrixRef AO.

Endpoint principal : POST /api/analyse-url
- reçoit une URL marchespublics.gov.ma,
- l'ouvre avec Playwright Chromium headless,
- extrait les données et les renvoie en JSON.
"""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from schemas import AnalyseUrlRequest, AnalyseUrlResponse
from scraper import parse_url, scrape

app = FastAPI(
    title="PrixRef AO — Backend d'analyse",
    description="Scraping Playwright des consultations marchespublics.gov.ma.",
    version="1.0.0",
)

# CORS ouvert : l'app mobile appelle directement le backend.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/api/analyse-url", response_model=AnalyseUrlResponse)
async def analyse_url(body: AnalyseUrlRequest) -> AnalyseUrlResponse:
    ref, org = parse_url(body.url)
    try:
        data = await scrape(body.url)
    except Exception as exc:  # noqa: BLE001
        return AnalyseUrlResponse(
            success=False,
            errorCode="SCRAPING_FAILED",
            message=f"Extraction automatique impossible ({type(exc).__name__}). "
                    "Veuillez utiliser le mode manuel.",
            refConsultation=ref,
            orgAcronyme=org,
            sourceUrl=body.url,
        )
    # ``data`` est déjà conforme au schéma ; AnalyseUrlResponse comble les défauts.
    return AnalyseUrlResponse(**data)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
