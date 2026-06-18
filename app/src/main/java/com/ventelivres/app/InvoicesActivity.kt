package com.ventelivres.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ventelivres.app.databinding.ActivityListBinding
import com.ventelivres.app.ui.InvoiceAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InvoicesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private val dao get() = (application as VenteApp).db.dao()
    private val adapter = InvoiceAdapter { openInvoice(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.invoices_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.text = getString(R.string.new_invoice)
        binding.fab.setOnClickListener { openInvoice(0L) }
        binding.emptyText.text = getString(R.string.no_invoices)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() = lifecycleScope.launch {
        val list = withContext(Dispatchers.IO) { dao.invoicesWithTotals() }
        adapter.submit(list)
        binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openInvoice(id: Long) {
        startActivity(Intent(this, InvoiceEditActivity::class.java).apply {
            putExtra(InvoiceEditActivity.EXTRA_INVOICE_ID, id)
        })
    }
}
