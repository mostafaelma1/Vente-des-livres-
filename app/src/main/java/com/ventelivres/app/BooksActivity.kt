package com.ventelivres.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ventelivres.app.data.Book
import com.ventelivres.app.databinding.ActivityListBinding
import com.ventelivres.app.databinding.DialogBookBinding
import com.ventelivres.app.ui.BookAdapter
import com.ventelivres.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BooksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private val dao get() = (application as VenteApp).db.dao()
    private val adapter = BookAdapter { showBookDialog(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.books_title)
        binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.text = getString(R.string.new_book)
        binding.fab.setOnClickListener { showBookDialog(null) }
        binding.empty.text = getString(R.string.no_books)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() = lifecycleScope.launch {
        val list = withContext(Dispatchers.IO) { dao.books() }
        adapter.submit(list)
        binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showBookDialog(existing: Book?) {
        val dialogBinding = DialogBookBinding.inflate(layoutInflater)
        dialogBinding.title.setText(existing?.title ?: "")
        dialogBinding.author.setText(existing?.author ?: "")
        if (existing != null && existing.priceGros > 0) dialogBinding.priceGros.setText(trim(existing.priceGros))
        if (existing != null && existing.priceDetail > 0) dialogBinding.priceDetail.setText(trim(existing.priceDetail))

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.new_book else R.string.edit)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.delete) { _, _ -> confirmDelete(existing) }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = dialogBinding.title.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) {
                    dialogBinding.title.error = getString(R.string.err_name_required)
                    return@setOnClickListener
                }
                val book = (existing ?: Book(title = title)).copy(
                    title = title,
                    author = dialogBinding.author.text?.toString()?.trim().orEmpty(),
                    priceGros = Format.parseNumber(dialogBinding.priceGros.text?.toString()),
                    priceDetail = Format.parseNumber(dialogBinding.priceDetail.text?.toString())
                )
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.upsertBook(book) }
                    dialog.dismiss()
                    refresh()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(book: Book) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.deleteBook(book) }
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun trim(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
