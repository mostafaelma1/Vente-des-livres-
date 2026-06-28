package com.prixref.ao

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.prixref.ao.analyze.LocalAnalyzer
import com.prixref.ao.analyze.PageData
import com.prixref.ao.analyze.WebExtraction
import com.prixref.ao.calc.ReferenceCalculator
import com.prixref.ao.data.AccountStore
import com.prixref.ao.data.Backend
import com.prixref.ao.databinding.ActivityBatchBinding
import com.prixref.ao.util.DeviceId
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Robot d'analyse automatique (réservé à l'admin) : ouvre la recherche avancée
 * marchespublics, fixe la « date limite de remise des plis » sur une fenêtre
 * récente (J-7 → J-3), récupère la liste des consultations, analyse chacune et
 * envoie les résultats au serveur (pool partagé visible par tous).
 *
 * Automatisation dépendante du site : le journal à l'écran permet de l'ajuster.
 */
class BatchAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchBinding
    private val handler = Handler(Looper.getMainLooper())
    private val deviceId by lazy { DeviceId.get(this) }

    private enum class Phase { FORM, RESULTS, ANALYZE }
    private var phase = Phase.FORM
    private var formSubmitted = false
    private var harvested = false

    private val queue = ArrayList<Pair<String, String>>()   // (refConsultation, orgAcronyme)
    private var index = 0
    private var uploaded = 0
    private var pageHandledForIndex = -1

    private var tries = 0
    private var harvestTries = 0
    private var winFrom = ""
    private var winTo = ""

    private companion object {
        const val SEARCH_URL = "https://www.marchespublics.gov.ma/index.php?page=entreprise.EntrepriseAdvancedSearch"
        const val BASE = "https://www.marchespublics.gov.ma/index.php?page=entreprise.SuiviConsultation"
        const val MAX = 10
        const val DAYS_MIN = 3           // date limite passée d'au moins 3 jours
        const val DAYS_MAX = 8           // … et au plus 8 jours
        const val EXTRACT_TRIES = 6      // tentatives d'extraction par consultation
        const val RETRY_MS = 2300L
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnClose.setOnClickListener { finish() }

        val account = AccountStore.get(this)
        if (account == null || !account.isAdmin || !Backend.isConfigured) {
            status("Réservé à l'administrateur (backend requis).")
            binding.progress.visibility = android.view.View.GONE
            return
        }

        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) = onPage()
        }

        // Fenêtre fixe : date limite passée entre 3 et 8 jours. La variété entre
        // exécutions vient du mélange aléatoire des résultats trouvés.
        winFrom = dateMinus(DAYS_MAX)
        winTo = dateMinus(DAYS_MIN)
        log("Fenêtre date limite : $winFrom → $winTo")
        status("Ouverture de la recherche…")
        binding.webView.loadUrl(SEARCH_URL)
    }

    private fun onPage() {
        when (phase) {
            Phase.FORM -> if (!formSubmitted) {
                formSubmitted = true
                status("Réglage des dates et lancement de la recherche…")
                submitSearch()
                phase = Phase.RESULTS
                handler.postDelayed({ if (!harvested) harvest() }, 6000)
            }
            Phase.RESULTS -> if (!harvested) handler.postDelayed({ if (!harvested) harvest() }, 3000)
            Phase.ANALYZE -> if (index != pageHandledForIndex) {
                pageHandledForIndex = index
                tries = 0
                handler.postDelayed({ extractCurrent() }, 1800)
            }
        }
    }

    private fun submitSearch() {
        val from = winFrom
        val to = winTo
        val js = """
            (function(){
              try{
                var ins=[].slice.call(document.querySelectorAll('input')).filter(function(i){
                  return /^\d{2}\/\d{2}\/\d{4}${'$'}/.test((i.value||'').trim());
                });
                if(ins.length>=2){ ins[0].value='$from'; ins[1].value='$to'; }
                var els=[].slice.call(document.querySelectorAll('a,input,button'));
                var b=els.filter(function(e){
                  var t=((e.value||e.innerText||e.textContent||'')+'').toLowerCase();
                  return t.indexOf('lancer la recherche')>=0;
                })[0];
                if(b){ b.click(); return 'submit:'+ins.length; }
                return 'nobtn:'+ins.length;
              }catch(e){ return 'err:'+e; }
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js) { log("Recherche → $it") }
    }

    private fun harvest() {
        if (harvested) return
        val js = """
            (function(){
              var html=document.documentElement.innerHTML, out=[], seen={};
              var re1=/refConsultation=(\d+)[^"'<>]*?orgAcronyme=([A-Za-z0-9_]+)/g;
              var re2=/orgAcronyme=([A-Za-z0-9_]+)[^"'<>]*?refConsultation=(\d+)/g, m;
              while(m=re1.exec(html)){var k=m[1]+'|'+m[2];if(!seen[k]){seen[k]=1;out.push({ref:m[1],org:m[2]});}}
              while(m=re2.exec(html)){var k=m[2]+'|'+m[1];if(!seen[k]){seen[k]=1;out.push({ref:m[2],org:m[1]});}}
              return JSON.stringify(out);
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js) { value ->
            val pairs = parsePairs(value)
            if (pairs.isEmpty()) {
                if (++harvestTries < 4) {
                    handler.postDelayed({ if (!harvested) harvest() }, 3000)
                    return@evaluateJavascript
                }
                log("Aucune consultation trouvée sur la page de résultats.")
                status("Aucune consultation trouvée. Vérifiez la recherche.")
                binding.progress.visibility = android.view.View.GONE
                return@evaluateJavascript
            }
            harvested = true
            queue.clear()
            // Mélange aléatoire → on n'analyse pas toujours les mêmes consultations.
            queue.addAll(pairs.distinct().shuffled().take(MAX))
            log("${queue.size} consultation(s) à analyser.")
            phase = Phase.ANALYZE
            index = 0
            pageHandledForIndex = -1
            loadCurrent()
        }
    }

    private fun loadCurrent() {
        if (index >= queue.size) { done(); return }
        val (ref, org) = queue[index]
        status("Analyse ${index + 1}/${queue.size} (réf. $ref)…")
        binding.webView.loadUrl("$BASE&refConsultation=$ref&orgAcronyme=$org")
    }

    private fun extractCurrent() {
        binding.webView.evaluateJavascript(WebExtraction.EXPAND_SCRIPT) {
            handler.postDelayed({
                binding.webView.evaluateJavascript(WebExtraction.SCRIPT) { value ->
                    processPage(value)
                }
            }, 1200)
        }
    }

    private fun processPage(value: String?) {
        val (ref, org) = queue[index]
        val page = parsePage(value)
        val result = page?.let { LocalAnalyzer.analyze(it, ref, org, "$BASE&refConsultation=$ref&orgAcronyme=$org") }
        val input = result?.input
        if (input != null && input.estimation > 0.0 && input.competitors.any { it.retained && it.amount > 0.0 }) {
            runCatching {
                val analysis = ReferenceCalculator.analyze(input)
                uploadAndNext(analysis)
            }.onFailure { skipNext("calcul impossible") }
            return
        }
        // Pas encore exploitable : la page n'est peut-être pas finie de charger → on réessaie.
        if (++tries < EXTRACT_TRIES) {
            handler.postDelayed({ extractCurrent() }, RETRY_MS)
        } else {
            skipNext("données incomplètes")
        }
    }

    private fun uploadAndNext(analysis: com.prixref.ao.model.AnalysisResult) {
        val account = AccountStore.get(this) ?: return
        lifecycleScope.launch {
            val ok = runCatching { Backend.submitAnalysis(account, deviceId, analysis, "robot") }.getOrDefault(false)
            if (ok) { uploaded++; log("✓ réf. ${queue[index].first} envoyée.") }
            else log("✗ réf. ${queue[index].first} non envoyée.")
            index++
            loadCurrent()
        }
    }

    private fun skipNext(reason: String) {
        log("– réf. ${queue[index].first} ignorée ($reason).")
        index++
        loadCurrent()
    }

    private fun done() {
        status("Terminé : $uploaded analyse(s) envoyée(s) au serveur.")
        log("Terminé. $uploaded / ${queue.size} envoyées.")
        binding.progress.visibility = android.view.View.GONE
    }

    private fun dateMinus(days: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(cal.time)
    }

    private fun parsePairs(value: String?): List<Pair<String, String>> {
        if (value == null || value == "null") return emptyList()
        return try {
            val json = runCatching { Gson().fromJson(value, String::class.java) }.getOrNull() ?: value
            com.google.gson.JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
                val o = el.asJsonObject
                val r = o.get("ref")?.takeIf { !it.isJsonNull }?.asString
                val org = o.get("org")?.takeIf { !it.isJsonNull }?.asString
                if (r != null && org != null) r to org else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePage(value: String?): PageData? {
        if (value == null || value == "null") return null
        return try {
            Gson().fromJson(value, PageData::class.java)
        } catch (e: Exception) {
            runCatching { Gson().fromJson(Gson().fromJson(value, String::class.java), PageData::class.java) }.getOrNull()
        }
    }

    private fun status(s: String) { binding.tvStatus.text = s }
    private fun log(s: String) {
        binding.tvLog.append(s + "\n")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
