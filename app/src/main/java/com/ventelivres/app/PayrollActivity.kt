package com.ventelivres.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import android.widget.Toast
import com.ventelivres.app.data.Pointage
import com.ventelivres.app.databinding.ActivityPayrollBinding
import com.ventelivres.app.ui.PayrollAdapter
import com.ventelivres.app.util.DataExport
import com.ventelivres.app.util.DocumentExporter
import com.ventelivres.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class PayrollActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPayrollBinding
    private val dao get() = (application as VenteApp).db.dao()
    private val settings by lazy { (application as VenteApp).settings }
    private val adapter = PayrollAdapter { updateTotal() }

    private var year = 0
    private var month = 0
    private var currentRows: List<PayrollAdapter.Row> = emptyList()
    private val pointageIds = HashMap<Long, Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPayrollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.payroll_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.toolbar.inflateMenu(R.menu.menu_payroll)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_export_payroll) { exportMonth(); true } else false
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        val cal = Calendar.getInstance()
        year = cal.get(Calendar.YEAR)
        month = cal.get(Calendar.MONTH) + 1

        binding.prevMonth.setOnClickListener { changeMonth(-1) }
        binding.nextMonth.setOnClickListener { changeMonth(1) }

        load()
    }

    private fun changeMonth(delta: Int) {
        saveCurrent()
        month += delta
        if (month < 1) { month = 12; year-- }
        if (month > 12) { month = 1; year++ }
        load()
    }

    private fun load() = lifecycleScope.launch {
        val base = settings.joursBase
        binding.periodLabel.text = Format.period(year, month)
        binding.baseLabel.text = getString(R.string.payroll_base, Format.trimDays(base))

        val rows = withContext(Dispatchers.IO) {
            val employees = dao.activeEmployees()
            val pointages = dao.pointages(year, month).associateBy { it.employeeId }
            pointageIds.clear()
            employees.map { e ->
                val p = pointages[e.id]
                if (p != null) pointageIds[e.id] = p.id
                PayrollAdapter.Row(e, p?.jours ?: Pointage.DEFAULT_JOURS)
            }
        }
        currentRows = rows
        adapter.submit(rows, base)
        val empty = rows.isEmpty()
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        updateTotal()
    }

    private fun updateTotal() {
        binding.totalLabel.text = Format.money(adapter.total())
    }

    /** Exports the current month's pointage + salaries as an Excel-compatible CSV. */
    private fun exportMonth() {
        if (currentRows.isEmpty()) {
            Toast.makeText(this, R.string.no_employees, Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrent()
        val items = currentRows.map { it.employee to it.jours }
        val content = DataExport.payrollCsv(settings.societe, Format.period(year, month), settings.joursBase, items)
        val file = DataExport.writeCache(this, "Pointage_${year}_${month}.csv", content)
        DocumentExporter.share(this, file, "text/csv")
    }

    /** Persists the worked days of every row for the current month. */
    private fun saveCurrent() {
        val rows = currentRows
        val y = year
        val m = month
        if (rows.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            rows.forEach { row ->
                val existingId = pointageIds[row.employee.id] ?: 0L
                val id = dao.upsertPointage(
                    Pointage(id = existingId, employeeId = row.employee.id, year = y, month = m, jours = row.jours)
                )
                pointageIds[row.employee.id] = if (existingId == 0L) id else existingId
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrent()
    }
}
