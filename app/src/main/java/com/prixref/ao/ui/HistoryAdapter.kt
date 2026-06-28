package com.prixref.ao.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.prixref.ao.R
import com.prixref.ao.data.AnalysisEntity
import com.prixref.ao.databinding.ItemHistoryBinding
import com.prixref.ao.util.Format

class HistoryAdapter(
    private val onClick: (AnalysisEntity) -> Unit,
    private val onDelete: (AnalysisEntity) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<AnalysisEntity>()

    fun submit(list: List<AnalysisEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.binding.root.context
        holder.binding.tvRef.text = item.reference.ifBlank { ctx.getString(R.string.hist_item_no_ref) }
        holder.binding.tvObjet.text = item.objet.ifBlank { ctx.getString(R.string.result_objet_empty) }
        holder.binding.tvMeta.text = ctx.getString(
            R.string.hist_meta, Format.money(item.referencePrice), item.probableWinner.ifBlank { "—" },
        )
        holder.binding.tvDate.text = Format.dateTime(item.date)
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.btnDelete.setOnClickListener { onDelete(item) }
    }
}
