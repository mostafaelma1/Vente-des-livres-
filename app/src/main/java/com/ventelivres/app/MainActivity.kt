package com.ventelivres.app

import android.content.Intent
import android.os.Bundle
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ventelivres.app.data.Pointage
import com.ventelivres.app.databinding.ActivityMainBinding
import com.ventelivres.app.databinding.IncludeMenuCardBinding
import com.ventelivres.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val dao get() = (application as VenteApp).db.dao()
    private val settings by lazy { (application as VenteApp).settings }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCard(
            binding.btnEmployees, R.drawable.ic_clients, R.color.tint_indigo,
            R.string.menu_employees, R.string.menu_employees_desc
        ) { startActivity(Intent(this, EmployeesActivity::class.java)) }

        setupCard(
            binding.btnPayroll, R.drawable.ic_calendar, R.color.tint_amber,
            R.string.menu_payroll, R.string.menu_payroll_desc
        ) { startActivity(Intent(this, PayrollActivity::class.java)) }

        setupCard(
            binding.btnVirement, R.drawable.ic_receipt, R.color.tint_teal,
            R.string.menu_virement, R.string.menu_virement_desc
        ) { startActivity(Intent(this, VirementActivity::class.java)) }

        setupCard(
            binding.btnSettings, R.drawable.ic_settings, R.color.tint_green,
            R.string.menu_settings, R.string.menu_settings_desc
        ) { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    private fun setupCard(
        card: IncludeMenuCardBinding,
        @DrawableRes icon: Int,
        @ColorRes tint: Int,
        @StringRes title: Int,
        @StringRes desc: Int,
        onClick: () -> Unit
    ) {
        card.icon.setImageResource(icon)
        card.icon.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, tint))
        card.title.setText(title)
        card.desc.setText(desc)
        card.root.setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    private fun refreshSummary() = lifecycleScope.launch {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val base = settings.joursBase

        val (count, total) = withContext(Dispatchers.IO) {
            val employees = dao.activeEmployees()
            val pointages = dao.pointages(year, month).associateBy { it.employeeId }
            val sum = employees.sumOf { e ->
                val jours = pointages[e.id]?.jours ?: Pointage.DEFAULT_JOURS
                val daily = if (base > 0) e.salaireMensuel / base else 0.0
                Math.round(daily * jours * 100.0) / 100.0
            }
            employees.size to sum
        }

        binding.summaryCount.text = count.toString()
        binding.summaryTotal.text = Format.money(total)
        binding.summaryPeriod.text = Format.period(year, month)
    }
}
