package com.ventelivres.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ventelivres.app.data.Employee
import com.ventelivres.app.databinding.ItemEmployeeBinding
import com.ventelivres.app.util.Format

class EmployeeAdapter(
    private val onClick: (Employee) -> Unit
) : RecyclerView.Adapter<EmployeeAdapter.VH>() {

    private val items = mutableListOf<Employee>()

    fun submit(list: List<Employee>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemEmployeeBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemEmployeeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.b.name.text = e.nomComplet
        val parts = mutableListOf<String>()
        if (e.poste.isNotBlank()) parts.add(e.poste)
        if (e.lieuTravail.isNotBlank()) parts.add(e.lieuTravail)
        if (!e.actif) parts.add("Inactif")
        holder.b.subtitle.text = parts.joinToString(" · ")
        holder.b.badge.text = Format.money(e.salaireMensuel)
        holder.b.root.setOnClickListener { onClick(e) }
    }
}
