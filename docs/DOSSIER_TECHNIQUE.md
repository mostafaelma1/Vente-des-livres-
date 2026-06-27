# B Marche – Dossier technique (handover développeur)

> Document destiné à un développeur Android professionnel pour **diagnostic et
> recommandations**. Il décrit l'architecture, le code, les données, le build et
> la dette technique connue. Application **100 % locale (sans backend)**.

---

## 1. Présentation

**B Marche – Calcul Prix Référence** : application Android d'aide à l'analyse des
**appels d'offres publics au Maroc** (source : `marchespublics.gov.ma`).

Elle permet de :
- récupérer les données d'une consultation (extraction locale via WebView+JS) ;
- calculer un **prix de référence** et **classer** les offres ;
- suivre des **statistiques par société / catégorie / domaine** ;
- analyser le **comportement des concurrents** (profils, top par domaine) ;
- **simuler** une offre avant dépôt avec alertes ;
- exporter en **PDF / Excel (CSV)**.

**Tout est stocké et calculé sur l'appareil. Aucune donnée n'est envoyée à un serveur.**

---

## 2. Stack technique

| Élément | Valeur |
|---|---|
| Langage | Kotlin |
| UI | Android Views + **ViewBinding**, Material 3 |
| applicationId | `com.prixref.ao` |
| versionName / versionCode | `1.0` / **1** |
| minSdk / target / compile | **26** / 34 / 34 |
| Java | 17 |
| Base locale | **Room** (SQLite) |
| JSON | Gson |
| Async | Coroutines |
| Réseau (optionnel, inutilisé) | Retrofit + OkHttp |
| Extraction | **WebView + JavaScript injecté** |
| Build | Gradle (KTS), GitHub Actions |

### Dépendances (app/build.gradle.kts)
```
androidx.core:core-ktx:1.13.1
androidx.appcompat:appcompat:1.7.0
com.google.android.material:material:1.12.0
androidx.constraintlayout:constraintlayout:2.1.4
androidx.activity:activity-ktx:1.9.1
androidx.recyclerview:recyclerview:1.3.2
androidx.lifecycle:lifecycle-runtime-ktx:2.8.4
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1
androidx.room:room-runtime/room-ktx:2.6.1 (+ ksp room-compiler)
com.google.code.gson:gson:2.11.0
com.squareup.retrofit2:retrofit/converter-gson:2.11.0   # optionnel, mode serveur non utilisé
com.squareup.okhttp3:okhttp/logging-interceptor:4.12.0  # idem
junit:junit:4.13.2 (test)
```

---

## 3. Structure du projet (packages `com.prixref.ao`)

