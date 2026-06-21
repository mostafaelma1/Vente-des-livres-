# RECO RESTAU · Gestion des salaires · تسيير الأجور 💼

Application Android **offline** pour gérer les **salaires** des employés de
**RECO RESTAU** (restauration collective & services traiteur) : fiches des
salariés, **pointage** mensuel, **calcul automatique** du salaire au prorata des
jours travaillés, et génération de l'**ordre de virement** pour la banque
(**Crédit Agricole – Jemâa Shaim**) en **PDF** et **Excel/CSV**, partageable par
WhatsApp ou e-mail.

> تطبيق أندرويد بدون أنترنت لتسيير أجور العمال: بطاقات الأجراء، التنقيط الشهري،
> الحساب التلقائي للأجر حسب أيام العمل، وإنشاء إذن بالتحويل البنكي بصيغة PDF
> و Excel قابل للمشاركة عبر واتساب أو البريد.

---

## ✨ Fonctionnalités · الميزات

- **Salariés / الأجراء** — nom & prénom, **poste**, **lieu de travail**,
  **téléphone**, **N° de carte nationale (CIN)**, **N° de compte / RIB**,
  **salaire mensuel** et type de virement (« Mise disposition » / « Virement »).
- **Pointage & Salaires / التنقيط** — pour chaque mois, on saisit les **jours
  travaillés** de chaque salarié ; le **salaire à payer** est recalculé en direct.
- **Calcul automatique** — `salaire journalier = salaire mensuel ÷ base (26 jours
  par défaut)` puis `× jours travaillés`. La **base** est paramétrable. Le mois
  courant est sélectionné automatiquement selon la date du jour.
- **Ordre de virement / إذن بالتحويل** — génère la liste pour la banque en
  **PDF** (avec logo, en-tête société, compte à débiter et tableau des salariés)
  et en **Excel / CSV**, puis **partage** (WhatsApp, e-mail, Drive…).
- **Compte de la société variable** — on enregistre un ou plusieurs **comptes**
  dans les Paramètres et on choisit le **compte actif** à débiter, modifiable à
  tout moment.
- **Tableau de bord** — nombre de salariés, **total des salaires du mois** et
  période en cours.

Tout est stocké **localement** sur l'appareil (SQLite / Room). Aucune connexion
internet, aucun compte.

---

## 🏗️ Stack technique

| Concern      | Choix                                       |
| ------------ | ------------------------------------------- |
| Langage      | Kotlin                                      |
| UI           | Android Views + Material 3 + ViewBinding    |
| Persistance  | Room (SQLite)                               |
| Documents    | `android.graphics.pdf.PdfDocument` + CSV    |
| Partage      | FileProvider (WhatsApp / e-mail / Drive)    |
| Async        | Kotlin Coroutines                           |
| Min / Target | Android 7.0 (API 24) / Android 14 (API 34)  |

---

## 🚀 Démarrer

1. Ouvrir le projet dans **Android Studio**. Depuis la ligne de commande, copier
   `local.properties.sample` vers `local.properties` et renseigner `sdk.dir`.
2. Construire :
   ```bash
   ./gradlew assembleDebug
   ```

Un **APK** est aussi construit automatiquement par GitHub Actions à chaque push
sur la branche de développement — voir l'onglet **Releases** pour le téléchargement direct.

### Première utilisation

1. **Paramètres** → vérifier la société, la banque (Crédit Agricole – Jemâa
   Shaim), la référence, la **base de jours** (26), puis **ajouter le compte**
   de la société à débiter et le marquer comme actif.
2. **Salariés** → ajouter les employés (salaire mensuel inclus).
3. **Pointage & Salaires** → saisir les jours travaillés du mois.
4. **Ordre de virement** → générer le **PDF** ou l'**Excel** et le partager.

---

## 📂 Structure du projet

```
app/src/main/java/com/ventelivres/app/
├── VenteApp.kt              # Application : base de données + paramètres
├── MainActivity.kt          # Tableau de bord + navigation
├── EmployeesActivity.kt     # Liste / ajout / édition des salariés
├── PayrollActivity.kt       # Pointage mensuel + calcul auto des salaires
├── VirementActivity.kt      # Ordre de virement (PDF / Excel / partage)
├── SettingsActivity.kt      # Société, banque, comptes (compte variable)
├── data/                    # Entités Room, DAO, base, paramètres
├── ui/                      # Adapters RecyclerView
└── util/                    # Format, calcul de paie, export de documents
```
