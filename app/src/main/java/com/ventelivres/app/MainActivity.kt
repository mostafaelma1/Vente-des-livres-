package com.ventelivres.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ventelivres.app.databinding.ActivityMainBinding
import com.ventelivres.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val dao get() = (application as VenteApp).db.dao()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnInvoices.setOnClickListener {
            startActivity(Intent(this, InvoicesActivity::class.java))
        }
        binding.btnClients.setOnClickListener {
            startActivity(Intent(this, ClientsActivity::class.java))
        }
        binding.btnBooks.setOnClickListener {
            startActivity(Intent(this, BooksActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    private fun refreshSummary() = lifecycleScope.launch {
        val invoices = withContext(Dispatchers.IO) { dao.invoicesWithTotals() }
        val unpaid = invoices.sumOf { it.rest.coerceAtLeast(0.0) }
        val revenue = invoices.sumOf { it.total }
        binding.summaryUnpaid.text = Format.money(unpaid)
        binding.summaryRevenue.text = Format.money(revenue)
        binding.summaryCount.text = invoices.size.toString()
    }
}
