"""Scraper Playwright + BeautifulSoup pour marchespublics.gov.ma.

Robuste par conception :
- ne dépend d'aucun id HTML fixe ;
- recherche les champs par libellés texte ;
- détecte tous les tableaux HTML (en-têtes + lignes) ;
- extrait les montants par expression régulière ;
- renvoie aussi rawText et tables pour le debug.
"""

from __future__ import annotations

import re
from urllib.parse import parse_qs, urlparse

from bs4 import BeautifulSoup

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

# ------- Libellés recherchés (insensibles à la casse / accents simples) -------
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

AMOUNT_RE = re.compile(r"\d[\d\s  .,]*\d|\d")


# --------------------------------------------------------------------------- #
# Utilitaires
# --------------------------------------------------------------------------- #
def parse_url(url: str) -> tuple[str, str]:
    """Extrait refConsultation et orgAcronyme d'une URL."""
    try:
        qs = parse_qs(urlparse(url).query)
    except Exception:
        return "", ""
    ref = (qs.get("refConsultation") or [""])[0]
    org = (qs.get("orgAcronyme") or [""])[0]
    return ref.strip(), org.strip()


def parse_amount(text: str | None) -> float | None:
    """Convertit un montant marocain ('1 234 567,89 DH') en float."""
    if not text:
        return None
    t = re.sub(r"(?i)(dirhams?|dhs?|mad)", " ", text)
    m = AMOUNT_RE.search(t)
    if not m:
        return None
    token = re.sub(r"[\s  ]", "", m.group(0))
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


# --------------------------------------------------------------------------- #
# Extraction HTML
# --------------------------------------------------------------------------- #
def _label_value(soup: BeautifulSoup, labels: list[str]) -> str:
    # 1) Lignes de tableau "label | valeur".
    for tr in soup.find_all("tr"):
        cells = tr.find_all(["td", "th"])
        if len(cells) >= 2:
            key = _clean(cells[0].get_text()).lower()
            if any(key.startswith(l) or l in key for l in labels):
                value = _clean(cells[-1].get_text())
                if value and value.lower() != key:
                    return value
    # 2) Paires "label : valeur" dans le texte d'un élément.
    for el in soup.find_all(["p", "li", "div", "span", "label", "strong", "b", "dt", "dd"]):
        t = _clean(el.get_text())
        for lbl in labels:
            m = re.search(rf"(?i)\b{re.escape(lbl)}\b\s*[:\-]\s*(.{{2,200}})", t)
            if m:
                return _clean(m.group(1))
    return ""


def _build_tables(soup: BeautifulSoup) -> list[dict]:
    tables: list[dict] = []
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


def _extract_offres(tables: list[dict]) -> list[dict]:
    """Choisit le meilleur tableau d'offres et en extrait les concurrents."""
    best: list[dict] = []
    for table in tables:
        headers = [h.lower() for h in table["headers"]]
        name_col = next((i for i, h in enumerate(headers)
                         if any(k in h for k in K_NAME)), -1)
        amount_col = next((i for i, h in enumerate(headers)
                           if any(k in h for k in K_AMOUNT)), -1)

        offres: list[dict] = []
        for row in table["rows"]:
            montant = None
            if 0 <= amount_col < len(row):
                montant = parse_amount(row[amount_col])
            if montant is None:
                for cell in row:
                    if _looks_like_amount(cell):
                        montant = parse_amount(cell)
                        break
            if not montant or montant <= 0:
                continue

            societe = ""
            if 0 <= name_col < len(row):
                societe = row[name_col]
            if not societe:
                candidates = [c for c in row if not _looks_like_amount(c) and len(c) >= 2]
                societe = max(candidates, key=len) if candidates else ""
            if not societe:
                continue

            row_text = " ".join(row).lower()
            statut = "ecartee" if any(k in row_text for k in K_STATUS_OUT) else "retenue"
            offres.append({"societe": _clean(societe), "montant": montant, "statut": statut})

        if len(offres) > len(best):
            best = offres
    return best


def _parse_html(html: str, url: str, ref: str, org: str) -> dict:
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
    raw_text = _clean(soup.get_text(" "))

    lots = []
    if offres:
        lots = [{
            "numero": "1",
            "designation": consultation["objet"],
            "estimation": consultation["estimation"],
            "offres": offres,
        }]

    found_header = any(consultation[k] for k in ("reference", "objet", "acheteur"))

    if offres:
        message = (f"{len(offres)} offre(s) récupérée(s). "
                   "Vérifiez les montants avant de calculer.")
    elif found_header:
        message = ("Les informations générales ont été récupérées, mais les montants "
                   "nécessaires au calcul ne sont pas disponibles. Complétez les "
                   "données manuellement.")
    else:
        message = ("Les données de consultation ont été récupérées, mais les résultats "
                   "financiers ne sont pas encore disponibles. Vous pouvez compléter "
                   "les offres manuellement.")

    return {
        "success": True,
        "message": message,
        "refConsultation": ref,
        "orgAcronyme": org,
        "sourceUrl": url,
        "consultation": consultation,
        "lots": lots,
        "tables": tables,
        "rawText": raw_text[:20000],
    }


# --------------------------------------------------------------------------- #
# Point d'entrée : Playwright
# --------------------------------------------------------------------------- #
async def scrape(url: str) -> dict:
    """Ouvre l'URL avec Playwright Chromium headless et extrait les données."""
    ref, org = parse_url(url)

    if not re.match(r"^https?://", url.strip(), re.IGNORECASE):
        return {
            "success": False,
            "errorCode": "INVALID_URL",
            "message": "URL invalide.",
            "refConsultation": ref,
            "orgAcronyme": org,
        }

    try:
        # Import paresseux : l'application peut démarrer sans navigateur installé.
        from playwright.async_api import async_playwright
    except Exception:
        return {
            "success": False,
            "errorCode": "PLAYWRIGHT_MISSING",
            "message": "Playwright n'est pas installé sur le serveur "
                       "(pip install playwright && playwright install chromium).",
            "refConsultation": ref,
            "orgAcronyme": org,
        }

    try:
        async with async_playwright() as p:
            browser = await p.chromium.launch(headless=True, args=["--no-sandbox"])
            context = await browser.new_context(
                user_agent=USER_AGENT,
                locale="fr-FR",
                viewport={"width": 1366, "height": 900},
            )
            page = await context.new_page()
            await page.goto(url, wait_until="networkidle", timeout=60000)
            # Laisse le temps au JavaScript de finir le rendu.
            await page.wait_for_timeout(3000)
            html = await page.content()
            await browser.close()
    except Exception as exc:  # noqa: BLE001
        return {
            "success": False,
            "errorCode": "SCRAPING_FAILED",
            "message": "Extraction automatique impossible "
                       f"({type(exc).__name__}). Veuillez utiliser le mode manuel.",
            "refConsultation": ref,
            "orgAcronyme": org,
        }

    return _parse_html(html, url, ref, org)
