package com.ventelivres.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ventelivres.app.data.Employee
import com.ventelivres.app.databinding.ActivityListBinding
import com.ventelivres.app.databinding.DialogEmployeeBinding
import com.ventelivres.app.ui.EmployeeAdapter
import com.ventelivres.app.util.DocumentExporter
import com.ventelivres.app.util.EmployeeCsv
import com.ventelivres.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmployeesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private val dao get() = (application as VenteApp).db.dao()
    private val adapter = EmployeeAdapter { showEmployeeDialog(it) }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFrom(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.employees_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.toolbar.inflateMenu(R.menu.menu_employees)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import -> { importLauncher.launch(arrayOf("*/*")); true }
                R.id.action_template -> { shareTemplate(); true }
                else -> false
            }
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.text = getString(R.string.new_employee)
        binding.fab.setOnClickListener { showEmployeeDialog(null) }
        binding.emptyText.text = getString(R.string.no_employees)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() = lifecycleScope.launch {
        val list = withContext(Dispatchers.IO) { dao.employees() }
        adapter.submit(list)
        binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEmployeeDialog(existing: Employee?) {
        val d = DialogEmployeeBinding.inflate(layoutInflater)
        d.nom.setText(existing?.nom ?: "")
        d.prenom.setText(existing?.prenom ?: "")
        d.poste.setText(existing?.poste ?: "")
        d.lieu.setText(existing?.lieuTravail ?: "")
        d.phone.setText(existing?.telephone ?: "")
        d.cin.setText(existing?.carteNationale ?: "")
        d.compte.setText(existing?.numeroCompte ?: "")
        if (existing != null && existing.salaireMensuel > 0) {
            d.salaire.setText(Format.trimDays(existing.salaireMensuel))
        }
        val isVirement = existing?.typeVirement == Employee.TYPE_VIREMENT
        d.typeGroup.check(if (isVirement) d.typeVirement.id else d.typeMise.id)
        d.actif.isChecked = existing?.actif ?: true

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.new_employee else R.string.edit)
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.delete) { _, _ -> confirmDelete(existing) }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nom = d.nom.text?.toString()?.trim().orEmpty()
                if (nom.isEmpty()) {
                    d.nom.error = getString(R.string.err_name_required)
                    return@setOnClickListener
                }
                val type = if (d.typeGroup.checkedButtonId == d.typeVirement.id)
                    Employee.TYPE_VIREMENT else Employee.TYPE_MISE_DISPOSITION
                val emp = (existing ?: Employee(nom = nom)).copy(
                    nom = nom,
                    prenom = d.prenom.text?.toString()?.trim().orEmpty(),
                    poste = d.poste.text?.toString()?.trim().orEmpty(),
                    lieuTravail = d.lieu.text?.toString()?.trim().orEmpty(),
                    telephone = d.phone.text?.toString()?.trim().orEmpty(),
                    carteNationale = d.cin.text?.toString()?.trim().orEmpty(),
                    numeroCompte = d.compte.text?.toString()?.trim().orEmpty(),
                    salaireMensuel = Format.parseNumber(d.salaire.text?.toString()),
                    typeVirement = type,
                    actif = d.actif.isChecked
                )
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.upsertEmployee(emp) }
                    dialog.dismiss()
                    refresh()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(employee: Employee) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.deleteEmployee(employee) }
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Shares the fill-in CSV template (opens in Excel / Sheets). */
    private fun shareTemplate() {
        val file = EmployeeCsv.writeTemplate(this)
        DocumentExporter.share(this, file, "text/csv")
    }

    /**
     * Imports employees from the picked CSV. Existing rows are matched by CIN
     * (else by name) and updated; unknown rows are added automatically.
     */
    private fun importFrom(uri: Uri) = lifecycleScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull().orEmpty()
        }
        val parsed = EmployeeCsv.parse(text)
        if (parsed.isEmpty()) {
            Toast.makeText(this@EmployeesActivity, R.string.import_empty, Toast.LENGTH_LONG).show()
            return@launch
        }
        val (added, updated) = withContext(Dispatchers.IO) {
            val existing = dao.employees()
            val byCin = existing.filter { it.carteNationale.isNotBlank() }
                .associateBy { it.carteNationale.trim().lowercase() }
            val byName = existing.associateBy { "${it.nom}|${it.prenom}".trim().lowercase() }
            var a = 0
            var u = 0
            for (p in parsed) {
                val match = (if (p.carteNationale.isNotBlank()) byCin[p.carteNationale.trim().lowercase()] else null)
                    ?: byName["${p.nom}|${p.prenom}".trim().lowercase()]
                if (match != null) {
                    dao.upsertEmployee(p.copy(id = match.id, actif = match.actif))
                    u++
                } else {
                    dao.upsertEmployee(p)
                    a++
                }
            }
            a to u
        }
        refresh()
        Toast.makeText(this@EmployeesActivity, getString(R.string.import_result, added, updated), Toast.LENGTH_LONG).show()
    }
}
