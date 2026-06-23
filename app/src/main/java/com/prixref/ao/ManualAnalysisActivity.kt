package com.prixref.ao

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.prixref.ao.calc.ReferenceCalculator
import com.prixref.ao.data.JsonStore
import com.prixref.ao.databinding.ActivityManualBinding
import com.prixref.ao.databinding.ItemCompetitorBinding
import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.model.Competitor
import com.prixref.ao.model.TypeMarche

class ManualAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManualBinding
    private val rows = mutableListOf<ItemCompetitorBinding>()

    companion object {
        const val EXTRA_REFERENCE = "extra_reference"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Dropdown du type de marché.
        val types = TypeMarche.entries.map { it.label }
        binding.acType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, types)
        )
        binding.acType.setText(TypeMarche.FOURNITURES.label, false)

        intent.getStringExtra(EXTRA_REFERENCE)?.let { binding.etReference.setText(it) }

        binding.btnAddCompetitor.setOnClickListener { addRow() }
        binding.btnCalculate.setOnClickListener { calculate() }

        // Deux lignes vides au démarrage.
        addRow()
        addRow()
    }

    private fun addRow(name: String = "", amount: String = "", retained: Boolean = true) {
        val row = ItemCompetitorBinding.inflate(layoutInflater, binding.competitorsContainer, false)
        row.etName.setText(name)
        row.etAmount.setText(amount)
        row.swRetained.isChecked = retained
        row.btnRemove.setOnClickListener {
            binding.competitorsContainer.removeView(row.root)
            rows.remove(row)
        }
        rows.add(row)
        binding.competitorsContainer.addView(row.root)
    }

    private fun calculate() {
        val estimation = binding.etEstimation.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
        if (estimation == null || estimation <= 0.0) {
            binding.etEstimation.error = "Estimation obligatoire (montant positif)"
            return
        }

        val competitors = mutableListOf<Competitor>()
        for (row in rows) {
            val name = row.etName.text?.toString()?.trim().orEmpty()
            val amountText = row.etAmount.text?.toString()?.replace(',', '.')?.trim().orEmpty()
            if (name.isEmpty() && amountText.isEmpty()) continue
            val amount = amountText.toDoubleOrNull()
            if (amount == null || amount <= 0.0) {
                row.etAmount.error = "Montant numérique requis"
                return
            }
            if (name.isEmpty()) {
                row.etName.error = "Nom requis"
                return
            }
            competitors.add(Competitor(name, amount, row.swRetained.isChecked))
        }

        if (competitors.none { it.retained }) {
            toast("Ajoutez au moins une offre retenue.")
            return
        }

        val input = AnalysisInput(
            reference = binding.etReference.text?.toString()?.trim().orEmpty(),
            objet = binding.etObjet.text?.toString()?.trim().orEmpty(),
            maitreOuvrage = binding.etMaitre.text?.toString()?.trim().orEmpty(),
            typeMarche = TypeMarche.fromLabel(binding.acType.text?.toString()),
            lieu = binding.etLieu.text?.toString()?.trim().orEmpty(),
            estimation = estimation,
            lotNumero = binding.etLotNum.text?.toString()?.trim().ifEmptyOrNull("1"),
            lotDesignation = binding.etLotDesig.text?.toString()?.trim().orEmpty(),
            competitors = competitors,
        )

        try {
            val result = ReferenceCalculator.analyze(input)
            startActivity(
                Intent(this, ResultActivity::class.java)
                    .putExtra(ResultActivity.EXTRA_RESULT_JSON, JsonStore.toJson(result))
            )
        } catch (e: ReferenceCalculator.CalculationException) {
            toast(e.message ?: "Erreur de calcul")
        }
    }

    private fun String?.ifEmptyOrNull(default: String): String =
        if (this.isNullOrEmpty()) default else this

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
