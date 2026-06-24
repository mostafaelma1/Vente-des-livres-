package com.prixref.ao

import com.prixref.ao.calc.ReferenceCalculator
import com.prixref.ao.calc.parseMarchesPublicsUrl
import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.model.Competitor
import com.prixref.ao.model.TypeMarche
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceCalculatorTest {

    private fun input(
        estimation: Double,
        competitors: List<Competitor>,
        type: TypeMarche = TypeMarche.FOURNITURES,
    ) = AnalysisInput(
        reference = "AO-TEST", objet = "", maitreOuvrage = "",
        typeMarche = type, lieu = "", estimation = estimation,
        lotNumero = "1", lotDesignation = "", competitors = competitors,
    )

    @Test
    fun `exemple de la specification`() {
        val result = ReferenceCalculator.analyze(
            input(
                949000.0,
                listOf(
                    Competitor("Société A", 910000.0, true),
                    Competitor("Société B", 940000.0, true),
                    Competitor("Société C", 970000.0, true),
                    Competitor("Société D", 990000.0, true),
                ),
            )
        )
        assertEquals(952500.0, result.averageRetained, 0.001)
        assertEquals(950750.0, result.referencePrice, 0.001)
        // L'offre la plus proche de 950750 est Société B (940000).
        assertEquals("Société B", result.probableWinner)
        assertEquals(1, result.ranking.first().rank)
    }

    @Test
    fun `classement les offres en dessous du prix de reference passent en premier`() {
        // estimation 500, offres 450 / 510 / 540 -> moyenne 500, prix de référence 500.
        val result = ReferenceCalculator.analyze(
            input(
                500.0,
                listOf(
                    Competitor("C1", 510.0, true), // au-dessus, écart 10
                    Competitor("C2", 450.0, true), // en dessous, écart 50
                    Competitor("C3", 540.0, true), // au-dessus, écart 40
                ),
            )
        )
        assertEquals(500.0, result.referencePrice, 0.001)
        // L'offre en dessous (450) passe avant les offres au-dessus, même si
        // son écart est plus grand. Puis les offres au-dessus par écart croissant.
        assertEquals(listOf("C2", "C1", "C3"), result.ranking.map { it.name })
        assertEquals("C2", result.probableWinner)
    }

    @Test
    fun `offres ecartees exclues de la moyenne`() {
        val result = ReferenceCalculator.analyze(
            input(
                100000.0,
                listOf(
                    Competitor("X", 90000.0, true),
                    Competitor("Y", 110000.0, true),
                    Competitor("Z", 1000000.0, false),
                ),
            )
        )
        assertEquals(100000.0, result.averageRetained, 0.001)
        assertEquals(100000.0, result.referencePrice, 0.001)
        assertEquals(2, result.retainedCount)
        assertEquals(1, result.excludedCount)
    }

    @Test(expected = ReferenceCalculator.CalculationException::class)
    fun `aucune offre retenue leve une exception`() {
        ReferenceCalculator.analyze(
            input(100000.0, listOf(Competitor("X", 90000.0, false)))
        )
    }

    @Test
    fun `observations selon ecart pourcentage`() {
        assertEquals("Très proche", ReferenceCalculator.observation(0.5))
        assertEquals("Proche", ReferenceCalculator.observation(2.0))
        assertEquals("Moyen", ReferenceCalculator.observation(5.0))
        assertEquals("Éloigné", ReferenceCalculator.observation(15.0))
    }

    @Test
    fun `risque offre anormalement basse pour fournitures`() {
        val result = ReferenceCalculator.analyze(
            input(
                100000.0,
                listOf(
                    Competitor("Basse", 70000.0, true),    // -30% < -20%
                    Competitor("Normale", 100000.0, true),
                ),
                TypeMarche.FOURNITURES,
            )
        )
        val basse = result.ranking.first { it.name == "Basse" }
        assertTrue(basse.risk.contains("anormalement basse"))
    }

    @Test
    fun `seuil travaux a 25 pourcent`() {
        // -22% : alerte pour Fournitures (20%) mais pas pour Travaux (25%).
        val travaux = ReferenceCalculator.analyze(
            input(
                100000.0,
                listOf(Competitor("A", 78000.0, true), Competitor("B", 100000.0, true)),
                TypeMarche.TRAVAUX,
            )
        ).ranking.first { it.name == "A" }
        assertEquals("Bonne position financière", travaux.risk)
    }

    @Test
    fun `parser url marches publics`() {
        val parsed = parseMarchesPublicsUrl(
            "https://www.marchespublics.gov.ma/?page=entreprise.SuiviConsultation" +
                "&refConsultation=1009150&orgAcronyme=s3d"
        )
        assertEquals("1009150", parsed?.refConsultation)
        assertEquals("s3d", parsed?.orgAcronyme)
    }

    @Test
    fun `parser url invalide retourne null`() {
        assertNull(parseMarchesPublicsUrl("https://example.com/page"))
        assertNull(parseMarchesPublicsUrl(""))
    }
}
