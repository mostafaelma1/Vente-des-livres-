# Guide de publication sur Google Play — B Marche

Ce dossier contient les éléments pour publier l'application.

## Contenu du dossier
- `icon_512.png` — icône de l'app (512×512), à téléverser dans la fiche Play.
- `feature_graphic_1024x500.png` — bannière « Feature graphic » (obligatoire).
- `listing_fr.txt` — titre, description courte et complète (Français).
- `listing_ar.txt` — idem en Arabe (ajouter l'arabe comme langue de la fiche).
- `privacy_policy_fr.md` / `privacy_policy_ar.md` — politique de confidentialité.
- `screenshots/` — où placer tes captures d'écran (voir plus bas).
- (Le fichier d'application **.aab signé** est produit par la chaîne de build — voir §4.)

## 1. Compte développeur
- Crée un compte sur https://play.google.com/console (frais uniques ~25 USD).

## 2. Créer l'application
- Play Console → **Créer une application** → nom, langue par défaut **Français**, type **Application**, **Gratuite**.

## 3. Fiche du store (Présence sur le store → Fiche principale)
- **Nom**, **Description courte**, **Description complète** : copie depuis `listing_fr.txt`.
- **Icône** : `icon_512.png`. **Feature graphic** : `feature_graphic_1024x500.png`.
- **Captures d'écran** : voir `screenshots/`.
- Ajoute une 2ᵉ langue **العربية** et colle `listing_ar.txt`.

## 4. Le fichier d'application (.aab signé)
Google Play exige un **Android App Bundle (.aab)**, pas un APK.
- La chaîne de build GitHub Actions produit désormais **`BMarche-release.aab`** (signé avec la clé stable) en plus de l'APK. Récupère-le dans la **Release** GitHub la plus récente.
- Dans Play Console → **Production → Créer une version** → téléverse le `.aab`.
- Active **Play App Signing** (recommandé) : ta clé sert de clé de téléversement.

## 5. Confidentialité & conformité (obligatoire)
- **Politique de confidentialité** : héberge `privacy_policy_fr.md` (ex. page web, GitHub Pages, Google Site) et colle l'URL dans Play Console → Règles de l'application.
- **Sécurité des données** : déclare les données collectées (téléphone, nom, ville, identifiant d'appareil) et leur usage ; aucune vente de données.
- **Classification du contenu** : remplis le questionnaire (l'app convient à tous publics).
- **Public cible** : adultes / professionnels.
- **Application non officielle** : précise dans la description qu'elle n'est pas affiliée à marchespublics.gov.ma (déjà inclus).

## 6. Catégorie
- Suggérée : **Entreprise** (ou Productivité).

## 7. Publier
- Renseigne toutes les sections requises (coches vertes) → **Envoyer pour examen**.
- L'examen prend généralement de quelques heures à quelques jours.

## Remarque
B Marche utilise des données du portail public marchespublics.gov.ma. Garde le **disclaimer** (résultats indicatifs, non affilié) bien visible — c'est important pour l'acceptation.
