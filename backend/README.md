# Backend PrixRef AO — FastAPI + Playwright

Service d'extraction des consultations de `marchespublics.gov.ma`. Le site
étant dynamique (JavaScript), le scraping se fait côté serveur avec **Playwright
Chromium headless**, puis l'analyse HTML avec **BeautifulSoup**. L'application
Android appelle ce backend ; le **mode manuel reste utilisable hors ligne**.

## Installation

```bash
cd backend
python3 -m venv venv
source venv/bin/activate           # Windows : venv\Scripts\activate
pip install -r requirements.txt
playwright install chromium        # télécharge le navigateur (~150 Mo)
```

## Lancement

```bash
uvicorn main:app --host 0.0.0.0 --port 8000
```

- Émulateur Android : l'app utilise `http://10.0.2.2:8000`
- Téléphone réel : utilisez l'IP locale du PC, ex. `http://192.168.1.20:8000`
  (PC et téléphone sur le même Wi-Fi).
- Production : déployez derrière HTTPS, ex. `https://monserveur.com`.

## Déploiement en ligne (recommandé)

Pour que l'analyse automatique fonctionne **sans laisser un PC allumé**, hébergez
ce backend. Un `Dockerfile` (basé sur l'image officielle Playwright, navigateurs
préinstallés) et un blueprint `render.yaml` sont fournis.

### Option A — Render (gratuit, le plus simple)

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/mostafaelma1/Vente-des-livres-)

1. Créez un compte gratuit sur https://render.com (connexion avec GitHub).
2. **New + → Blueprint**, choisissez ce dépôt, **branche
   `claude/moroccan-tender-analyzer-0szsuc`** → Render lit `render.yaml`.
3. Validez : Render construit l'image Docker et déploie. Vous obtenez une URL du
   type `https://prixref-backend.onrender.com`.
4. Dans l'app Android → **Paramètres** → collez cette URL.

> Plan gratuit : l'instance se met en veille après ~15 min d'inactivité (le
> premier appel peut prendre 30–60 s). La RAM est limitée (512 Mo) ; si Chromium
> manque de mémoire, passez à un plan supérieur ou utilisez Fly.io.

### Option B — Fly.io / Railway / VPS

L'image Docker fonctionne sur n'importe quelle plateforme acceptant Docker :

```bash
docker build -t prixref-backend ./backend
docker run -p 8000:8000 prixref-backend
```

### Option C — Hugging Face Spaces (gratuit, sans carte)

Créez un Space **Docker**, ajoutez le contenu de `backend/`, et exposez le port
attendu par HF (`PORT=7860`). L'URL publique sera `https://<user>-<space>.hf.space`.

## Endpoint

`POST /api/analyse-url`

Requête :

```json
{ "url": "https://www.marchespublics.gov.ma/?page=entreprise.SuiviConsultation&refConsultation=1006000&orgAcronyme=u6i" }
```

Réponse (succès) :

```json
{
  "success": true,
  "refConsultation": "1006000",
  "orgAcronyme": "u6i",
  "sourceUrl": "...",
  "consultation": {
    "reference": "", "objet": "", "acheteur": "", "lieuExecution": "",
    "categorie": "", "procedure": "", "estimation": 0, "caution": 0
  },
  "lots": [
    { "numero": "1", "designation": "", "estimation": 0,
      "offres": [ { "societe": "", "montant": 0, "statut": "retenue" } ] }
  ],
  "tables": [ { "index": 0, "headers": [], "rows": [] } ],
  "rawText": "",
  "message": ""
}
```

Réponse (échec) :

```json
{
  "success": false,
  "errorCode": "SCRAPING_FAILED",
  "message": "Extraction automatique impossible. Veuillez utiliser le mode manuel.",
  "refConsultation": "1006000",
  "orgAcronyme": "u6i"
}
```

## Test rapide

```bash
curl -X POST http://localhost:8000/api/analyse-url \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.marchespublics.gov.ma/?page=entreprise.SuiviConsultation&refConsultation=1006000&orgAcronyme=u6i"}'
```

## Robustesse

Le scraper (`scraper.py`) ne dépend d'aucun `id` HTML fixe :
- recherche des champs par **libellés texte** (objet, acheteur, estimation, …) ;
- détection de **tous les tableaux** (en-têtes + lignes) renvoyés dans `tables` ;
- extraction des montants par **expression régulière** (espaces, NBSP, virgule
  décimale, points de milliers) ;
- `rawText` et `tables` sont toujours renvoyés pour faciliter le **debug** et la
  correction du mapping des colonnes.

## Multi-utilisateurs

Le backend est conçu pour servir plusieurs utilisateurs :
- **Limite de concurrence** : au plus 2 navigateurs Chromium simultanés
  (`_MAX_CONCURRENCY`) pour ne pas saturer la mémoire ; les autres requêtes
  attendent leur tour.
- **Cache** : les consultations récentes (clé `refConsultation|orgAcronyme`) sont
  mises en cache 10 min, donc plusieurs utilisateurs qui ouvrent la même
  consultation ne déclenchent qu'un seul scraping.

> Pour une forte charge, augmentez la RAM/CPU de l'hébergement et `_MAX_CONCURRENCY`.

## Calcul du prix de référence

Le calcul est effectué **côté serveur** (champ `analyse` de la réponse) **et**
reste disponible côté application. À partir des offres `retenue` :

```
moyenneOffres = somme(offres retenues) / nombre(offres retenues)
prixReference = (estimation + moyenneOffres) / 2
ecartDh       = |offre - prixReference|
ecartPercent  = ecartDh / prixReference * 100
classement    = tri par ecartDh croissant
```
