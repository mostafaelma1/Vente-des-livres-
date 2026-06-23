package com.prixref.ao.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
        holder.binding.tvRef.text = item.reference.ifBlank { "(sans référence)" }
        holder.binding.tvObjet.text = item.objet.ifBlank { "Objet non renseigné" }
        holder.binding.tvMeta.text =
            "Prix réf. : ${Format.money(item.referencePrice)}  •  ${item.probableWinner.ifBlank { "—" }}"
        holder.binding.tvDate.text = Format.dateTime(item.date)
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.btnDelete.setOnClickListener { onDelete(item) }
    }
}
