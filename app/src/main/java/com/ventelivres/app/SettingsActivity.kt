package com.ventelivres.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ventelivres.app.data.CompanyAccount
import com.ventelivres.app.databinding.ActivitySettingsBinding
import com.ventelivres.app.databinding.DialogAccountBinding
import com.ventelivres.app.databinding.ItemAccountBinding
import com.ventelivres.app.util.DataExport
import com.ventelivres.app.util.DocumentExporter
import com.ventelivres.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val dao get() = (application as VenteApp).db.dao()
    private val settings by lazy { (application as VenteApp).settings }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { confirmRestore(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.settings_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.societe.setText(settings.societe)
        binding.manager.setText(settings.manager)
        binding.bank.setText(settings.bankName)
        binding.agency.setText(settings.bankAgency)
        binding.ville.setText(settings.ville)
        binding.reference.setText(settings.reference)
        binding.joursBase.setText(Format.trimDays(settings.joursBase))

        binding.addAccount.setOnClickListener { showAccountDialog(null) }
        binding.saveBtn.setOnClickListener { persistAndFinish() }
        binding.backupBtn.setOnClickListener { doBackup() }
        binding.restoreBtn.setOnClickListener { restoreLauncher.launch(arrayOf("*/*")) }

        loadAccounts()
    }

    /** Persist the form first, then export the whole database as a JSON backup. */
    private fun doBackup() = lifecycleScope.launch {
        persist()
        val json = withContext(Dispatchers.IO) {
            DataExport.backupJson(settings, dao.accounts(), dao.employees(), dao.allPointages())
        }
        val file = DataExport.writeCache(this@SettingsActivity, "Reco_Salaire_sauvegarde.json", json)
        DocumentExporter.share(this@SettingsActivity, file, "application/json")
    }

    private fun confirmRestore(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_restore)
            .setMessage(R.string.restore_confirm)
            .setPositiveButton(R.string.set_restore) { _, _ -> restore(uri) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restore(uri: Uri) = lifecycleScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull().orEmpty()
        }
        val data = DataExport.parseBackup(text)
        if (data == null) {
            Toast.makeText(this@SettingsActivity, R.string.restore_error, Toast.LENGTH_LONG).show()
            return@launch
        }
        withContext(Dispatchers.IO) {
            dao.deleteAllEmployees()   // cascades to pointages
            dao.deleteAllAccounts()
            data.accounts.forEach { dao.upsertAccount(it) }
            data.employees.forEach { dao.upsertEmployee(it) }
            data.pointages.forEach { dao.upsertPointage(it) }
            settings.societe = data.settings["societe"].orEmpty().ifBlank { settings.societe }
            settings.manager = data.settings["manager"].orEmpty()
            settings.bankName = data.settings["bankName"].orEmpty().ifBlank { settings.bankName }
            settings.bankAgency = data.settings["bankAgency"].orEmpty().ifBlank { settings.bankAgency }
            settings.reference = data.settings["reference"].orEmpty()
            settings.ville = data.settings["ville"].orEmpty().ifBlank { settings.ville }
            data.joursBase?.let { if (it > 0) settings.joursBase = it }
            data.activeAccountId?.let { settings.activeAccountId = it }
        }
        // Reflect restored settings in the form.
        binding.societe.setText(settings.societe)
        binding.manager.setText(settings.manager)
        binding.bank.setText(settings.bankName)
        binding.agency.setText(settings.bankAgency)
        binding.ville.setText(settings.ville)
        binding.reference.setText(settings.reference)
        binding.joursBase.setText(Format.trimDays(settings.joursBase))
        loadAccounts()
        Toast.makeText(this@SettingsActivity, R.string.restore_done, Toast.LENGTH_LONG).show()
    }

    private fun loadAccounts() {
        lifecycleScope.launch {
            val accounts = withContext(Dispatchers.IO) { dao.accounts() }
            binding.accountsContainer.removeAllViews()
            binding.noAccounts.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE

            accounts.forEach { acc ->
                val row = ItemAccountBinding.inflate(layoutInflater, binding.accountsContainer, false)
                row.label.text = acc.label
                row.rib.text = acc.rib
                row.radio.isChecked = acc.id == settings.activeAccountId
                val select: () -> Unit = {
                    settings.activeAccountId = acc.id
                    loadAccounts()
                }
                row.radio.setOnClickListener { select() }
                row.label.setOnClickListener { select() }
                row.rib.setOnClickListener { select() }
                row.editIcon.setOnClickListener { showAccountDialog(acc) }
                binding.accountsContainer.addView(row.root)
            }
        }
    }

    private fun showAccountDialog(existing: CompanyAccount?) {
        val d = DialogAccountBinding.inflate(layoutInflater)
        d.label.setText(existing?.label ?: "")
        d.rib.setText(existing?.rib ?: "")

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.set_add_account else R.string.edit)
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.delete) { _, _ -> deleteAccount(existing) }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = d.label.text?.toString()?.trim().orEmpty()
                val rib = d.rib.text?.toString()?.trim().orEmpty()
                if (label.isEmpty() && rib.isEmpty()) {
                    d.label.error = getString(R.string.err_name_required)
                    return@setOnClickListener
                }
                val account = (existing ?: CompanyAccount(label = label, rib = rib))
                    .copy(label = label.ifBlank { rib }, rib = rib)
                lifecycleScope.launch {
                    val id = withContext(Dispatchers.IO) { dao.upsertAccount(account) }
                    // Make the first account active by default.
                    if (settings.activeAccountId <= 0L) settings.activeAccountId = id
                    dialog.dismiss()
                    loadAccounts()
                }
            }
        }
        dialog.show()
    }

    private fun deleteAccount(account: CompanyAccount) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { dao.deleteAccount(account) }
            if (settings.activeAccountId == account.id) settings.activeAccountId = -1L
            loadAccounts()
        }
    }

    /** Saves the form values into settings (without closing the screen). */
    private fun persist() {
        settings.societe = binding.societe.text?.toString()?.trim().orEmpty().ifBlank { settings.societe }
        settings.manager = binding.manager.text?.toString()?.trim().orEmpty()
        settings.bankName = binding.bank.text?.toString()?.trim().orEmpty()
        settings.bankAgency = binding.agency.text?.toString()?.trim().orEmpty()
        settings.ville = binding.ville.text?.toString()?.trim().orEmpty()
        settings.reference = binding.reference.text?.toString()?.trim().orEmpty()
        val base = Format.parseNumber(binding.joursBase.text?.toString())
        if (base > 0) settings.joursBase = base
    }

    private fun persistAndFinish() {
        persist()
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
