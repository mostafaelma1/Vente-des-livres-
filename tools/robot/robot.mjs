// ============================================================================
//  Robot serveur B Marche — analyse automatique des consultations
//  marchespublics.gov.ma (Playwright/Chromium) et écriture dans Supabase.
//
//  Tourne sur GitHub Actions (aucun téléphone). Réutilise les mêmes scripts
//  d'extraction que l'application Android, et porte la logique d'analyse
//  (LocalAnalyzer + ReferenceCalculator) en JavaScript.
//
//  Variables d'environnement requises :
//    SUPABASE_URL          ex. https://xxxx.supabase.co
//    SUPABASE_SERVICE_KEY  clé service_role (écriture serveur, NE PAS exposer)
//    ROBOT_TARGET          (optionnel) nb d'analyses à envoyer (défaut 10)
// ============================================================================

import { chromium } from 'playwright';
import crypto from 'node:crypto';

const SUPA_URL = (process.env.SUPABASE_URL || '').replace(/\/+$/, '');
const SUPA_KEY = process.env.SUPABASE_SERVICE_KEY || '';
const TARGET = parseInt(process.env.ROBOT_TARGET || '10', 10);
const DAYS_MIN = 3, DAYS_MAX = 8, WINDOW = 2;
const SEARCH_URL = 'https://www.marchespublics.gov.ma/index.php?page=entreprise.EntrepriseAdvancedSearch&searchAnnCons';
const BASE = 'https://www.marchespublics.gov.ma/index.php?page=entreprise.SuiviConsultation';

if (!SUPA_URL || !SUPA_KEY) { console.error('SUPABASE_URL / SUPABASE_SERVICE_KEY manquants'); process.exit(1); }

const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

