"""PrixRef AO — backend tout-en-un (FastAPI + Playwright).

Version monofichier pour un déploiement facile sur Hugging Face Spaces.
Endpoint principal : POST /api/analyse-url
"""

from __future__ import annotations

import asyncio
import re
import time
from urllib.parse import parse_qs, urlparse

from bs4 import BeautifulSoup
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

# ---- Multi-utilisateurs : concurrence limitée + cache ----
_MAX_CONCURRENCY = 2
_SEM = asyncio.Semaphore(_MAX_CONCURRENCY)
_CACHE: dict[str, tuple[float, dict]] = {}
_CACHE_TTL_SECONDS = 600

LABELS = {
    "reference": ["référence", "reference", "n° de consultation", "numéro de consultation"],
    "objet": ["objet"],
    "acheteur": ["maître d'ouvrage", "maitre d'ouvrage", "acheteur public", "acheteur",
                 "administration", "service"],
    "lieuExecution": ["lieu d'exécution", "lieu d'execution", "lieu de réalisation", "lieu"],
    "categorie": ["catégorie", "categorie", "domaine"],
    "procedure": ["procédure", "procedure", "mode de passation", "type de procédure"],
    "estimation": ["estimation", "montant estimé", "estimation du maître"],
    "caution": ["caution", "cautionnement provisoire", "garantie"],
}
K_NAME = ["entreprise", "société", "societe", "soumissionnaire", "concurrent",
          "raison sociale", "attributaire"]
K_AMOUNT = ["montant", "offre", "prix", "proposé", "propose"]
K_STATUS_OUT = ["écart", "ecart", "rejet", "rejeté", "exclu", "non admis", "non retenu",
                "éliminé", "elimine"]
AMOUNT_RE = re.compile(r"\d[\d\s  .,]*\d|\d")


# --------------------------------------------------------------------------- #
# Utilitaires
# --------------------------------------------------------------------------- #
def parse_url(url: str) -> tuple[str, str]:
    try:
        qs = parse_qs(urlparse(url).query)
    except Exception:
        return "", ""
    return (qs.get("refConsultation") or [""])[0].strip(), (qs.get("orgAcronyme") or [""])[0].strip()


def parse_amount(text):
    if not text:
        return None
    t = re.sub(r"(?i)(dirhams?|dhs?|mad)", " ", text)
    m = AMOUNT_RE.search(t)
    if not m:
        return None
    token = re.sub(r"[\s  ]", "", m.group(0))
    if "," in token and "." in token:
        token = token.replace(".", "").replace(",", ".")
    elif "," in token:
        token = token.replace(",", ".")
    elif re.fullmatch(r"\d{1,3}(\.\d{3})+", token):
        token = token.replace(".", "")
    try:
        return float(token)
    except ValueError:
        return None


def _looks_like_amount(text: str) -> bool:
    return sum(c.isdigit() for c in text) >= 4 and parse_amount(text) is not None


