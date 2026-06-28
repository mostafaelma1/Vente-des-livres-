package com.prixref.ao.data

import java.text.Normalizer

/**
 * Régions du Maroc et leurs provinces/préfectures (selon marchespublics.gov.ma).
 * Permet d'agréger les statistiques par région à partir du lieu d'exécution
 * (ville) de chaque marché.
 */
object Regions {

    const val ALL = "(toutes les régions)"

    data class Region(val name: String, val provinces: List<String>)

    val REGIONS: List<Region> = listOf(
        Region("Béni Mellal-Khénifra", listOf("BENI MELLAL", "AZILAL", "FQUIH BEN SALAH", "KHENIFRA", "KHOURIBGA")),
        Region("Casablanca-Settat", listOf("CASABLANCA", "MOHAMMADIA", "EL JADIDA", "NOUACEUR", "MEDIOUNA", "BENSLIMANE", "BERRECHID", "SETTAT", "SIDI BENNOUR")),
        Region("Dakhla-Oued Ed-Dahab", listOf("OUED ED DAHAB", "AOUSSERD", "DAKHLA")),
        Region("Drâa-Tafilalet", listOf("ERRACHIDIA", "OUARZAZATE", "MIDELT", "TINGHIR", "ZAGORA")),
        Region("Fès-Meknès", listOf("FES", "MEKNES", "EL-HAJEB", "IFRANE", "MOULAY-YACOUB", "SEFROU", "BOULEMANE", "TAOUNATE", "TAZA")),
        Region("Guelmim-Oued Noun", listOf("GUELMIM", "ASSA-ZAG", "TAN-TAN", "SIDI IFNI")),
        Region("L'oriental", listOf("OUJDA-ANGAD", "NADOR", "DRIOUCH", "JERADA", "BERKANE", "TAOURIRT", "GUERCIF", "FIGUIG")),
        Region("Laâyoune-Sakia El Hamra", listOf("LAAYOUNE", "BOUJDOUR", "TARFAYA", "ES-SEMARA")),
        Region("Marrakech-Safi", listOf("MARRAKECH", "CHICHAOUA", "AL HAOUZ", "EL KELAA DES SRAGHNA", "ESSAOUIRA", "REHAMNA", "SAFI", "YOUSSOUFIA")),
        Region("Rabat-Salé-Kénitra", listOf("RABAT", "SALE", "SKHIRATE-TEMARA", "KENITRA", "KHEMISSET", "SIDI KACEM", "SIDI SLIMANE")),
        Region("Souss-Massa", listOf("AGADIR IDA OU TANANE", "AGADIR", "INEZGANE-AIT MELLOUL", "CHTOUKA-AIT BAHA", "TAROUDANNT", "TIZNIT", "TATA")),
        Region("Tanger-Tetouan-Al Hoceima", listOf("TANGER-ASSILAH", "TANGER", "MDIQ-FNIDEQ", "TETOUAN", "FAHS-ANJRA", "LARACHE", "AL HOCEIMA", "CHEFCHAOUEN", "OUEZZANE")),
    )

    fun names(): List<String> = REGIONS.map { it.name }

    /** Région correspondant à une ville / lieu d'exécution (null si inconnue). */
    fun regionOf(ville: String): String? {
        val v = norm(ville)
        if (v.isBlank()) return null
        for (r in REGIONS) {
            for (p in r.provinces) {
                val pn = norm(p)
                if (pn.isNotBlank() && (v == pn || v.contains(pn))) return r.name
            }
        }
        return null
    }

    /** Normalise (majuscules, sans accents ni séparateurs) pour comparer. */
    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .uppercase()
            .filter { it.isLetterOrDigit() }
}
