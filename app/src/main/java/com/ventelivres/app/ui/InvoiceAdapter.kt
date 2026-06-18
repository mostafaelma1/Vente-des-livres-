package com.ventelivres.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ventelivres.app.R
import com.ventelivres.app.data.InvoiceWithTotals
import com.ventelivres.app.databinding.ItemInvoiceBinding
import com.ventelivres.app.util.Format

class InvoiceAdapter(
    private val onClick: (Long) -> Unit
) : RecyclerView.Adapter<InvoiceAdapter.VH>() {

    private val items = mutableListOf<InvoiceWithTotals>()

    fun submit(list: List<InvoiceWithTotals>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemInvoiceBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemInvoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val ctx = holder.b.root.context
        holder.b.clientName.text = row.invoice.clientName
        holder.b.meta.text = "Facture #${row.invoice.id} · ${Format.date(row.invoice.date)}"
        holder.b.total.text = "Total : ${Format.money(row.total)}"
        holder.b.rest.text = "Reste : ${Format.money(row.rest)}"

        val (label, colorRes) = when (row.status) {
            InvoiceWithTotals.STATUS_PAID -> R.string.status_paid to R.color.paid
            InvoiceWithTotals.STATUS_PARTIAL -> R.string.status_partial to R.color.partial
            else -> R.string.status_unpaid to R.color.unpaid
        }
        holder.b.badge.text = ctx.getString(label)
        val color = ContextCompat.getColor(ctx, colorRes)
        holder.b.badge.backgroundTintList = ColorStateList.valueOf(color)
        holder.b.rest.setTextColor(color)

        holder.b.root.setOnClickListener { onClick(row.invoice.id) }
    }
}
