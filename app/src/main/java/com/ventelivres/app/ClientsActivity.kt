package com.ventelivres.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ventelivres.app.data.Client
import com.ventelivres.app.databinding.ActivityListBinding
import com.ventelivres.app.databinding.DialogClientBinding
import com.ventelivres.app.ui.ClientAdapter
import com.ventelivres.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClientsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private val dao get() = (application as VenteApp).db.dao()
    private val adapter = ClientAdapter { showClientDialog(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.clients_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.text = getString(R.string.new_client)
        binding.fab.setOnClickListener { showClientDialog(null) }
        binding.emptyText.text = getString(R.string.no_clients)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() = lifecycleScope.launch {
        val list = withContext(Dispatchers.IO) { dao.clients() }
        adapter.submit(list)
        binding.empty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showClientDialog(existing: Client?) {
        val dialogBinding = DialogClientBinding.inflate(layoutInflater)
        dialogBinding.name.setText(existing?.name ?: "")
        dialogBinding.phone.setText(existing?.phone ?: "")
        dialogBinding.notes.setText(existing?.notes ?: "")
        if (existing != null && existing.remisePercent > 0) {
            dialogBinding.remise.setText(trim(existing.remisePercent))
        }
        // Default type = GROS for new clients.
        val isDetail = existing?.type == Client.TYPE_DETAIL
        dialogBinding.typeGroup.check(if (isDetail) dialogBinding.typeDetail.id else dialogBinding.typeGros.id)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.new_client else R.string.edit)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.delete) { _, _ -> confirmDelete(existing) }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.name.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    dialogBinding.name.error = getString(R.string.err_name_required)
                    return@setOnClickListener
                }
                val type = if (dialogBinding.typeGroup.checkedButtonId == dialogBinding.typeDetail.id)
                    Client.TYPE_DETAIL else Client.TYPE_GROS
                val client = (existing ?: Client(name = name)).copy(
                    name = name,
                    phone = dialogBinding.phone.text?.toString()?.trim().orEmpty(),
                    type = type,
                    remisePercent = Format.parseNumber(dialogBinding.remise.text?.toString()),
                    notes = dialogBinding.notes.text?.toString()?.trim().orEmpty()
                )
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.upsertClient(client) }
                    dialog.dismiss()
                    refresh()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(client: Client) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.deleteClient(client) }
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun trim(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
