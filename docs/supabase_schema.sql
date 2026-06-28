-- ============================================================================
--  B Marche — Schéma Supabase (PostgreSQL)
--  Modèle commercial :
--    Free    = calcul prix de référence + statistiques LOCALES (sur le téléphone)
--    Premium = statistiques GLOBALES (analyses de tous les utilisateurs)
--
--  Sécurité : RLS activée sur toutes les tables, AUCUN accès direct.
--  Tout passe par des fonctions RPC SECURITY DEFINER (logique contrôlée côté
--  serveur). La clé "anon" publique de l'app ne peut appeler que ces fonctions.
--
--  À exécuter dans :  Supabase > SQL Editor > New query > Run.
-- ============================================================================

create extension if not exists "pgcrypto";   -- gen_random_uuid()

-- ----------------------------------------------------------------------------
-- 1. TABLES
-- ----------------------------------------------------------------------------

-- Comptes utilisateurs (inscription par téléphone, sans SMS).
create table if not exists public.users (
    id              uuid primary key default gen_random_uuid(),
    phone           text unique not null,
    name            text not null,                 -- nom ou société
    ville           text,
    domaine         text,                          -- domaine principal (optionnel)
    plan            text not null default 'free'   check (plan in ('free','premium')),
    premium_start   timestamptz,
    premium_expiry  timestamptz,                   -- NULL = Premium à vie
    device_id       text,                          -- téléphone activé (blocage multi-appareil)
    is_admin        boolean not null default false,
    is_blocked      boolean not null default false,
    analyses_count  integer not null default 0,
    created_at      timestamptz not null default now(),
    last_activity   timestamptz not null default now()
);

