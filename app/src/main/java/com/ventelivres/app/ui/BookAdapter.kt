package com.ventelivres.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ventelivres.app.data.Book
import com.ventelivres.app.databinding.ItemBookBinding
import com.ventelivres.app.util.Format

class BookAdapter(
    private val onClick: (Book) -> Unit
) : RecyclerView.Adapter<BookAdapter.VH>() {

    private val items = mutableListOf<Book>()

    fun submit(list: List<Book>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemBookBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val book = items[position]
        holder.b.title.text = book.title
        holder.b.author.text = book.author
        holder.b.author.visibility = if (book.author.isBlank())
            android.view.View.GONE else android.view.View.VISIBLE
        holder.b.prices.text = "Gros ${Format.money(book.priceGros)} · Détail ${Format.money(book.priceDetail)}"
        holder.b.root.setOnClickListener { onClick(book) }
    }
}
