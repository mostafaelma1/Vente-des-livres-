package com.ventelivres.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ventelivres.app.data.Employee
import com.ventelivres.app.data.Pointage
import com.ventelivres.app.databinding.ActivityVirementBinding
import com.ventelivres.app.util.DocumentExporter
import com.ventelivres.app.util.Format
import com.ventelivres.app.util.MoneyWords
import com.ventelivres.app.util.PayrollRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class VirementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVirementBinding
    private val dao get() = (application as VenteApp).db.dao()
    private val settings by lazy { (application as VenteApp).settings }

    private var year = 0
    private var month = 0

    /** "" = all, else "VIREMENT" / "MISE DISPOSITION". */
    private var typeFilter = ""

    /** "" = all sites, else a specific lieu de travail. */
    private var lieuFilter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVirementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.virement_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val extraYear = intent.getIntExtra(EXTRA_YEAR, -1)
        if (extraYear > 0) {
            year = extraYear
            month = intent.getIntExtra(EXTRA_MONTH, 1)
        } else {
            val cal = Calendar.getInstance()
            year = cal.get(Calendar.YEAR)
            month = cal.get(Calendar.MONTH) + 1
        }

        binding.prevMonth.setOnClickListener { changeMonth(-1) }
        binding.nextMonth.setOnClickListener { changeMonth(1) }
        binding.pdfBtn.setOnClickListener { export(pdf = true) }
        binding.excelBtn.setOnClickListener { export(pdf = false) }

        binding.typeFilter.check(binding.typeAll.id)
        binding.typeFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            typeFilter = when (checkedId) {
                binding.typeVir.id -> Employee.TYPE_VIREMENT
                binding.typeMise.id -> Employee.TYPE_MISE_DISPOSITION
                else -> ""
            }
            refresh()
        }

        binding.siteBtn.text = getString(R.string.site_all)
        binding.siteBtn.setOnClickListener { showSitePicker() }
    }

    private fun showSitePicker() = lifecycleScope.launch {
        val sites = withContext(Dispatchers.IO) {
            dao.activeEmployees().map { it.lieuTravail.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        }
        val options = listOf(getString(R.string.site_all_option)) + sites
        val checked = if (lieuFilter.isBlank()) 0 else options.indexOf(lieuFilter).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this@VirementActivity)
            .setTitle(R.string.site_dialog_title)
            .setSingleChoiceItems(options.toTypedArray(), checked) { d, which ->
                lieuFilter = if (which == 0) "" else options[which]
                binding.siteBtn.text =
                    if (lieuFilter.isBlank()) getString(R.string.site_all) else getString(R.string.site_pick, lieuFilter)
                d.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun changeMonth(delta: Int) {
        month += delta
        if (month < 1) { month = 12; year-- }
        if (month > 12) { month = 1; year++ }
        refresh()
    }

    private fun refresh() = lifecycleScope.launch {
        binding.periodLabel.text = Format.period(year, month)

        val account = withContext(Dispatchers.IO) {
            val id = settings.activeAccountId
            if (id > 0) dao.account(id) else null
        }
        if (account != null) {
            binding.accountLabel.text = account.label
            binding.accountRib.text = account.rib
        } else {
            binding.accountLabel.text = getString(R.string.virement_no_account)
            binding.accountRib.text = ""
        }

        val rows = loadRows()
        val total = rows.sumOf { it.salaireAPayer }
        binding.countLabel.text = rows.size.toString()
        binding.totalLabel.text = Format.money(total)
        binding.amountWords.text = if (rows.isEmpty()) "—" else MoneyWords.money(total)

        val enabled = rows.isNotEmpty()
        binding.pdfBtn.isEnabled = enabled
        binding.excelBtn.isEnabled = enabled
    }

    private suspend fun loadRows(): List<PayrollRow> {
        val base = settings.joursBase
        return withContext(Dispatchers.IO) {
            val employees = dao.activeEmployees()
                .filter { typeFilter.isEmpty() || it.typeVirement == typeFilter }
                .filter { lieuFilter.isEmpty() || it.lieuTravail.trim() == lieuFilter }
            val pointages = dao.pointages(year, month).associateBy { it.employeeId }
            employees.map { e ->
                PayrollRow(e, pointages[e.id]?.jours ?: Pointage.DEFAULT_JOURS, base)
            }
        }
    }

    private fun export(pdf: Boolean) = lifecycleScope.launch {
        val rows = loadRows()
        if (rows.isEmpty()) {
            Toast.makeText(this@VirementActivity, R.string.virement_empty, Toast.LENGTH_SHORT).show()
            return@launch
        }
        val account = withContext(Dispatchers.IO) {
            val id = settings.activeAccountId
            if (id > 0) dao.account(id) else null
        }
        val data = DocumentExporter.OrderData(
            societe = settings.societe,
            manager = settings.manager,
            bankName = settings.bankName,
            bankAgency = settings.bankAgency,
            reference = settings.reference,
            ville = settings.ville,
            accountLabel = account?.label ?: "",
            accountRib = account?.rib ?: "",
            year = year,
            month = month,
            rows = rows,
            typeFilter = typeFilter,
            lieuFilter = lieuFilter
        )

        try {
            val file = withContext(Dispatchers.IO) {
                if (pdf) {
                    val logo = BitmapFactory.decodeResource(resources, R.drawable.logo_reco_restau)
                    DocumentExporter.buildPdf(this@VirementActivity, data, logo)
                } else {
                    DocumentExporter.buildCsv(this@VirementActivity, data)
                }
            }
            DocumentExporter.share(
                this@VirementActivity,
                file,
                if (pdf) "application/pdf" else "text/csv"
            )
        } catch (e: Exception) {
            Toast.makeText(this@VirementActivity, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_YEAR = "extra_year"
        const val EXTRA_MONTH = "extra_month"
    }
}