// ---------- Supabase REST (service_role) ----------
async function supaGet(path) {
  const r = await fetch(`${SUPA_URL}/rest/v1/${path}`, {
    headers: { apikey: SUPA_KEY, Authorization: `Bearer ${SUPA_KEY}` },
  });
  if (!r.ok) { log('GET', path, r.status); return []; }
  return r.json();
}
async function supaRpc(fn, body) {
  const r = await fetch(`${SUPA_URL}/rest/v1/rpc/${fn}`, {
    method: 'POST',
    headers: { apikey: SUPA_KEY, Authorization: `Bearer ${SUPA_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return r.ok;
}

// ---------- Port de l'extraction (LocalAnalyzer) ----------
const FOLD = { 'à':'a','â':'a','ä':'a','á':'a','ã':'a','å':'a','é':'e','è':'e','ê':'e','ë':'e','î':'i','ï':'i','í':'i','ì':'i','ô':'o','ö':'o','ò':'o','ó':'o','õ':'o','û':'u','ü':'u','ù':'u','ú':'u','ç':'c','ñ':'n','’':"'",'‘':"'",'`':"'",'´':"'",'ʼ':"'",' ':' ',' ':' ' };
const fold = (s) => { let o = ''; for (const c of (s || '')) { const lc = c.toLowerCase(); o += (FOLD[c] || FOLD[lc] || lc); } return o; };
const clean = (s) => (s || '').replace(/\s+/g, ' ').trim().slice(0, 400);

const ALL_LABELS = ["date et heure limite de remise des plis","date limite de remise des plis","remise des plis","date limite","référence","reference","objet","acheteur public","maître d'ouvrage","type d'annonce","procédure","procedure","catégorie principale","categorie principale","réservé à","reserve a","lieu d'exécution","lieu d'execution","estimation (dhs ttc)","estimation","domaines d'activité","domaines d'activite","domaine d'activité","domaine d'activite","adresse de retrait","adresse de dépôt","adresse de depot","lieu d'ouverture des plis","lieu d'ouverture","prix d'acquisition des plans","prix d'acquisition","caution provisoire","cautionnement","agréments","agrements","qualifications","qualification"].map(fold);
const L = {
  reference: ["référence","reference","n° de consultation","numéro de consultation","n° consultation"],
  objet: ["objet"],
  acheteur: ["acheteur public","maître d'ouvrage","maitre d'ouvrage","acheteur","administration","service contractant"],
  lieu: ["lieu d'exécution","lieu d'execution","lieu de réalisation","lieu de prestation"],
  estimation: ["estimation (dhs ttc)","estimation (dh ttc)","estimation","montant estimé","coût estimatif"],
  categorie: ["catégorie principale","categorie principale","catégorie","categorie"],
  domaine: ["domaines d'activité","domaines d'activite","domaine d'activité","domaine d'activite"],
  dateLimite: ["date et heure limite de remise des plis","date limite de remise des plis","date limite des plis","limite de remise des plis","date limite"],
};
const K_NAME = ["entreprise","société","societe","soumissionnaire","concurrent","raison sociale","attributaire","candidat","fournisseur"];
const K_AMOUNT = ["montant","offre","prix","proposé","propose","ttc","ht"];
const K_STATUS = ["statut","état","etat","observation","décision","decision","résultat","resultat"];
const K_STATUS_OUT = ["écart","ecart","rejet","rejeté","rejete","exclu","non admis","non retenu","éliminé","elimine","irrecevable","hors délai"];
const NON_NAME = new Set(["admissible","admis","inadmissible","non admis","retenu","retenue","non retenu","non retenue","écartée","ecartee","écarté","ecarte","conforme","non conforme","rejeté","rejete","qualifié","qualifie","accepté","accepte","refusé","refuse","recevable","irrecevable","valide","invalide","oui","non","-","—"]);

function parseAmount(text) {
  if (!text) return null;
  const t = text.replace(/(dirhams?|dhs?|mad)/ig, ' ');
  const m = t.match(/\d[\d\s  .,]*\d|\d/);
  if (!m) return null;
  let tok = m[0].replace(/[\s  ]/g, '');
  if (tok.includes(',') && tok.includes('.')) tok = tok.replace(/\./g, '').replace(',', '.');
  else if (tok.includes(',')) tok = tok.replace(',', '.');
  else if (/^\d{1,3}(\.\d{3})+$/.test(tok)) tok = tok.replace(/\./g, '');
  const n = parseFloat(tok);
  return isNaN(n) ? null : n;
}
const looksLikeAmount = (t) => (t.replace(/\D/g, '').length >= 4) && parseAmount(t) != null;
const isStatusWord = (t) => { const x = clean(t).toLowerCase(); return x && NON_NAME.has(x); };
const isExcluded = (t) => { const x = (t || '').toLowerCase(); return K_STATUS_OUT.some((k) => x.includes(k)); };

function pickName(row, nameCol) {
  const byCol = (nameCol >= 0 && nameCol < row.length) ? (row[nameCol] || '').trim() : '';
  if (byCol.length >= 2 && !looksLikeAmount(byCol) && !isStatusWord(byCol)) return clean(byCol);
  const cands = row.filter((c) => (c || '').trim().length >= 2 && !looksLikeAmount(c) && !isStatusWord(c));
  cands.sort((a, b) => b.trim().length - a.trim().length);
  return cands.length ? clean(cands[0]) : '';
}

function labelValue(page, labels) {
  const fl = labels.map(fold);
  for (const table of page.tables) {
    for (const row of table.rows) {
      if (row.length >= 2) {
        const key = fold(clean(row[0]));
        if (fl.some((x) => key === x || key.startsWith(x) || key.includes(x))) {
          const value = clean(row[row.length - 1]);
          if (value && fold(value) !== key) return value;
        }
      }
    }
  }
  return valueAfterLabel(page.rawText || '', fl);
}
function valueAfterLabel(text, fl) {
  const folded = fold(text);
  for (const f of fl) {
    const idx = folded.indexOf(f);
    if (idx < 0) continue;
    const start = idx + f.length;
    if (start >= text.length) continue;
    let end = text.length;
    for (const stop of ALL_LABELS) {
      const p = folded.indexOf(stop, start + 1);
      if (p > start && p < end) end = p;
    }
    const value = clean(text.substring(start, end)).replace(/^[:\-.\s]+|[:\-.\s]+$/g, '');
    if (value.length >= 2) return value.slice(0, 300);
  }
  return '';
}
function parseDomaine(text) {
  if (!text) return '';
  const segs = text.split('/').map((s) => s.trim()).filter((s) => s.length >= 2);
  let r = '';
  if (segs.length >= 2) r = segs[1];
  else if (segs.length === 1) r = segs[0].replace(/^\d+\s+/, '').trim();
  return r.slice(0, 160);
}
function mapType(cat) {
  const c = (cat || '').toLowerCase();
  if (c.includes('travaux')) return 'Travaux';
  if (c.includes('service')) return 'Services';
  return 'Fournitures';
}
function extractOffers(table, nameCol, amountCol, statusCol) {
  const offers = [];
  for (const row of table.rows) {
    let amount = (amountCol >= 0 && amountCol < row.length) ? parseAmount(row[amountCol]) : null;
    if (!amount || amount <= 0) { const c = row.find(looksLikeAmount); amount = c ? parseAmount(c) : null; }
    if (!amount || amount <= 0) continue;
    const name = pickName(row, nameCol);
    if (!name) continue;
    const statusText = (statusCol >= 0 && statusCol < row.length) ? row[statusCol] : row.join(' ');
    offers.push({ name, amount, retained: !isExcluded(statusText) });
  }
  return offers;
}
function analyzePage(page, fallbackRef) {
  const reference = labelValue(page, L.reference) || fallbackRef;
  const objet = labelValue(page, L.objet);
  const acheteur = labelValue(page, L.acheteur);
  const lieu = labelValue(page, L.lieu);
  const estimation = parseAmount(labelValue(page, L.estimation)) || 0;
  const categorieText = labelValue(page, L.categorie);
  const domaineText = labelValue(page, L.domaine);
  const categorie = mapType(categorieText || domaineText);
  const domaine = parseDomaine(domaineText);
  const dateLimite = labelValue(page, L.dateLimite);

  let best = [], bNameCol = 0, bAmtCol = 1, bStatCol = -1;
  for (const table of page.tables) {
    const headers = (table.headers || []).map((h) => (h || '').toLowerCase());
    const nameCol = headers.findIndex((h) => K_NAME.some((k) => h.includes(k)));
    const amountCol = headers.findIndex((h) => K_AMOUNT.some((k) => h.includes(k)));
    const statusCol = headers.findIndex((h) => K_STATUS.some((k) => h.includes(k)));
    const offers = extractOffers(table, nameCol, amountCol, statusCol);
    if (offers.length > best.length) { best = offers; bNameCol = nameCol; bAmtCol = amountCol; bStatCol = statusCol; }
  }
  return { reference, objet, acheteur, lieu, estimation, categorie, domaine, dateLimite, offers: best };
}

// ReferenceCalculator (port)
function computeReference(estimation, offers) {
  const retained = offers.filter((o) => o.retained && o.amount > 0);
  if (estimation <= 0 || retained.length === 0) return null;
  const avg = retained.reduce((s, o) => s + o.amount, 0) / retained.length;
  const ref = (estimation + avg) / 2;
  const ranked = retained.slice().sort((a, b) => {
    const ka = a.amount <= ref ? 0 : 1, kb = b.amount <= ref ? 0 : 1;
    if (ka !== kb) return ka - kb;
    return Math.abs(a.amount - ref) - Math.abs(b.amount - ref);
  }).map((o, i) => ({ name: o.name, amount: o.amount, rank: i + 1 }));
  return { ref, ranked };
}
function dedupKey(reference, acheteur, lieu, dateLimite, estimation) {
  const raw = [reference, acheteur, lieu, dateLimite, String(estimation)].map((s) => (s || '').trim().toLowerCase()).join('|');
  return crypto.createHash('sha256').update(raw).digest('hex');
}

// ---------- Scripts JS de la page (copiés de l'app) ----------
const EXPAND_SCRIPT = `(function(){function ownText(e){var t='';for(var i=0;i<e.childNodes.length;i++){if(e.childNodes[i].nodeType===3)t+=e.childNodes[i].nodeValue;}return t.trim();}try{var hint=/plus|expand|toggle|détail|detail|deplier|déplier|afficher|voir|more|collaps/i;var els=document.querySelectorAll('a,span,div,button,img,i,td,th,li,p');var clicked=0;for(var k=0;k<els.length&&clicked<80;k++){var e=els[k];if(e.__pxClicked)continue;if(e.tagName==='A'){var href=e.getAttribute('href')||'';var hl=href.toLowerCase();if(href&&hl.indexOf('#')!==0&&hl.indexOf('javascript')!==0)continue;}var cls=(''+(e.className&&e.className.baseVal!==undefined?e.className.baseVal:(e.className||''))).toLowerCase();var id=(e.id||'').toLowerCase();var oc=(e.getAttribute&&(e.getAttribute('onclick')||''))||'';var title=(e.getAttribute&&(e.getAttribute('title')||''))||'';var t=ownText(e);if(t==='+'||t==='[+]'||hint.test(cls)||hint.test(id)||hint.test(oc)||hint.test(title)){e.__pxClicked=1;try{e.click();clicked++;}catch(_){}}}return clicked;}catch(e){return -1;}})()`;
const SCRIPT = `(function(){function clean(s){return (s||'').replace(/\\s+/g,' ').trim();}try{var tables=[];var tEls=document.querySelectorAll('table');for(var i=0;i<tEls.length;i++){var trs=tEls[i].querySelectorAll('tr');if(!trs.length)continue;var headers=[];var fc=trs[0].querySelectorAll('th,td');for(var h=0;h<fc.length;h++)headers.push(clean(fc[h].innerText));var rows=[];for(var r=1;r<trs.length;r++){var cells=trs[r].querySelectorAll('td,th');var row=[];var any=false;for(var c=0;c<cells.length;c++){var v=clean(cells[c].innerText);row.push(v);if(v)any=true;}if(any)rows.push(row);if(rows.length>=400)break;}if(headers.length||rows.length)tables.push({headers:headers,rows:rows});}var bodyText=clean(document.body?document.body.innerText:'');return {url:location.href,rawText:bodyText.substring(0,20000),titles:[],tables:tables,amounts:[]};}catch(e){return {url:location.href,rawText:'',titles:[],tables:[],amounts:[]};}})()`;

// ---------- Navigation marchés publics ----------
function dateMinus(days) {
  const d = new Date(); d.setDate(d.getDate() - days);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()}`;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function run() {
  const offset = DAYS_MIN + Math.floor(Math.random() * (DAYS_MAX - WINDOW - DAYS_MIN + 1));
  const from = dateMinus(offset + WINDOW), to = dateMinus(offset);
  log('Fenêtre date limite', from, '→', to);

  // Réf. déjà connues (service_role contourne la RLS).
  const seenRows = await supaGet('tenders?select=reference');
  const seen = new Set(seenRows.map((r) => r.reference).filter(Boolean));
  const visRows = await supaGet('robot_visited?select=ref,org');
  const visited = new Set(visRows.map((r) => `${r.ref}|${r.org}`));
  log('Déjà en base:', seen.size, '· déjà visitées:', visited.size);

  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  const page = await browser.newPage({ userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36' });
  page.setDefaultTimeout(45000);

  try {
    await page.goto(SEARCH_URL, { waitUntil: 'domcontentloaded' });
    await sleep(2500);

    // Remplir "Date limite de remise des plis" puis lancer la recherche.
    const sres = await page.evaluate(({ from, to }) => {
      function classify(inp){var p=inp;for(var u=0;u<8&&p.parentElement;u++){p=p.parentElement;var s=(p.textContent||'').toLowerCase();var a=s.indexOf('remise des plis')>=0,b=s.indexOf('mise en ligne')>=0;if(a&&!b)return 'dl';if(b&&!a)return 'ml';if(a&&b)return '?';}return '';}
      var all=[].slice.call(document.querySelectorAll('input')).filter(function(i){var t=(i.type||'text').toLowerCase();return t==='text'||t==='';});
      var dl=all.filter(function(i){return classify(i)==='dl';});
      if(dl.length>=2){dl[0].value=from;dl[1].value=to;}
      var b=[].slice.call(document.querySelectorAll('a,input,button')).filter(function(e){return ((e.value||e.innerText||e.textContent||'')+'').toLowerCase().indexOf('lancer la recherche')>=0;})[0];
      if(b)b.click();
      return 'dl='+dl.length;
    }, { from, to });
    log('Recherche', sres);
    await page.waitForLoadState('networkidle').catch(() => {});
    await sleep(3000);

    // Afficher 500 résultats / page.
    const pres = await page.evaluate(() => {
      var sels=[].slice.call(document.querySelectorAll('select'));
      for(var i=0;i<sels.length;i++){var s=sels[i];var vals=[].slice.call(s.options).map(function(o){return parseInt((o.value||o.text||'').trim(),10);});if(vals.indexOf(10)>=0&&vals.indexOf(50)>=0){var max=Math.max.apply(null,vals.filter(function(n){return !isNaN(n);}));for(var j=0;j<s.options.length;j++){if(parseInt((s.options[j].value||s.options[j].text||'').trim(),10)===max){s.selectedIndex=j;break;}}s.dispatchEvent(new Event('change',{bubbles:true}));if(typeof window.__doPostBack==='function'&&s.name){try{window.__doPostBack(s.name,'');}catch(e){}}return 'perpage='+max;}}return 'noselect';
    });
    log('Résultats/page', pres);
    await page.waitForLoadState('networkidle').catch(() => {});
    await sleep(3500);

    // Récolter les couples (ref, org).
    const pairs = await page.evaluate(() => {
      var html=document.documentElement.innerHTML,out=[],seen={};
      var re1=/refConsultation=(\d+)[^"'<>]*?orgAcronyme=([A-Za-z0-9_]+)/g;
      var re2=/orgAcronyme=([A-Za-z0-9_]+)[^"'<>]*?refConsultation=(\d+)/g,m;
      while(m=re1.exec(html)){var k=m[1]+'|'+m[2];if(!seen[k]){seen[k]=1;out.push([m[1],m[2]]);}}
      while(m=re2.exec(html)){var k=m[2]+'|'+m[1];if(!seen[k]){seen[k]=1;out.push([m[2],m[1]]);}}
      return out;
    });
    log('Trouvées:', pairs.length);

    const fresh = pairs.filter(([ref, org]) => !seen.has(ref) && !visited.has(`${ref}|${org}`));
    for (let i = fresh.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [fresh[i], fresh[j]] = [fresh[j], fresh[i]]; }
    log('Nouvelles:', fresh.length, '· objectif', TARGET);

    let sent = 0;
    for (const [ref, org] of fresh) {
      if (sent >= TARGET) break;
      const url = `${BASE}&refConsultation=${ref}&orgAcronyme=${org}`;
      let ok = false, status = 'incomplet';
      try {
        await page.goto(url, { waitUntil: 'domcontentloaded' });
        for (let attempt = 0; attempt < 5 && !ok; attempt++) {
          await sleep(1800);
          await page.evaluate(EXPAND_SCRIPT).catch(() => {});
          await sleep(1300);
          const pd = await page.evaluate(SCRIPT).catch(() => null);
          if (!pd) continue;
          const a = analyzePage(pd, ref);
          const calc = computeReference(a.estimation, a.offers);
          if (calc) {
            const key = dedupKey(a.reference, a.acheteur, a.lieu, a.dateLimite, a.estimation);
            ok = await supaRpc('robot_ingest', {
              p_ref: ref, p_org: org, p_dedup_key: key, p_reference: a.reference, p_objet: a.objet,
              p_acheteur: a.acheteur, p_ville: a.lieu, p_categorie: a.categorie, p_domaine: a.domaine,
              p_estimation: a.estimation, p_reference_price: calc.ref, p_date_limite: a.dateLimite,
              p_participants: calc.ranked, p_completeness: calc.ranked.length, p_payload: a, p_status: 'ok',
            });
            status = ok ? 'ok' : 'send_fail';
            break;
          }
        }
      } catch (e) { status = 'err'; }
      if (ok) { sent++; log('✓', ref, `(${sent}/${TARGET})`); }
      else { log('–', ref, status); await supaRpc('robot_ingest', { p_ref: ref, p_org: org, p_dedup_key: '', p_reference: ref, p_objet: '', p_acheteur: '', p_ville: '', p_categorie: '', p_domaine: '', p_estimation: 0, p_reference_price: 0, p_date_limite: '', p_participants: [], p_completeness: 0, p_payload: {}, p_status: status }); }
    }
    log('Terminé:', sent, '/', TARGET, 'envoyées.');
  } finally {
    await browser.close();
  }
}

run().catch((e) => { console.error(e); process.exit(1); });
