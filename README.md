# PrixRef AO Maroc

Application Android d'aide à l'analyse financière des appels d'offres publics au
Maroc. Elle calcule le **prix de référence**, compare les offres des concurrents,
les classe selon leur proximité avec le prix de référence et génère un **rapport
PDF** professionnel.

> Cet outil fournit une estimation analytique interne. Il ne remplace pas les
> décisions officielles des commissions d'appel d'offres ni les documents
> réglementaires. L'application n'est pas affiliée à marchespublics.gov.ma.

## Fonctionnalités

- **Mode manuel (hors ligne)** — saisie des informations de l'AO, de l'estimation
  et des offres des concurrents (statut retenue / écartée).
- **Calcul du prix de référence**
  - Moyenne des offres retenues = somme des offres retenues / nombre des offres retenues
  - Prix de référence = (estimation maître d'ouvrage + moyenne des offres retenues) / 2
  - Écart (DH) = | offre − prix de référence | ; Écart (%) = écart / prix de référence × 100
  - Classement par écart croissant ; gagnant probable = offre la plus proche
- **Observations automatiques** : Très proche (≤ 1 %), Proche (≤ 3 %), Moyen (≤ 7 %), Éloigné (> 7 %)
- **Analyse de risque** : offre anormalement basse / excessive / bonne position.
  Seuil d'alerte ±25 % (Travaux), ±20 % (Fournitures et Services).
- **Rapport PDF** professionnel, partageable (WhatsApp, Gmail, …).
- **Export Excel** (CSV compatible Excel, séparateur `;`, encodage UTF-8 avec BOM).
- **Historique** local (Room/SQLite) : recherche, ouverture, suppression,
  régénération du PDF.
- **Simulation avant dépôt** : position probable et conseils selon l'offre saisie.
- **Analyse par URL (bêta)** : extraction de `refConsultation` et `orgAcronyme`
  d'un lien `marchespublics.gov.ma`, puis bascule sur le mode manuel.

## Pile technique

- Kotlin, Android (minSdk 26 / Android 8+, targetSdk 34)
- Architecture View + ViewBinding, Material Design 3
- Room (SQLite) pour l'historique, Gson pour la sérialisation JSON
- `android.graphics.pdf.PdfDocument` pour le PDF (aucune dépendance lourde)
- Coroutines pour les accès base hors du thread UI

## Structure du projet

```
app/src/main/java/com/prixref/ao/
├── MainActivity.kt              # Accueil
├── ManualAnalysisActivity.kt    # Saisie manuelle
├── ResultActivity.kt            # Résultats + PDF/Excel/enregistrement
├── HistoryActivity.kt           # Historique
├── UrlAnalysisActivity.kt       # Extraction depuis URL (bêta)
├── SimulationActivity.kt        # Simulation avant dépôt
├── AboutActivity.kt             # À propos
├── calc/
│   ├── ReferenceCalculator.kt   # Moteur de calcul (pur Kotlin, testé)
│   └── UrlParser.kt             # parseMarchesPublicsUrl(url)
├── model/Models.kt              # Modèles de données
├── data/                        # Room (Entity, Dao, Database) + JsonStore
├── pdf/PdfReportGenerator.kt    # Génération du rapport PDF
├── export/CsvExporter.kt        # Export Excel (CSV)
├── ui/HistoryAdapter.kt         # Adaptateur RecyclerView
└── util/                        # Format (montants/dates) + Sharing (FileProvider)

app/src/test/java/com/prixref/ao/
└── ReferenceCalculatorTest.kt   # Tests unitaires du moteur de calcul
```

## Compilation

Prérequis : Android Studio (Iguana ou plus récent) ou le SDK Android en ligne de
commande, JDK 17.

1. Copier `local.properties.sample` vers `local.properties` et y indiquer le
   chemin du SDK (`sdk.dir=...`). Android Studio le fait automatiquement.
2. Construire le debug APK :

   ```bash
   ./gradlew assembleDebug
   ```

   L'APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

3. APK de release (non signé) :

   ```bash
   ./gradlew assembleRelease
   ```

4. Lancer les tests unitaires :

   ```bash
   ./gradlew testDebugUnitTest
   ```

## Installation sur un appareil

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Priorité de la version actuelle (v1)

Conformément au cahier des charges, la première version fonctionne **entièrement
hors ligne** : mode manuel, calcul du prix de référence, classement, historique
et export PDF/Excel. L'extraction automatique depuis marchespublics.gov.ma reste
une fonctionnalité **bêta** (extraction des paramètres d'URL uniquement) afin de
ne jamais bloquer l'application si le portail n'est pas lisible automatiquement.
