# B Marche — Backend Supabase (guide de mise en place)

Ce guide explique comment brancher l'application sur un serveur **Supabase**
(gratuit pour démarrer). Aucune compétence serveur n'est nécessaire.

## Modèle commercial
- **Free** : calcul du prix de référence + **statistiques locales** (sur le téléphone).
- **Premium (à vie)** : **statistiques globales** issues des analyses de tous les
  utilisateurs B Marche.
- **Toute analyse** (Free ou Premium) est envoyée au serveur pour alimenter les
  statistiques globales. Les **simulations privées** ne sont jamais envoyées.

## 1. Créer le projet Supabase
1. Va sur https://supabase.com → **Sign in** → **New project**.
2. Choisis un nom (ex. `bmarche`), un mot de passe de base de données, une région
   proche (Europe / Paris).
3. Attends ~2 min que le projet soit prêt.

## 2. Créer les tables et la logique
1. Menu de gauche → **SQL Editor** → **New query**.
2. Copie/colle **tout** le contenu de [`docs/supabase_schema.sql`](supabase_schema.sql).
3. Clique **Run**. (Tu dois voir « Success ».)

## 3. Récupérer les clés à mettre dans l'app
Menu **Project Settings → API** :
- **Project URL** → ex. `https://abcdxyz.supabase.co`
- **anon public** key → une longue chaîne `eyJ...`

> ⚠️ Utilise UNIQUEMENT la clé **anon public**. Ne mets JAMAIS la clé
> `service_role` dans l'application.

## 4. Renseigner les clés dans le build
Ces deux valeurs sont injectées à la compilation via des propriétés Gradle.

**En local** — ajoute dans `~/.gradle/gradle.properties` (ou
`gradle.properties` du projet) :
```
supabaseUrl=https://abcdxyz.supabase.co
supabaseAnonKey=eyJ........
```

**En CI (GitHub Actions)** — ajoute deux *secrets* de dépôt
(`Settings → Secrets and variables → Actions`) :
- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`

puis le workflow les passe au build :
```
-PsupabaseUrl=${{ secrets.SUPABASE_URL }} -PsupabaseAnonKey=${{ secrets.SUPABASE_ANON_KEY }}
```

> Si les clés sont vides, l'app fonctionne quand même **en mode local seul**
> (calcul + historique + stats locales) ; l'envoi serveur et le Premium sont
> simplement désactivés.

## 5. Te déclarer administrateur
1. Lance l'app, **inscris-toi** avec ton numéro (écran « Mon compte »).
2. Dans Supabase → **SQL Editor**, exécute (avec TON numéro) :
   ```sql
   update public.users set is_admin = true where phone = '+2126XXXXXXXX';
   ```
3. Rouvre « Mon compte » : l'accès **Admin** apparaît dans l'app.

## 6. Activer le Premium d'un client (paiement à vie)
Depuis le **panneau Admin** dans l'app : recherche le client par téléphone →
**Activer Premium** → choisis **À vie**. (Tu peux aussi choisir 1 / 3 / 12 mois.)

## Sécurité
- Toutes les tables ont la **RLS activée** : aucun accès direct.
- Tout passe par des fonctions **RPC SECURITY DEFINER** : la clé publique de
  l'app ne peut faire que ce que ces fonctions autorisent.
- Le blocage multi-appareil et la vérification Premium sont **côté serveur**.

## Confidentialité (texte affiché dans l'app)
> « Les analyses envoyées au serveur concernent les données des appels d'offres
> analysés afin d'améliorer les statistiques globales B Marche. Les données
> privées de simulation de l'utilisateur ne sont pas partagées. »
