package com.ventelivres.app

import android.content.Intent
import android.os.Bundle
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ventelivres.app.databinding.ActivityMainBinding
import com.ventelivres.app.databinding.IncludeMenuCardBinding
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

        setupCard(
            binding.btnInvoices, R.drawable.ic_invoice, R.color.tint_indigo,
            R.string.menu_invoices, R.string.menu_invoices_desc
        ) { startActivity(Intent(this, InvoicesActivity::class.java)) }

        setupCard(
            binding.btnClients, R.drawable.ic_clients, R.color.tint_teal,
            R.string.menu_clients, R.string.menu_clients_desc
        ) { startActivity(Intent(this, ClientsActivity::class.java)) }

        setupCard(
            binding.btnBooks, R.drawable.ic_book, R.color.tint_amber,
            R.string.menu_books, R.string.menu_books_desc
        ) { startActivity(Intent(this, BooksActivity::class.java)) }
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
        val invoices = withContext(Dispatchers.IO) { dao.invoicesWithTotals() }
        val unpaid = invoices.sumOf { it.rest.coerceAtLeast(0.0) }
        val revenue = invoices.sumOf { it.total }
        binding.summaryUnpaid.text = Format.money(unpaid)
        binding.summaryRevenue.text = Format.money(revenue)
        binding.summaryCount.text = invoices.size.toString()
    }
}
