package com.prixref.ao.analyze

/**
 * Données extraites localement d'une page web (via JavaScript injecté dans la
 * WebView). Aucun serveur n'est impliqué : l'extraction se fait sur l'appareil.
 */

/** Un tableau HTML détecté sur la page. */
data class ExtractedTable(
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
) {
    /** Nombre de colonnes (max entre l'en-tête et les lignes). */
    val columnCount: Int
        get() = (listOf(headers.size) + rows.map { it.size }).maxOrNull() ?: 0
}

/** Résultat brut renvoyé par le script JavaScript. */
data class PageData(
    val url: String = "",
    val rawText: String = "",
    val titles: List<String> = emptyList(),
    val tables: List<ExtractedTable> = emptyList(),
    val amounts: List<String> = emptyList(),
)

/**
 * Script JavaScript injecté dans la WebView pour extraire localement le contenu
 * de la page (texte, titres, tableaux, montants). Retourne un objet JSON que la
 * WebView transmet à Kotlin via evaluateJavascript.
 */
object WebExtraction {

    /**
     * Déplie les sections repliables de la page (boutons « + », « Afficher les
     * détails »…) afin que toutes les informations de la consultation
     * deviennent visibles avant l'extraction. Chaque élément n'est cliqué
     * qu'une seule fois (marqueur __pxClicked) pour éviter de re-replier.
     */
    val EXPAND_SCRIPT: String = """
        (function () {
          function ownText(e) {
            var t = '';
            for (var i = 0; i < e.childNodes.length; i++) {
              if (e.childNodes[i].nodeType === 3) t += e.childNodes[i].nodeValue;
            }
            return t.trim();
          }
          try {
            var hint = /plus|expand|toggle|détail|detail|deplier|déplier|afficher|voir|more|collaps/i;
            var els = document.querySelectorAll('a,span,div,button,img,i,td,th,li,p');
            var clicked = 0;
            for (var k = 0; k < els.length && clicked < 80; k++) {
              var e = els[k];
              if (e.__pxClicked) continue;
              if (e.tagName === 'A') {
                var href = e.getAttribute('href') || '';
                var hl = href.toLowerCase();
                if (href && hl.indexOf('#') !== 0 && hl.indexOf('javascript') !== 0) continue;
              }
              var cls = ('' + (e.className && e.className.baseVal !== undefined ? e.className.baseVal : (e.className || ''))).toLowerCase();
              var id = (e.id || '').toLowerCase();
              var oc = (e.getAttribute && (e.getAttribute('onclick') || '')) || '';
              var title = (e.getAttribute && (e.getAttribute('title') || '')) || '';
              var t = ownText(e);
              if (t === '+' || t === '[+]' || hint.test(cls) || hint.test(id) || hint.test(oc) || hint.test(title)) {
                e.__pxClicked = 1;
                try { e.click(); clicked++; } catch (_) {}
              }
            }
            return clicked;
          } catch (e) { return -1; }
        })();
    """.trimIndent()

    val SCRIPT: String = """
        (function () {
          function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
          try {
            var tables = [];
            var tEls = document.querySelectorAll('table');
            for (var i = 0; i < tEls.length; i++) {
              var trs = tEls[i].querySelectorAll('tr');
              if (!trs.length) continue;
              var headers = [];
              var firstCells = trs[0].querySelectorAll('th,td');
              for (var h = 0; h < firstCells.length; h++) headers.push(clean(firstCells[h].innerText));
              var rows = [];
              for (var r = 1; r < trs.length; r++) {
                var cells = trs[r].querySelectorAll('td,th');
                var row = []; var any = false;
                for (var c = 0; c < cells.length; c++) {
                  var v = clean(cells[c].innerText);
                  row.push(v); if (v) any = true;
                }
                if (any) rows.push(row);
                if (rows.length >= 400) break;
              }
              if (headers.length || rows.length) tables.push({ headers: headers, rows: rows });
            }

            var bodyText = clean(document.body ? document.body.innerText : '');

            var titles = [];
            var hEls = document.querySelectorAll('h1,h2,h3,h4,legend,caption,th');
            for (var k = 0; k < hEls.length; k++) {
              var tt = clean(hEls[k].innerText);
              if (tt && titles.indexOf(tt) === -1) titles.push(tt);
              if (titles.length >= 80) break;
            }

            var amounts = []; var seen = {};
            var re = /\d[\d.,\s\u00a0\u202f]{2,}\d/g; var m;
            while ((m = re.exec(bodyText)) !== null) {
              var a = m[0];
              if (!seen[a]) { seen[a] = 1; amounts.push(a); }
              if (amounts.length >= 250) break;
            }

            return {
              url: location.href,
              rawText: bodyText.substring(0, 20000),
              titles: titles,
              tables: tables,
              amounts: amounts
            };
          } catch (e) {
            return { url: location.href, rawText: '', titles: [], tables: [], amounts: [] };
          }
        })();
    """.trimIndent()
}
