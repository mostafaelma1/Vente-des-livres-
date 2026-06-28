package com.prixref.ao

import com.prixref.ao.analyze.ExtractedTable
import com.prixref.ao.analyze.LocalAnalyzer
import com.prixref.ao.analyze.PageData
import com.prixref.ao.calc.ReferenceCalculator
import com.prixref.ao.data.CompanyStats
import com.prixref.ao.data.CompetitorEngine
import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.model.Competitor
import com.prixref.ao.model.TypeMarche
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsAndExtractionTest {

    private fun analysis(
        ref: String, est: Double, cat: String, dom: String, ville: String, dl: String,
        vararg c: Competitor,
    ) = ReferenceCalculator.analyze(
        AnalysisInput(ref, "Objet", "MO", TypeMarche.SERVICES, ville, est, "1", "",
            c.toList(), dateLimite = dl, categorieLabel = cat, domaine = dom)
    )

    @Test
    fun `extraction libelles avec accents apostrophes et pluriel`() {
        val raw = "Catégorie principale Services Lieu d'exécution RABAT " +
            "Estimation (en Dhs TTC) 100 000,00 " +
            "Domaines d'activité Services / Services d'hôtellerie, hébergement, restauration / Prestation " +
            "Adresse de retrait des dossiers test"
        val table = ExtractedTable(
            headers = listOf("Soumissionnaire", "Montant", "Statut"),
            rows = listOf(listOf("ACME", "99 000,00", "Retenue"), listOf("ZED", "80 000,00", "Écartée")),
        )
        val r = LocalAnalyzer.analyze(PageData(rawText = raw, tables = listOf(table)), "1/26", "x", "u")
        assertEquals("RABAT", r.input.lieu)
        assertEquals(100000.0, r.input.estimation, 0.001)
        assertEquals(TypeMarche.SERVICES, r.input.typeMarche)
        assertTrue("domaine extrait", r.input.domaine.contains("hôtellerie", ignoreCase = true))
        assertTrue("offres détectées", r.offersDetected)
        assertEquals(2, r.input.competitors.size)
        // ZED marquée écartée
        assertTrue(r.input.competitors.first { it.name == "ZED" }.retained.not())
    }

    @Test
    fun `nom societe jamais un mot de statut`() {
        // Colonne sans en-tête société ; "Admissible" ne doit pas devenir le nom.
        val table = ExtractedTable(
            headers = listOf("N°", "Nom", "Montant", "Décision"),
            rows = listOf(listOf("5", "ABC", "120000", "Admissible")),
        )
        val r = LocalAnalyzer.analyze(PageData(rawText = "Estimation 100000", tables = listOf(table)), "x", "y", "u")
        assertTrue(r.input.competitors.any { it.name == "ABC" })
        assertTrue(r.input.competitors.none { it.name.equals("Admissible", ignoreCase = true) })
    }

    @Test
    fun `profils concurrents agressif et fiabilite`() {
        val src = (1..4).map { i ->
            CompetitorEngine.Source(
                i.toLong(),
                analysis("$i", 100000.0, "Services", "Restauration", "SAFI", "0$i/02/2026 10:00",
                    Competitor("ACME", 99000.0, true), Competitor("ZED", 80000.0, true)),
            )
        }
        val comps = CompetitorEngine.build(src)
        val zed = comps.first { it.nom == "ZED" }
        val acme = comps.first { it.nom == "ACME" }
        assertEquals(CompetitorEngine.PROFIL_AGRESSIF, zed.stats.profil)
        assertEquals(CompetitorEngine.FIAB_FAIBLE, zed.stats.fiabilite) // 4 participations
        assertTrue("ZED écart négatif", zed.stats.ecartPrMoyen < 0)
        assertTrue("ACME proche/au-dessus", acme.stats.ecartPrMoyen > zed.stats.ecartPrMoyen)
    }

    @Test
    fun `top par domaine privilegie proximite du prix de reference`() {
        val src = (1..6).map { i ->
            CompetitorEngine.Source(
                i.toLong(),
                analysis("$i", 100000.0, "Services", "Restauration", "SAFI", "",
                    Competitor("PROCHE", 100000.0, true),
                    Competitor("HAUTE", 140000.0, true),
                    Competitor("BASSE", 70000.0, true)),
            )
        }
        val comps = CompetitorEngine.build(src)
        val top = CompetitorEngine.topInDomaine(comps, "Services", "Restauration")
        assertEquals("PROCHE", top.first().nom)
    }

    @Test
    fun `parse date limite`() {
        assertNotNull(CompanyStats.parseDate("12/05/2026 10:00"))
        assertNotNull(CompanyStats.parseDate("01/03/2026"))
        assertEquals(null, CompanyStats.parseDate(""))
    }
}
