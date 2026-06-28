// Edge Function Supabase : déclenche le robot serveur (workflow GitHub Actions).
// Appelée par le bouton admin de l'app. Vérifie que l'appelant est admin
// (téléphone + appareil), puis lance le workflow via l'API GitHub.
//
// Déploiement (CLI) :
//   supabase functions deploy trigger-robot --no-verify-jwt
// Secrets de la fonction à définir :
//   supabase secrets set GH_TOKEN=...  GH_OWNER=mostafaelma1  GH_REPO=Vente-des-livres-  GH_REF=claude/moroccan-tender-analyzer-0szsuc
// (SUPABASE_URL et SUPABASE_SERVICE_ROLE_KEY sont fournis automatiquement.)

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

serve(async (req) => {
  if (req.method !== "POST") return json({ status: "method_not_allowed" }, 405);
  try {
    const { phone, device, target } = await req.json();
    const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
    const SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

    // Vérifie que l'appelant est un administrateur sur cet appareil.
    const r = await fetch(
      `${SUPA_URL}/rest/v1/users?select=is_admin,device_id,is_blocked&phone=eq.${encodeURIComponent(phone)}`,
      { headers: { apikey: SERVICE, Authorization: `Bearer ${SERVICE}` } },
    );
    const rows = await r.json();
    const u = rows?.[0];
    if (!u || !u.is_admin || u.is_blocked || u.device_id !== device) {
      return json({ status: "not_admin" }, 403);
    }

    // Déclenche le workflow GitHub.
    const token = Deno.env.get("GH_TOKEN")!;
    const owner = Deno.env.get("GH_OWNER")!;
    const repo = Deno.env.get("GH_REPO")!;
    const ref = Deno.env.get("GH_REF") || "main";
    const gh = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/actions/workflows/robot.yml/dispatches`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: "application/vnd.github+json",
          "User-Agent": "bmarche-robot",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ ref, inputs: { target: String(target || 10) } }),
      },
    );
    if (gh.status === 204) return json({ status: "ok" });
    return json({ status: "gh_error", code: gh.status, detail: await gh.text() }, 502);
  } catch (e) {
    return json({ status: "error", detail: String(e) }, 500);
  }
});
