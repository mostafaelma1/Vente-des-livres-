package com.prixref.ao.calc

import android.net.Uri

/** Résultat de l'analyse d'une URL de suivi de consultation. */
data class ParsedUrl(
    val refConsultation: String,
    val orgAcronyme: String,
)

/**
 * Extrait `refConsultation` et `orgAcronyme` d'une URL de type :
 * https://www.marchespublics.gov.ma/?page=entreprise.SuiviConsultation&refConsultation=1009150&orgAcronyme=s3d
 *
 * Retourne `null` si l'URL est invalide ou si les paramètres sont absents.
 */
fun parseMarchesPublicsUrl(url: String): ParsedUrl? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null

    // Tentative robuste via Uri ; repli sur une analyse manuelle des paramètres.
    val ref = queryParam(trimmed, "refConsultation")
    val org = queryParam(trimmed, "orgAcronyme")

    if (ref.isNullOrBlank() || org.isNullOrBlank()) return null
    return ParsedUrl(ref, org)
}

private fun queryParam(url: String, key: String): String? {
    // Méthode 1 : Uri d'Android (disponible sur appareil/instrumentation).
    runCatching {
        val value = Uri.parse(url).getQueryParameter(key)
        if (!value.isNullOrBlank()) return value
    }
    // Méthode 2 : analyse manuelle (utile aussi pour les tests unitaires JVM).
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return null
    for (pair in query.split('&')) {
        val idx = pair.indexOf('=')
        if (idx > 0) {
            val name = pair.substring(0, idx)
            if (name.equals(key, ignoreCase = true)) {
                return pair.substring(idx + 1).trim().ifBlank { null }
            }
        }
    }
    return null
}
