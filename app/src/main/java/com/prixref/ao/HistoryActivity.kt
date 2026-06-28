package com.prixref.ao

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prixref.ao.data.AnalysisEntity
import com.prixref.ao.data.AppDatabase
import com.prixref.ao.databinding.ActivityHistoryBinding
import com.prixref.ao.ui.HistoryAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = HistoryAdapter(onClick = ::openAnalysis, onDelete = ::confirmDelete)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.etSearch.doAfterTextChanged { load(it?.toString().orEmpty()) }
    }

    override fun onResume() {
        super.onResume()
        load(binding.etSearch.text?.toString().orEmpty())
    }

    private fun load(query: String) {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@HistoryActivity).analysisDao()
            val list = withContext(Dispatchers.IO) {
                if (query.isBlank()) dao.getAll() else dao.search(query.trim())
            }
            adapter.submit(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun openAnalysis(item: AnalysisEntity) {
        startActivity(
            Intent(this, ResultActivity::class.java)
                .putExtra(ResultActivity.EXTRA_RESULT_JSON, item.json)
                .putExtra(ResultActivity.EXTRA_FROM_HISTORY, true)
        )
    }

    private fun confirmDelete(item: AnalysisEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.hist_delete_title))
            .setMessage(item.reference.ifBlank { getString(R.string.hist_no_ref) })
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.get(this@HistoryActivity).analysisDao().delete(item)
                    }
                    load(binding.etSearch.text?.toString().orEmpty())
                }
            }
            .show()
    }
}