-- Appels d'offres analysés, DÉDUPLIQUÉS (1 AO = 1 ligne, peu importe le nombre
-- d'utilisateurs qui l'ont analysé). Alimente les statistiques globales.
create table if not exists public.tenders (
    id                 uuid primary key default gen_random_uuid(),
    dedup_key          text unique not null,       -- hash(reference|acheteur|ville|date_limite|estimation)
    reference          text,
    objet              text,
    acheteur           text,
    ville              text,
    categorie          text,                        -- Travaux / Services / Fournitures
    domaine            text,
    estimation         numeric,
    reference_price    numeric,
    date_limite        text,
    participants       jsonb,                        -- [{ "name":..., "amount":..., "rank":... }]
    analyzed_by_count  integer not null default 1,   -- nb d'utilisateurs ayant analysé cet AO
    completeness       integer not null default 0,   -- score de complétude (pour décider la mise à jour)
    first_analysis_at  timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

create index if not exists tenders_categorie_idx on public.tenders (categorie);
create index if not exists tenders_domaine_idx   on public.tenders (domaine);
create index if not exists tenders_ville_idx     on public.tenders (ville);

-- Journal des soumissions (traçabilité ; qui a envoyé quoi). Les données
-- privées de simulation NE sont JAMAIS envoyées ici.
create table if not exists public.analyses (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid references public.users(id) on delete set null,
    device_id   text,
    tender_id   uuid references public.tenders(id) on delete cascade,
    dedup_key   text,
    source      text,                              -- 'auto' | 'manuel'
    payload     jsonb,
    created_at  timestamptz not null default now()
);

-- RLS : on bloque tout accès direct ; seules les fonctions RPC ci-dessous
-- (SECURITY DEFINER) peuvent lire/écrire.
alter table public.users    enable row level security;
alter table public.tenders  enable row level security;
alter table public.analyses enable row level security;

-- ----------------------------------------------------------------------------
-- 2. INSCRIPTION / CONNEXION + BLOCAGE MULTI-APPAREIL
-- ----------------------------------------------------------------------------
create or replace function public.register_or_login(
    p_phone text, p_name text, p_ville text, p_domaine text, p_device_id text
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u public.users;
begin
    select * into u from public.users where phone = p_phone;

    if not found then
        insert into public.users(phone, name, ville, domaine, device_id)
        values (p_phone, p_name, p_ville, p_domaine, p_device_id)
        returning * into u;
        return jsonb_build_object('status','ok','user', to_jsonb(u));
    end if;

    if u.is_blocked then
        return jsonb_build_object('status','blocked_account');
    end if;

    -- Pas encore d'appareil enregistré → on lie l'appareil actuel.
    if u.device_id is null then
        update public.users
           set device_id = p_device_id,
               name      = coalesce(nullif(p_name,''),    name),
               ville     = coalesce(nullif(p_ville,''),   ville),
               domaine   = coalesce(nullif(p_domaine,''), domaine),
               last_activity = now()
         where id = u.id returning * into u;
        return jsonb_build_object('status','ok','user', to_jsonb(u));
    end if;

    -- Même appareil → autorisé.
    if u.device_id = p_device_id then
        update public.users set last_activity = now() where id = u.id returning * into u;
        return jsonb_build_object('status','ok','user', to_jsonb(u));
    end if;

    -- Appareil différent → bloqué.
    return jsonb_build_object('status','blocked_device');
end; $$;

-- Profil + statut Premium à jour (vérifié au lancement / à l'ouverture du Premium).
create or replace function public.get_profile(
    p_user_id uuid, p_device_id text
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u public.users;
begin
    select * into u from public.users where id = p_user_id;
    if not found then return jsonb_build_object('status','not_found'); end if;
    if u.is_blocked then return jsonb_build_object('status','blocked_account'); end if;
    if u.device_id is not null and u.device_id <> p_device_id then
        return jsonb_build_object('status','blocked_device');
    end if;
    -- Premium expiré → rétrograde en free (sauf à vie = expiry NULL).
    if u.plan = 'premium' and u.premium_expiry is not null and u.premium_expiry < now() then
        update public.users set plan='free' where id=u.id returning * into u;
    end if;
    update public.users set last_activity = now() where id = u.id;
    return jsonb_build_object('status','ok','user', to_jsonb(u));
end; $$;

-- ----------------------------------------------------------------------------
-- 3. ENVOI D'ANALYSE + ANTI-DOUBLON
-- ----------------------------------------------------------------------------
create or replace function public.submit_analysis(
    p_user_id uuid, p_device_id text, p_dedup_key text, p_source text,
    p_reference text, p_objet text, p_acheteur text, p_ville text,
    p_categorie text, p_domaine text, p_estimation numeric, p_reference_price numeric,
    p_date_limite text, p_participants jsonb, p_completeness integer, p_payload jsonb
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare t public.tenders; v_new boolean := false;
begin
    -- Garde-fou : appareil de l'utilisateur cohérent.
    if not exists (select 1 from public.users
                   where id = p_user_id and not is_blocked
                     and (device_id is null or device_id = p_device_id)) then
        return jsonb_build_object('status','rejected');
    end if;

    select * into t from public.tenders where dedup_key = p_dedup_key;

    if not found then
        insert into public.tenders(dedup_key, reference, objet, acheteur, ville,
            categorie, domaine, estimation, reference_price, date_limite,
            participants, analyzed_by_count, completeness)
        values (p_dedup_key, p_reference, p_objet, p_acheteur, p_ville,
            p_categorie, p_domaine, p_estimation, p_reference_price, p_date_limite,
            p_participants, 1, coalesce(p_completeness,0))
        returning * into t;
        v_new := true;
    else
        -- Doublon : on incrémente le compteur, et on enrichit SEULEMENT si plus complet.
        update public.tenders set
            analyzed_by_count = analyzed_by_count + 1,
            participants    = case when coalesce(p_completeness,0) > completeness then p_participants    else participants    end,
            reference_price = case when coalesce(p_completeness,0) > completeness then p_reference_price else reference_price end,
            estimation      = coalesce(estimation, p_estimation),
            objet           = coalesce(nullif(objet,''), p_objet),
            domaine         = coalesce(nullif(domaine,''), p_domaine),
            categorie       = coalesce(nullif(categorie,''), p_categorie),
            completeness    = greatest(completeness, coalesce(p_completeness,0)),
            updated_at      = now()
        where id = t.id returning * into t;
    end if;

    insert into public.analyses(user_id, device_id, tender_id, dedup_key, source, payload)
    values (p_user_id, p_device_id, t.id, p_dedup_key, p_source, p_payload);

    update public.users set analyses_count = analyses_count + 1, last_activity = now()
     where id = p_user_id;

    return jsonb_build_object('status','ok','tender_id', t.id,
                              'is_new', v_new, 'analyzed_by_count', t.analyzed_by_count);
end; $$;

-- ----------------------------------------------------------------------------
-- 4. ADMIN (vérification par téléphone + appareil d'un compte is_admin=true)
-- ----------------------------------------------------------------------------
create or replace function public._is_admin(p_phone text, p_device text)
returns boolean language sql security definer set search_path = public as $$
    select exists(select 1 from public.users
                  where phone = p_phone and device_id = p_device
                    and is_admin = true and not is_blocked);
$$;

create or replace function public.admin_list_users(
    p_admin_phone text, p_admin_device text, p_search text default null
) returns setof public.users
language plpgsql security definer set search_path = public as $$
begin
    if not public._is_admin(p_admin_phone, p_admin_device) then
        raise exception 'not_admin';
    end if;
    return query
        select * from public.users
        where p_search is null or p_search = ''
           or phone ilike '%'||p_search||'%'
           or name  ilike '%'||p_search||'%'
        order by last_activity desc;
end; $$;

-- Active Premium. p_months = NULL  → Premium à vie (paiement à vie).
--                p_months = 1/3/12 → durée en mois.
create or replace function public.admin_set_premium(
    p_admin_phone text, p_admin_device text, p_user_id uuid, p_months integer default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u public.users;
begin
    if not public._is_admin(p_admin_phone, p_admin_device) then raise exception 'not_admin'; end if;
    update public.users set
        plan = 'premium',
        premium_start = now(),
        premium_expiry = case when p_months is null then null else now() + (p_months || ' months')::interval end
    where id = p_user_id returning * into u;
    return jsonb_build_object('status','ok','user', to_jsonb(u));
end; $$;

create or replace function public.admin_disable_premium(
    p_admin_phone text, p_admin_device text, p_user_id uuid
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u public.users;
begin
    if not public._is_admin(p_admin_phone, p_admin_device) then raise exception 'not_admin'; end if;
    update public.users set plan='free', premium_expiry = now()
    where id = p_user_id returning * into u;
    return jsonb_build_object('status','ok','user', to_jsonb(u));
end; $$;

-- Réinitialise l'appareil (changement de téléphone).
create or replace function public.admin_reset_device(
    p_admin_phone text, p_admin_device text, p_user_id uuid
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u public.users;
begin
    if not public._is_admin(p_admin_phone, p_admin_device) then raise exception 'not_admin'; end if;
    update public.users set device_id = null where id = p_user_id returning * into u;
    return jsonb_build_object('status','ok','user', to_jsonb(u));
end; $$;

create or replace function public.admin_set_blocked(
    p_admin_phone text, p_admin_device text, p_user_id uuid, p_blocked boolean
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare u public.users;
begin
    if not public._is_admin(p_admin_phone, p_admin_device) then raise exception 'not_admin'; end if;
    update public.users set is_blocked = p_blocked where id = p_user_id returning * into u;
    return jsonb_build_object('status','ok','user', to_jsonb(u));
end; $$;

-- ----------------------------------------------------------------------------
-- 5. STATISTIQUES GLOBALES — PREMIUM uniquement
--    (vérifie le statut premium avant de renvoyer quoi que ce soit)
-- ----------------------------------------------------------------------------
create or replace function public._require_premium(p_user_id uuid, p_device text)
returns boolean language sql security definer set search_path = public as $$
    select exists(select 1 from public.users
                  where id = p_user_id and device_id = p_device and not is_blocked
                    and plan = 'premium'
                    and (premium_expiry is null or premium_expiry > now()));
$$;

-- Top concurrents par domaine (exemple ; affine en Phase 2).
create or replace function public.global_top_competitors(
    p_user_id uuid, p_device text, p_categorie text default null,
    p_domaine text default null, p_ville text default null, p_limit integer default 20
) returns table(name text, participations bigint, avg_rank numeric)
language plpgsql security definer set search_path = public as $$
begin
    if not public._require_premium(p_user_id, p_device) then raise exception 'premium_required'; end if;
    return query
        select p.name,
               count(*) as participations,
               avg((p.rank)::numeric) as avg_rank
        from public.tenders t
        cross join lateral jsonb_to_recordset(t.participants)
                   as p(name text, amount numeric, rank int)
        where (p_categorie is null or t.categorie = p_categorie)
          and (p_domaine   is null or t.domaine   = p_domaine)
          and (p_ville     is null or t.ville      = p_ville)
        group by p.name
        order by participations desc
        limit p_limit;
end; $$;

-- ----------------------------------------------------------------------------
-- 6. DROITS : la clé "anon" ne peut appeler QUE ces fonctions.
-- ----------------------------------------------------------------------------
grant execute on function public.register_or_login(text,text,text,text,text)         to anon, authenticated;
grant execute on function public.get_profile(uuid,text)                              to anon, authenticated;
grant execute on function public.submit_analysis(uuid,text,text,text,text,text,text,text,text,text,numeric,numeric,text,jsonb,integer,jsonb) to anon, authenticated;
grant execute on function public.admin_list_users(text,text,text)                    to anon, authenticated;
grant execute on function public.admin_set_premium(text,text,uuid,integer)           to anon, authenticated;
grant execute on function public.admin_disable_premium(text,text,uuid)               to anon, authenticated;
grant execute on function public.admin_reset_device(text,text,uuid)                  to anon, authenticated;
grant execute on function public.admin_set_blocked(text,text,uuid,boolean)           to anon, authenticated;
grant execute on function public.global_top_competitors(uuid,text,text,text,text,integer) to anon, authenticated;

-- ----------------------------------------------------------------------------
-- 7bis. STATISTIQUES GLOBALES PREMIUM — Phase 2
--       (toutes vérifient _require_premium avant de renvoyer des données)
-- ----------------------------------------------------------------------------

-- Profils des concurrents (alimente Top / Agressives / Stratégiques / Alertes).
create or replace function public.global_profiles(
    p_user_id uuid, p_device text, p_categorie text default null,
    p_domaine text default null, p_ville text default null, p_limit integer default 40
) returns table(name text, participations bigint, avg_rank numeric,
                avg_ecart numeric, pct_low numeric, pct_close numeric)
language plpgsql security definer set search_path = public as $$
begin
    if not public._require_premium(p_user_id, p_device) then raise exception 'premium_required'; end if;
    return query
        with e as (
            select p.name, p.rank,
                   case when t.reference_price is not null and t.reference_price > 0
                        then (p.amount - t.reference_price) / t.reference_price * 100 end as ecart
            from public.tenders t
            cross join lateral jsonb_to_recordset(t.participants)
                       as p(name text, amount numeric, rank int)
            where (p_categorie is null or t.categorie = p_categorie)
              and (p_domaine   is null or t.domaine   = p_domaine)
              and (p_ville     is null or t.ville      = p_ville)
              and p.name is not null and p.name <> ''
        )
        select name,
               count(*)::bigint as participations,
               avg(rank::numeric) as avg_rank,
               avg(ecart) as avg_ecart,
               100.0 * count(*) filter (where ecart < -10) / count(*) as pct_low,
               100.0 * count(*) filter (where ecart between -3 and 3) / count(*) as pct_close
        from e
        group by name
        order by participations desc
        limit p_limit;
end; $$;

-- Indice de concurrence : nb d'AO + nb moyen de participants + estimation moyenne.
create or replace function public.global_competition_index(
    p_user_id uuid, p_device text, p_categorie text default null,
    p_domaine text default null, p_ville text default null
) returns table(nb_tenders bigint, avg_participants numeric, avg_estimation numeric)
language plpgsql security definer set search_path = public as $$
begin
    if not public._require_premium(p_user_id, p_device) then raise exception 'premium_required'; end if;
    return query
        select count(*)::bigint,
               avg(jsonb_array_length(coalesce(participants, '[]'::jsonb)))::numeric,
               avg(estimation)::numeric
        from public.tenders t
        where (p_categorie is null or t.categorie = p_categorie)
          and (p_domaine   is null or t.domaine   = p_domaine)
          and (p_ville     is null or t.ville      = p_ville);
end; $$;

-- Tendances par ville (nb d'AO + estimation moyenne).
create or replace function public.global_trends(
    p_user_id uuid, p_device text, p_categorie text default null,
    p_domaine text default null, p_limit integer default 30
) returns table(ville text, nb_tenders bigint, avg_estimation numeric)
language plpgsql security definer set search_path = public as $$
begin
    if not public._require_premium(p_user_id, p_device) then raise exception 'premium_required'; end if;
    return query
        select coalesce(nullif(ville,''), '(non précisé)') as ville,
               count(*)::bigint, avg(estimation)::numeric
        from public.tenders t
        where (p_categorie is null or t.categorie = p_categorie)
          and (p_domaine   is null or t.domaine   = p_domaine)
        group by 1 order by 2 desc limit p_limit;
end; $$;

-- Comparaison de mon offre avec le marché global.
create or replace function public.global_compare_offer(
    p_user_id uuid, p_device text, p_categorie text, p_domaine text,
    p_ville text, p_amount numeric
) returns table(nb_offres bigint, avg_ref numeric, avg_amount numeric,
                min_amount numeric, max_amount numeric, pct_above_me numeric)
language plpgsql security definer set search_path = public as $$
begin
    if not public._require_premium(p_user_id, p_device) then raise exception 'premium_required'; end if;
    return query
        with parts as (
            select p.amount, t.reference_price as ref
            from public.tenders t
            cross join lateral jsonb_to_recordset(t.participants)
                       as p(name text, amount numeric, rank int)
            where (p_categorie is null or t.categorie = p_categorie)
              and (p_domaine   is null or t.domaine   = p_domaine)
              and (p_ville     is null or t.ville      = p_ville)
              and p.amount is not null
        )
        select count(*)::bigint, avg(ref)::numeric, avg(amount)::numeric,
               min(amount)::numeric, max(amount)::numeric,
               case when count(*) > 0
                    then 100.0 * count(*) filter (where amount > p_amount) / count(*) end
        from parts;
end; $$;

grant execute on function public.global_profiles(uuid,text,text,text,text,integer)         to anon, authenticated;
grant execute on function public.global_competition_index(uuid,text,text,text,text)        to anon, authenticated;
grant execute on function public.global_trends(uuid,text,text,text,integer)                to anon, authenticated;
grant execute on function public.global_compare_offer(uuid,text,text,text,text,numeric)    to anon, authenticated;

-- ----------------------------------------------------------------------------
-- 7ter. PURGE AUTOMATIQUE — suppression des données de plus de 2 mois
--       (les statistiques ne gardent qu'une fenêtre glissante de 2 mois)
-- ----------------------------------------------------------------------------
create extension if not exists pg_cron;

create or replace function public.purge_old_data() returns void
language plpgsql security definer set search_path = public as $$
begin
    -- Journal des soumissions de plus de 2 mois.
    delete from public.analyses where created_at < now() - interval '2 months';
    -- Appels d'offres enregistrés il y a plus de 2 mois (supprime aussi leurs
    -- analyses liées via ON DELETE CASCADE).
    delete from public.tenders  where first_analysis_at < now() - interval '2 months';
end; $$;

-- Planifie la purge tous les jours à 03:00 UTC (remplace l'ancienne si elle existe).
do $$
begin
    perform cron.unschedule('bmarche_purge_2m');
exception when others then null;
end $$;
select cron.schedule('bmarche_purge_2m', '0 3 * * *', $$ select public.purge_old_data(); $$);

-- ----------------------------------------------------------------------------
-- 7. CRÉER LE PREMIER ADMIN
--    Après ta 1re inscription dans l'app, récupère ton téléphone et exécute :
--      update public.users set is_admin = true where phone = '+2126XXXXXXXX';
-- ----------------------------------------------------------------------------
