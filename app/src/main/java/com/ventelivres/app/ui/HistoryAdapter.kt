package com.ventelivres.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ventelivres.app.R
import com.ventelivres.app.databinding.ItemHistoryBinding
import com.ventelivres.app.util.Format

class HistoryAdapter(
    private val onClick: (Period) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    /** One archived month with its computed totals. */
    data class Period(val year: Int, val month: Int, val count: Int, val total: Double)

    private val items = mutableListOf<Period>()

    fun submit(list: List<Period>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val ctx = holder.b.root.context
        holder.b.period.text = Format.period(p.year, p.month)
        holder.b.subtitle.text = ctx.getString(R.string.history_subtitle, p.count, Format.money(p.total))
        holder.b.root.setOnClickListener { onClick(p) }
    }
}
