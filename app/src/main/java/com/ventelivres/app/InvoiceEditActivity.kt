package com.ventelivres.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ventelivres.app.data.Book
import com.ventelivres.app.data.Client
import com.ventelivres.app.data.Invoice
import com.ventelivres.app.data.InvoiceItem
import com.ventelivres.app.data.Payment
import com.ventelivres.app.databinding.ActivityInvoiceEditBinding
import com.ventelivres.app.databinding.DialogItemBinding
import com.ventelivres.app.databinding.DialogPaymentBinding
import com.ventelivres.app.databinding.ItemInvoiceLineBinding
import com.ventelivres.app.databinding.ItemPaymentBinding
import com.ventelivres.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class InvoiceEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInvoiceEditBinding
    private val dao get() = (application as VenteApp).db.dao()

    private var invoiceId: Long = 0
    private var clients: List<Client> = emptyList()
    private var books: List<Book> = emptyList()
    private var selectedClient: Client? = null
    private var invoiceDate: Long = System.currentTimeMillis()
    private var currentType: String = Client.TYPE_GROS

    private val workingItems = mutableListOf<InvoiceItem>()
    private val workingPayments = mutableListOf<Payment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInvoiceEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        invoiceId = intent.getLongExtra(EXTRA_INVOICE_ID, 0L)

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = if (invoiceId == 0L)
            getString(R.string.new_invoice) else "Facture #$invoiceId"

        setupTypeToggle()
        binding.date.setOnClickListener { pickDate() }
        binding.remise.addTextChangedListener(simpleWatcher { recomputeTotals() })
        binding.addItem.setOnClickListener { showItemDialog(null) }
        binding.addPayment.setOnClickListener { showPaymentDialog() }
        binding.save.setOnClickListener { save() }
        binding.share.setOnClickListener { shareInvoice() }

        if (invoiceId != 0L) {
            binding.toolbar.menu.add(getString(R.string.delete)).setOnMenuItemClickListener {
                confirmDeleteInvoice(); true
            }
        }

        updateDateField()
        loadData()
    }

    private fun setupTypeToggle() {
        binding.typeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentType = if (checkedId == binding.typeDetail.id) Client.TYPE_DETAIL else Client.TYPE_GROS
        }
        binding.typeGroup.check(binding.typeGros.id)
    }

    private fun loadData() = lifecycleScope.launch {
        clients = withContext(Dispatchers.IO) { dao.clients() }
        books = withContext(Dispatchers.IO) { dao.books() }
        setupClientPicker()

        if (invoiceId != 0L) {
            val invoice = withContext(Dispatchers.IO) { dao.invoice(invoiceId) }
            val items = withContext(Dispatchers.IO) { dao.items(invoiceId) }
            val payments = withContext(Dispatchers.IO) { dao.payments(invoiceId) }
            if (invoice != null) bindInvoice(invoice, items, payments)
        }
        renderItems()
        renderPayments()
        recomputeTotals()
    }

    private fun setupClientPicker() {
        val names = clients.map { clientLabel(it) }
        binding.clientPicker.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        )
        binding.clientPicker.setOnItemClickListener { _, _, position, _ ->
            val client = clients[position]
            selectedClient = client
            // Apply the client's defaults the first time they're picked.
            currentType = client.type
            binding.typeGroup.check(
                if (client.type == Client.TYPE_DETAIL) binding.typeDetail.id else binding.typeGros.id
            )
            if (binding.remise.text.isNullOrBlank() && client.remisePercent > 0) {
                binding.remise.setText(trim(client.remisePercent))
            }
        }
    }

    private fun bindInvoice(invoice: Invoice, items: List<InvoiceItem>, payments: List<Payment>) {
        invoiceDate = invoice.date
        currentType = invoice.type
        binding.typeGroup.check(
            if (invoice.type == Client.TYPE_DETAIL) binding.typeDetail.id else binding.typeGros.id
        )
        if (invoice.remisePercent > 0) binding.remise.setText(trim(invoice.remisePercent))
        binding.note.setText(invoice.note)
        updateDateField()

        selectedClient = clients.firstOrNull { it.id == invoice.clientId }
        binding.clientPicker.setText(
            selectedClient?.let { clientLabel(it) } ?: invoice.clientName, false
        )

        workingItems.clear()
        workingItems.addAll(items)
        workingPayments.clear()
        workingPayments.addAll(payments)
    }

    // ---------- Items ----------

    private fun renderItems() {
        binding.itemsContainer.removeAllViews()
        workingItems.forEachIndexed { index, item ->
            val row = ItemInvoiceLineBinding.inflate(layoutInflater, binding.itemsContainer, false)
            row.title.text = item.title
            row.detail.text = "${item.quantity} × ${Format.money(item.unitPrice)}"
            row.lineTotal.text = Format.money(item.lineTotal)
            row.root.setOnClickListener { showItemDialog(index) }
            row.delete.setOnClickListener {
                workingItems.removeAt(index)
                renderItems()
                recomputeTotals()
            }
            binding.itemsContainer.addView(row.root)
        }
    }

    private fun showItemDialog(editIndex: Int?) {
        val d = DialogItemBinding.inflate(layoutInflater)
        val existing = editIndex?.let { workingItems[it] }

        val bookLabels = books.map { "${it.title} — ${Format.money(priceFor(it))}" }
        d.bookPicker.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, bookLabels))
        d.bookPicker.setOnItemClickListener { _, _, position, _ ->
            val book = books[position]
            d.title.setText(book.title)
            d.unitPrice.setText(trim(priceFor(book)))
        }

        existing?.let {
            d.title.setText(it.title)
            d.unitPrice.setText(trim(it.unitPrice))
            d.quantity.setText(it.quantity.toString())
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_item)
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = d.title.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) {
                    d.title.error = getString(R.string.err_name_required)
                    return@setOnClickListener
                }
                val price = Format.parseNumber(d.unitPrice.text?.toString())
                val qty = d.quantity.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val item = (existing ?: InvoiceItem(invoiceId = invoiceId, title = title, unitPrice = price))
                    .copy(title = title, unitPrice = price, quantity = qty)
                if (editIndex != null) workingItems[editIndex] = item else workingItems.add(item)
                dialog.dismiss()
                renderItems()
                recomputeTotals()
            }
        }
        dialog.show()
    }

    /** Default price suggestion for a book based on the current sale type. */
    private fun priceFor(book: Book): Double =
        if (currentType == Client.TYPE_DETAIL) book.priceDetail else book.priceGros

    // ---------- Payments ----------

    private fun renderPayments() {
        binding.paymentsContainer.removeAllViews()
        workingPayments.forEachIndexed { index, payment ->
            val row = ItemPaymentBinding.inflate(layoutInflater, binding.paymentsContainer, false)
            row.amount.text = Format.money(payment.amount)
            row.detail.text = "${methodLabel(payment.method)} · ${Format.date(payment.date)}"
            row.delete.setOnClickListener {
                workingPayments.removeAt(index)
                renderPayments()
                recomputeTotals()
            }
            binding.paymentsContainer.addView(row.root)
        }
    }

    private fun showPaymentDialog() {
        val d = DialogPaymentBinding.inflate(layoutInflater)
        val methodCodes = listOf(
            Payment.METHOD_CASH, Payment.METHOD_CHEQUE, Payment.METHOD_TRANSFER, Payment.METHOD_OTHER
        )
        val methodLabels = methodCodes.map { methodLabel(it) }
        d.method.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, methodLabels))
        d.method.setText(methodLabels[0], false)
        var selectedMethod = methodCodes[0]
        d.method.setOnItemClickListener { _, _, position, _ -> selectedMethod = methodCodes[position] }

        // Pre-fill with the remaining balance for convenience.
        val rest = computeRest()
        if (rest > 0) d.amount.setText(trim(rest))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_payment)
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amount = Format.parseNumber(d.amount.text?.toString())
                if (amount <= 0) {
                    d.amount.error = getString(R.string.payment_amount)
                    return@setOnClickListener
                }
                workingPayments.add(
                    Payment(
                        invoiceId = invoiceId,
                        amount = amount,
                        method = selectedMethod,
                        note = d.note.text?.toString()?.trim().orEmpty()
                    )
                )
                dialog.dismiss()
                renderPayments()
                recomputeTotals()
            }
        }
        dialog.show()
    }

    // ---------- Totals ----------

    private fun currentRemisePercent(): Double = Format.parseNumber(binding.remise.text?.toString())

    private fun computeSubtotal(): Double = workingItems.sumOf { it.lineTotal }
    private fun computeTotal(): Double {
        val sub = computeSubtotal()
        return sub - sub * currentRemisePercent() / 100.0
    }

    private fun computePaid(): Double = workingPayments.sumOf { it.amount }
    private fun computeRest(): Double = computeTotal() - computePaid()

    private fun recomputeTotals() {
        val sub = computeSubtotal()
        binding.subtotal.text = Format.money(sub)
        binding.remiseAmount.text = Format.money(sub * currentRemisePercent() / 100.0)
        binding.total.text = Format.money(computeTotal())
        binding.paid.text = Format.money(computePaid())
        binding.rest.text = Format.money(computeRest())
    }

    // ---------- Save / delete / share ----------

    private fun save() {
        val client = selectedClient
        if (client == null) {
            binding.clientPicker.error = getString(R.string.err_no_client)
            return
        }
        if (workingItems.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.err_no_items)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val invoice = Invoice(
            id = invoiceId,
            clientId = client.id,
            clientName = client.name,
            date = invoiceDate,
            type = currentType,
            remisePercent = currentRemisePercent(),
            note = binding.note.text?.toString()?.trim().orEmpty()
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val newId = dao.upsertInvoice(invoice)
                val id = if (invoiceId == 0L) newId else invoiceId
                // Rewrite the child rows so the saved state matches the screen.
                dao.items(id).forEach { dao.deleteItem(it) }
                workingItems.forEach { dao.upsertItem(it.copy(id = 0, invoiceId = id)) }
                dao.payments(id).forEach { dao.deletePayment(it) }
                workingPayments.forEach { dao.upsertPayment(it.copy(id = 0, invoiceId = id)) }
            }
            finish()
        }
    }

    private fun confirmDeleteInvoice() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        dao.invoice(invoiceId)?.let { dao.deleteInvoice(it) }
                    }
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareInvoice() {
        val client = selectedClient
        if (client == null || workingItems.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setMessage(if (client == null) R.string.err_no_client else R.string.err_no_items)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val text = buildInvoiceText(client)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_invoice)))
    }

    private fun buildInvoiceText(client: Client): String {
        val sb = StringBuilder()
        sb.appendLine("=== FACTURE / فاتورة ===")
        if (invoiceId != 0L) sb.appendLine("N° : $invoiceId")
        sb.appendLine("Date : ${Format.date(invoiceDate)}")
        sb.appendLine("Client : ${client.name}")
        if (client.phone.isNotBlank()) sb.appendLine("Tél : ${client.phone}")
        sb.appendLine("--------------------------------")
        workingItems.forEach {
            sb.appendLine("${it.title}")
            sb.appendLine("   ${it.quantity} × ${Format.money(it.unitPrice)} = ${Format.money(it.lineTotal)}")
        }
        sb.appendLine("--------------------------------")
        sb.appendLine("Sous-total : ${Format.money(computeSubtotal())}")
        if (currentRemisePercent() > 0) {
            sb.appendLine("Remise ${trim(currentRemisePercent())}% : -${Format.money(computeSubtotal() * currentRemisePercent() / 100.0)}")
        }
        sb.appendLine("TOTAL : ${Format.money(computeTotal())}")
        sb.appendLine("Payé : ${Format.money(computePaid())}")
        sb.appendLine("RESTE : ${Format.money(computeRest())}")
        val note = binding.note.text?.toString()?.trim().orEmpty()
        if (note.isNotBlank()) {
            sb.appendLine("--------------------------------")
            sb.appendLine("Note : $note")
        }
        return sb.toString()
    }

    // ---------- Helpers ----------

    private fun pickDate() {
        val cal = Calendar.getInstance().apply { timeInMillis = invoiceDate }
        android.app.DatePickerDialog(
            this,
            { _, year, month, day ->
                val c = Calendar.getInstance()
                c.set(year, month, day, 12, 0, 0)
                invoiceDate = c.timeInMillis
                updateDateField()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateField() {
        binding.date.setText(Format.date(invoiceDate))
    }

    private fun clientLabel(c: Client): String {
        val type = if (c.type == Client.TYPE_GROS)
            getString(R.string.type_gros) else getString(R.string.type_detail)
        return "${c.name} · $type"
    }

    private fun methodLabel(code: String): String = when (code) {
        Payment.METHOD_CASH -> getString(R.string.method_cash)
        Payment.METHOD_CHEQUE -> getString(R.string.method_cheque)
        Payment.METHOD_TRANSFER -> getString(R.string.method_transfer)
        else -> getString(R.string.method_other)
    }

    private fun trim(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun simpleWatcher(after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = after()
    }

    companion object {
        const val EXTRA_INVOICE_ID = "invoice_id"
    }
}