def _clean(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()[:400]


def _label_value(soup, labels):
    for tr in soup.find_all("tr"):
        cells = tr.find_all(["td", "th"])
        if len(cells) >= 2:
            key = _clean(cells[0].get_text()).lower()
            if any(key.startswith(l) or l in key for l in labels):
                value = _clean(cells[-1].get_text())
                if value and value.lower() != key:
                    return value
    for el in soup.find_all(["p", "li", "div", "span", "label", "strong", "b", "dt", "dd"]):
        t = _clean(el.get_text())
        for lbl in labels:
            m = re.search(rf"(?i)\b{re.escape(lbl)}\b\s*[:\-]\s*(.{{2,200}})", t)
            if m:
                return _clean(m.group(1))
    return ""


def _build_tables(soup):
    tables = []
    for i, table in enumerate(soup.find_all("table")):
        trs = table.find_all("tr")
        if not trs:
            continue
        headers = [_clean(c.get_text()) for c in trs[0].find_all(["th", "td"])]
        rows = []
        for tr in trs[1:]:
            row = [_clean(c.get_text()) for c in tr.find_all(["td", "th"])]
            if any(cell for cell in row):
                rows.append(row)
            if len(rows) >= 300:
                break
        max_cols = max([len(headers)] + [len(r) for r in rows] or [0])
        if max_cols >= 2 and (headers or rows):
            tables.append({"index": i, "headers": headers, "rows": rows})
    return tables


def _extract_offres(tables):
    best = []
    for table in tables:
        headers = [h.lower() for h in table["headers"]]
        name_col = next((i for i, h in enumerate(headers) if any(k in h for k in K_NAME)), -1)
        amount_col = next((i for i, h in enumerate(headers) if any(k in h for k in K_AMOUNT)), -1)
        offres = []
        for row in table["rows"]:
            montant = parse_amount(row[amount_col]) if 0 <= amount_col < len(row) else None
            if montant is None:
                for cell in row:
                    if _looks_like_amount(cell):
                        montant = parse_amount(cell)
                        break
            if not montant or montant <= 0:
                continue
            societe = row[name_col] if 0 <= name_col < len(row) else ""
            if not societe:
                cands = [c for c in row if not _looks_like_amount(c) and len(c) >= 2]
                societe = max(cands, key=len) if cands else ""
            if not societe:
                continue
            rt = " ".join(row).lower()
            statut = "ecartee" if any(k in rt for k in K_STATUS_OUT) else "retenue"
            offres.append({"societe": _clean(societe), "montant": montant, "statut": statut})
        if len(offres) > len(best):
            best = offres
    return best


# --------------------------------------------------------------------------- #
# Calcul (prix de référence + classement)
# --------------------------------------------------------------------------- #
def _observation(p):
    if p <= 1.0:
        return "Très proche"
    if p <= 3.0:
        return "Proche"
    if p <= 7.0:
        return "Moyen"
    return "Éloigné"


def _is_retenue(statut):
    s = (statut or "").lower()
    return not any(k in s for k in ("ecart", "écart", "rejet", "exclu", "elimin"))


def compute_analyse(estimation, lots):
    offres = []
    for lot in lots:
        offres.extend(lot.get("offres", []))
    retenues = [o for o in offres if _is_retenue(o.get("statut", "retenue"))
                and float(o.get("montant", 0) or 0) > 0]
    nb_ecartees = len(offres) - len(retenues)
    est = float(estimation or 0)
    if est <= 0 or not retenues:
        return {"calculable": False, "estimation": est, "moyenneOffres": 0.0,
                "prixReference": 0.0, "nbRetenues": len(retenues), "nbEcartees": nb_ecartees,
                "gagnantProbable": None, "classement": [],
                "message": "Montants insuffisants pour le calcul. Complétez manuellement."}
    moyenne = sum(float(o["montant"]) for o in retenues) / len(retenues)
    prix_ref = (est + moyenne) / 2.0
    classees = []
    for o in retenues:
        montant = float(o["montant"])
        ecart = abs(montant - prix_ref)
        pct = (ecart / prix_ref * 100.0) if prix_ref else 0.0
        classees.append({"societe": o.get("societe", ""), "montant": montant,
                         "statut": o.get("statut", "retenue"), "ecartDh": ecart,
                         "ecartPercent": pct, "observation": _observation(pct)})
    classees.sort(key=lambda x: (x["ecartDh"], x["montant"]))
    for i, c in enumerate(classees, start=1):
        c["rang"] = i
    return {"calculable": True, "estimation": est, "moyenneOffres": moyenne,
            "prixReference": prix_ref, "nbRetenues": len(retenues), "nbEcartees": nb_ecartees,
            "gagnantProbable": classees[0]["societe"] if classees else None,
            "classement": classees,
            "message": f"{len(retenues)} offre(s) retenue(s)."}


def _parse_html(html, url, ref, org):
    soup = BeautifulSoup(html, "lxml")
    consultation = {
        "reference": _label_value(soup, LABELS["reference"]) or ref,
        "objet": _label_value(soup, LABELS["objet"]),
        "acheteur": _label_value(soup, LABELS["acheteur"]),
        "lieuExecution": _label_value(soup, LABELS["lieuExecution"]),
        "categorie": _label_value(soup, LABELS["categorie"]),
        "procedure": _label_value(soup, LABELS["procedure"]),
        "estimation": parse_amount(_label_value(soup, LABELS["estimation"])) or 0.0,
        "caution": parse_amount(_label_value(soup, LABELS["caution"])) or 0.0,
    }
    tables = _build_tables(soup)
    offres = _extract_offres(tables)
    raw_text = _clean(soup.get_text(" "))[:20000]
    lots = [{"numero": "1", "designation": consultation["objet"],
             "estimation": consultation["estimation"], "offres": offres}] if offres else []
    found = any(consultation[k] for k in ("reference", "objet", "acheteur"))
    if offres:
        message = f"{len(offres)} offre(s) récupérée(s)."
    elif found:
        message = ("Informations générales récupérées, mais montants indisponibles. "
                   "Complétez manuellement.")
    else:
        message = ("Données de consultation récupérées, mais résultats financiers "
                   "indisponibles. Complétez manuellement.")
    return {"success": True, "message": message, "refConsultation": ref, "orgAcronyme": org,
            "sourceUrl": url, "consultation": consultation, "lots": lots,
            "analyse": compute_analyse(consultation["estimation"], lots),
            "tables": tables, "rawText": raw_text}


# --------------------------------------------------------------------------- #
# Scraping Playwright
# --------------------------------------------------------------------------- #
async def scrape(url: str) -> dict:
    ref, org = parse_url(url)
    if not re.match(r"^https?://", url.strip(), re.IGNORECASE):
        return {"success": False, "errorCode": "INVALID_URL", "message": "URL invalide.",
                "refConsultation": ref, "orgAcronyme": org}

    cache_key = f"{ref}|{org}|{url}"
    cached = _CACHE.get(cache_key)
    if cached and (time.time() - cached[0]) < _CACHE_TTL_SECONDS and cached[1].get("success"):
        return cached[1]

    try:
        from playwright.async_api import async_playwright
    except Exception:
        return {"success": False, "errorCode": "PLAYWRIGHT_MISSING",
                "message": "Playwright non installé.", "refConsultation": ref, "orgAcronyme": org}

    try:
        async with _SEM, async_playwright() as p:
            browser = await p.chromium.launch(
                headless=True,
                args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
                      "--disable-extensions", "--no-zygote"],
            )
            context = await browser.new_context(user_agent=USER_AGENT, locale="fr-FR",
                                                viewport={"width": 1280, "height": 800})
            page = await context.new_page()

            async def _block(route):
                if route.request.resource_type in ("image", "media", "font"):
                    await route.abort()
                else:
                    await route.continue_()

            await page.route("**/*", _block)
            await page.goto(url, wait_until="networkidle", timeout=60000)
            await page.wait_for_timeout(3000)
            html = await page.content()
            await browser.close()
    except Exception as exc:
        return {"success": False, "errorCode": "SCRAPING_FAILED",
                "message": f"Extraction impossible ({type(exc).__name__}). "
                           "Veuillez utiliser le mode manuel.",
                "refConsultation": ref, "orgAcronyme": org}

    result = _parse_html(html, url, ref, org)
    if result.get("success"):
        _CACHE[cache_key] = (time.time(), result)
    return result


# --------------------------------------------------------------------------- #
# API
# --------------------------------------------------------------------------- #
app = FastAPI(title="PrixRef AO — Backend", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


class AnalyseRequest(BaseModel):
    url: str


@app.get("/")
async def root():
    return {"service": "PrixRef AO", "status": "ok", "endpoint": "POST /api/analyse-url"}


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/api/analyse-url")
async def analyse_url(body: AnalyseRequest):
    ref, org = parse_url(body.url)
    try:
        return await scrape(body.url)
    except Exception as exc:
        return {"success": False, "errorCode": "SCRAPING_FAILED",
                "message": f"Extraction impossible ({type(exc).__name__}).",
                "refConsultation": ref, "orgAcronyme": org, "sourceUrl": body.url}
