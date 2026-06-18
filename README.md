# Vente Livres · بيع الكتب 📚

Application Android **offline** pour gérer la **vente de livres** (en gros et au
détail) : clients/librairies, catalogue de livres saisi manuellement, factures
avec **remise**, **paiements partiels** et suivi du **reste à payer**.

> تطبيق أندرويد بدون أنترنت لتسيير بيع الكتب: الزبناء، الكتب، الفواتير مع
> التخفيض، الأداء الجزئي، وتتبّع الباقي.

---

## ✨ Fonctionnalités · الميزات

- **Clients / الزبناء** — nom, téléphone, type **Gros** ou **Détail**, et une
  **remise par défaut (%)** appliquée automatiquement à leurs factures.
- **Livres / الكتب** — saisis manuellement avec un **prix de gros** et un **prix
  de détail**. Aucun import : vous entrez les titres et les prix vous-même.
- **Factures / الفواتير** — choisir un client, ajouter des livres (depuis le
  catalogue ou en saisie libre), appliquer la remise, voir le **sous-total**, la
  **remise**, le **total**.
- **Paiements partiels / الأداء الجزئي** — enregistrer plusieurs paiements
  (espèces, chèque, virement…). Le **Reste** (الباقي) est recalculé en direct.
- **Modifier une facture / تعديل الفاتورة** — rouvrir une facture pour ajouter
  un article, enregistrer un nouveau paiement ou corriger la remise. Le statut
  passe automatiquement **Impayée → Partielle → Payée**.
- **Partager la facture / مشاركة الفاتورة** — générer un texte de facture clair
  et l'envoyer par WhatsApp, SMS, e-mail, etc.
- **Tableau de bord / لوحة القيادة** — total du **reste à encaisser**, chiffre
  d'affaires et nombre de factures.

Tout est stocké **localement** sur l'appareil (SQLite / Room). Aucune connexion
internet, aucun compte.

---

## 🏗️ Stack technique

| Concern      | Choix                                       |
| ------------ | ------------------------------------------- |
| Langage      | Kotlin                                      |
| UI           | Android Views + Material 3 + ViewBinding    |
| Persistance  | Room (SQLite)                               |
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

---

## 📂 Structure du projet

```
app/src/main/java/com/ventelivres/app/
├── VenteApp.kt              # Application : accès à la base de données
├── MainActivity.kt          # Tableau de bord + navigation
├── ClientsActivity.kt       # Liste / ajout / édition des clients
├── BooksActivity.kt         # Liste / ajout / édition des livres
├── InvoicesActivity.kt      # Liste des factures + statut + reste
├── InvoiceEditActivity.kt   # Créer / modifier une facture, articles, paiements
├── data/                    # Entités Room, DAO, base de données
├── ui/                      # Adapters RecyclerView
└── util/Format.kt           # Formatage montant / date
```
