package com.ventelivres.app.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ventelivres.app.data.Employee
import com.ventelivres.app.databinding.ItemPayrollBinding
import com.ventelivres.app.util.Format

/**
 * One editable row per employee: the number of worked days is typed in and the
 * salary to pay is recomputed live.
 */
class PayrollAdapter(
    private val onChanged: (Row) -> Unit
) : RecyclerView.Adapter<PayrollAdapter.VH>() {

    data class Row(val employee: Employee, var jours: Double)

    private val rows = mutableListOf<Row>()
    private var joursBase: Double = 26.0

    fun submit(list: List<Row>, base: Double) {
        joursBase = base
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    fun total(): Double = rows.sumOf { salaire(it) }

    private fun salaire(r: Row): Double {
        val daily = if (joursBase > 0) r.employee.salaireMensuel / joursBase else 0.0
        return Math.round(daily * r.jours * 100.0) / 100.0
    }

    inner class VH(val b: ItemPayrollBinding) : RecyclerView.ViewHolder(b.root) {
        var watcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPayrollBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val e = row.employee
        val ctx = holder.b.root.context

        holder.b.name.text = e.nomComplet
        val daily = if (joursBase > 0) e.salaireMensuel / joursBase else 0.0
        holder.b.sub.text = "${Format.money(e.salaireMensuel)} / mois · ${Format.amount(daily)} DH/j"

        // Detach the previous watcher before resetting the text on a recycled row.
        holder.watcher?.let { holder.b.jours.removeTextChangedListener(it) }
        holder.b.jours.setText(Format.trimDays(row.jours))
        holder.b.salaire.text = Format.money(salaire(row))

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                row.jours = Format.parseNumber(s?.toString())
                holder.b.salaire.text = Format.money(salaire(row))
                onChanged(row)
            }
        }
        holder.b.jours.addTextChangedListener(watcher)
        holder.watcher = watcher
    }
}