### Écrans (Activities)
- `SplashActivity` — écran d'ouverture animé (launcher).
- `MainActivity` — accueil (cartes : analyse, simulation, stats, contact, premium).
- `UrlAnalysisActivity` — **analyse par URL** : WebView cachée + extraction auto + animation.
- `ManualAnalysisActivity` — saisie/édition manuelle d'une analyse.
- `ResultActivity` — résultat (classement, écarts) + **enregistrement auto** + PDF/CSV.
- `SimulationActivity` — **simulation avant dépôt** enrichie (concurrents + alertes).
- `CompanyStatsActivity` — **Stats par catégorie** (catégorie → domaine → sociétés).
- `SocietyStatsActivity` — **Stats par société** (détail par domaine).
- `CompetitorsActivity` — **Stats concurrents** (liste + profils).
- `CompetitorDetailActivity` — fiche société (indicateurs, par domaine/ville).
- `TopCompetitorsActivity` — **Top concurrents par domaine** (classement par score).
- `HistoryActivity` — historique des analyses (recherche, ouverture, suppression).
- `ContactActivity` — e-mail / WhatsApp.
- `AboutActivity`, `SettingsActivity` — **orphelins** (non liés à l'accueil ; Settings = serveur optionnel).
- `AdvancedStatsActivity` — **code mort** (retiré de l'accueil/manifest).

### Logique métier
- `analyze/PageData.kt` — modèles d'extraction + **scripts JS** (`WebExtraction.SCRIPT`, `EXPAND_SCRIPT`).
- `analyze/LocalAnalyzer.kt` — parsing du contenu de page → `AnalysisInput`
  (libellés robustes : **insensible aux accents/apostrophes**, libellé **pluriel**
  « Domaines d'activité », parsing montants format FR/MA, détection des offres,
  noms de sociétés, catégorie/type, domaine, date limite).
- `calc/ReferenceCalculator.kt` — **moteur de calcul** (voir §6).
- `calc/UrlParser.kt` — extrait `refConsultation` / `orgAcronyme`.
- `data/AppDatabase.kt`, `AnalysisDao.kt`, `AnalysisEntity.kt` — Room.
- `data/JsonStore.kt` — (dé)sérialisation `AnalysisResult`.
- `data/CompanyStats.kt` — agrégation par société / catégorie / domaine.
- `data/CompetitorEngine.kt` — **profils concurrents**, top par domaine, paysage simulation.
- `data/HiddenCompanies.kt` — sociétés masquées (SharedPreferences).
- `data/AdvancedStats.kt` — **code mort** (à supprimer).
- `model/Models.kt` — modèles métier (voir §5).
- `pdf/PdfReportGenerator.kt` — rapport PDF (logo + branding).
- `export/CsvExporter.kt` — export CSV (Excel FR, BOM UTF-8).
- `util/Format.kt` (montants/%/dates), `util/Sharing.kt` (FileProvider).
- `api/*` — client backend **optionnel non utilisé**.
- `ui/HistoryAdapter.kt` — RecyclerView historique.

---

## 4. Flux de données

```
URL marchespublics → WebView (UA bureau, JS activé)
   → EXPAND_SCRIPT (déplie les « + »)
   → SCRIPT (innerText, tables, titres, montants) → JSON
   → LocalAnalyzer → AnalysisInput
   → ReferenceCalculator → AnalysisResult
   → ResultActivity (affichage)
   → enregistrement automatique (Room, anti-doublon)
   → CompanyStats / CompetitorEngine (statistiques & profils)
```
La WebView est **invisible** (recouverte par un écran d'animation) ; l'utilisateur
ne voit que « Analyse en cours… » puis le résultat.

---

## 5. Modèle de données

### Room — table `analyses` (`AnalysisEntity`)
```
id (PK auto), date (Long), reference, objet, maitreOuvrage,
estimation (Double), referencePrice (Double), probableWinner, json (String)
```
`json` = `AnalysisResult` complet sérialisé (source de vérité des stats).

DAO : `insert`, `delete`, `getAll`, `search`, `getById`,
`countMatching(reference, estimation, referencePrice)` (anti-doublon).

### Modèles (`model/Models.kt`)
```
AnalysisInput(reference, objet, maitreOuvrage, typeMarche, lieu, estimation,
              lotNumero, lotDesignation, competitors[], dateLimite,
              categorieLabel, domaine)
Competitor(name, amount, retained)
RankedOffer(rank, name, amount, gap, gapPercent, observation, risk,
            isProbableWinner, gapEstimation, gapEstimationPercent)
AnalysisResult(input, averageRetained, referencePrice, retainedCount,
               excludedCount, ranking[], excluded[], probableWinner)
```

> Les **statistiques concurrents ne sont pas stockées** : elles sont **recalculées**
> à la volée depuis l'historique (`CompetitorEngine.build`). Simple et sans
> migration ; à revoir si l'historique devient très volumineux (cf. §8).

---

## 6. Calculs (formules)

- Moyenne retenues = Σ(offres retenues) / nb retenues
- **Prix de référence** = (estimation + moyenne retenues) / 2
- Écart vs réf (DH) = |offre − prixRef| ; (%) = écart / prixRef × 100
- Écart vs estimation (signé) = (offre − estimation) / estimation × 100
- **Classement** : offres ≤ prix de référence **d'abord**, puis par proximité croissante.

### Profils concurrents (`CompetitorEngine`)
Écart **signé** vs réf = (offre − prixRef)/prixRef×100. Indicateurs par société :
classement moyen, taux top 3, taux proche réf (±3 %), taux offre basse (< −10 %),
taux offre haute (> +10 %), variabilité (écart-type).
Profils : **Stratégique, Agressif, Stable, Irrégulier, Faible, Local fort**.
Fiabilité : 1‑2 = insuffisant, 3‑5 = faible, 6‑10 = moyenne, >10 = forte.
Top par domaine : score = fiabilité + top3 + proximité + classement + volume.

---

## 7. Build, signature, CI

- Workflows : `.github/workflows/build-prixref-apk.yml` (push sur la branche).
- Étapes : JDK 17 → Android SDK → tests unitaires → `assembleRelease` → APK signé
  → upload artifact → **Release** `prixref-v<run_number>` (fichier `PrixRef-AO-Maroc.apk`).
- **Signature** : clé stable embarquée (`app/prixref-release.jks`, identifiants dans
  `build.gradle.kts`) → mises à jour installables par-dessus sans désinstallation.
  ⚠️ **À sécuriser** avant publication (clé/keystore en clair dans le repo).
- Branche de dev : `claude/moroccan-tender-analyzer-0szsuc`.

---

## 8. Dette technique & points de diagnostic

1. **Code mort** : `AdvancedStatsActivity.kt` + `data/AdvancedStats.kt` (à supprimer).
2. **Écrans orphelins** : `AboutActivity`, `SettingsActivity` (au manifeste, non liés).
   `api/*` + Retrofit/OkHttp inutilisés (mode serveur abandonné).
3. **versionCode figé à 1** → incrémentation requise (Play Store).
4. **Keystore & mots de passe en clair** dans le dépôt → à externaliser (secrets CI).
5. **Anti-doublon** : actuellement skip silencieux à l'enregistrement ; dialogue
   *Mettre à jour / Garder / Annuler* non implémenté.
6. **Extraction dépendante du HTML** de marchespublics : robustesse à éprouver sur
   plusieurs types de pages (résultats, ouverture des plis, multi-lots).
   Repli manuel + sélecteur de colonnes existants.
7. **Données historiques anciennes** sans ville/domaine → « non précisé ».
8. **Statistiques recalculées** à chaque ouverture d'écran (pas de cache/table
   dérivée) → OK à petite échelle, à indexer si gros volume.
9. **Tests** : seul `ReferenceCalculatorTest` existe → couvrir
   `LocalAnalyzer`, `CompanyStats`, `CompetitorEngine`.
10. **i18n** : tout en français en dur (pas de support arabe encore).
11. **Pas encore** : graphiques, exports enrichis des stats concurrents,
    multi-utilisateurs / base partagée (nécessiterait un backend + comptes).
12. **Permissions** : `INTERNET` uniquement ; `usesCleartextTraffic=true`
    (à revoir si plus nécessaire).

---

## 9. Questions utiles pour le diagnostic
- Faut-il publier sur **Play Store** ? (→ versionCode auto, keystore sécurisé,
  politique de confidentialité, target SDK récent).
- Besoin d'une **base partagée multi-utilisateurs** ? (→ backend Firebase/Supabase,
  comptes, synchro, paywall Premium).
- Volume d'historique attendu (→ besoin d'indexation / table dérivée).
- Couverture de tests souhaitée (unitaires extraction/stats, tests UI).

---

## 10. Build local (rappel)
```
./gradlew testDebugUnitTest
./gradlew assembleRelease   # APK signé : app/build/outputs/apk/release/
```
JDK 17 + Android SDK (platforms;android-34, build-tools;34.0.0).
