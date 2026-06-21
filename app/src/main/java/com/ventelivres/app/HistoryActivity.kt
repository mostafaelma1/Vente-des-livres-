package com.ventelivres.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ventelivres.app.databinding.ActivityListBinding
import com.ventelivres.app.ui.HistoryAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private val dao get() = (application as VenteApp).db.dao()
    private val settings by lazy { (application as VenteApp).settings }
    private val adapter = HistoryAdapter { openMonth(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.history_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.visibility = View.GONE
        binding.emptyText.text = getString(R.string.history_empty)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() = lifecycleScope.launch {
        val base = settings.joursBase
        val periods = withContext(Dispatchers.IO) {
            val salaries = dao.employees().associate { it.id to it.salaireMensuel }
            dao.allPointages()
                .groupBy { it.year * 100 + it.month }
                .map { (key, list) ->
                    val total = list.sumOf { p ->
                        val salaire = salaries[p.employeeId] ?: 0.0
                        val daily = if (base > 0) salaire / base else 0.0
                        Math.round(daily * p.jours * 100.0) / 100.0
                    }
                    HistoryAdapter.Period(key / 100, key % 100, list.size, total)
                }
                .sortedByDescending { it.year * 100 + it.month }
        }
        adapter.submit(periods)
        binding.empty.visibility = if (periods.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openMonth(p: HistoryAdapter.Period) {
        startActivity(Intent(this, VirementActivity::class.java).apply {
            putExtra(VirementActivity.EXTRA_YEAR, p.year)
            putExtra(VirementActivity.EXTRA_MONTH, p.month)
        })
    }
}
