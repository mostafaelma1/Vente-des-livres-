package com.ventelivres.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ventelivres.app.R
import com.ventelivres.app.data.Client
import com.ventelivres.app.databinding.ItemClientBinding

class ClientAdapter(
    private val onClick: (Client) -> Unit
) : RecyclerView.Adapter<ClientAdapter.VH>() {

    private val items = mutableListOf<Client>()

    fun submit(list: List<Client>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemClientBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemClientBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.b.root.context
        holder.b.name.text = c.name
        val typeLabel = if (c.type == Client.TYPE_GROS)
            ctx.getString(R.string.type_gros) else ctx.getString(R.string.type_detail)
        val parts = mutableListOf(typeLabel)
        if (c.remisePercent > 0) parts.add("Remise ${trimPercent(c.remisePercent)}%")
        if (c.phone.isNotBlank()) parts.add(c.phone)
        holder.b.subtitle.text = parts.joinToString(" · ")
        holder.b.root.setOnClickListener { onClick(c) }
    }

    private fun trimPercent(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
