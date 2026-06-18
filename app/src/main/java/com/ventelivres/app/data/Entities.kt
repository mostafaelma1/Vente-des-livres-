package com.ventelivres.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A customer: a bookstore (gros) or an individual (détail). */
@Entity(tableName = "clients")
data class Client(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    /** "GROS" or "DETAIL". */
    val type: String = TYPE_GROS,
    /** Default discount applied to this client's invoices, in percent. */
    val remisePercent: Double = 0.0,
    val notes: String = ""
) {
    companion object {
        const val TYPE_GROS = "GROS"
        const val TYPE_DETAIL = "DETAIL"
    }
}

/** A book in the catalog, entered manually with its wholesale/retail price. */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val priceGros: Double = 0.0,
    val priceDetail: Double = 0.0
)

/** A sale/invoice header. Items and payments reference it. */
@Entity(tableName = "invoices")
data class Invoice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    /** Snapshot of the client name at creation time (kept even if client edited). */
    val clientName: String,
    val date: Long = System.currentTimeMillis(),
    val type: String = Client.TYPE_GROS,
    val remisePercent: Double = 0.0,
    val note: String = ""
)

@Entity(
    tableName = "invoice_items",
    foreignKeys = [ForeignKey(
        entity = Invoice::class,
        parentColumns = ["id"],
        childColumns = ["invoiceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("invoiceId")]
)
data class InvoiceItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val bookId: Long? = null,
    val title: String,
    val unitPrice: Double,
    val quantity: Int = 1
) {
    val lineTotal: Double get() = unitPrice * quantity
}

@Entity(
    tableName = "payments",
    foreignKeys = [ForeignKey(
        entity = Invoice::class,
        parentColumns = ["id"],
        childColumns = ["invoiceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("invoiceId")]
)
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val amount: Double,
    val date: Long = System.currentTimeMillis(),
    /** "CASH", "CHEQUE", "TRANSFER", "OTHER". */
    val method: String = METHOD_CASH,
    val note: String = ""
) {
    companion object {
        const val METHOD_CASH = "CASH"
        const val METHOD_CHEQUE = "CHEQUE"
        const val METHOD_TRANSFER = "TRANSFER"
        const val METHOD_OTHER = "OTHER"
    }
}

/** Invoice header plus its computed totals, for list/summary screens. */
data class InvoiceWithTotals(
    @Embedded val invoice: Invoice,
    val subtotal: Double,
    val paid: Double
) {
    val remiseAmount: Double get() = subtotal * invoice.remisePercent / 100.0
    val total: Double get() = subtotal - remiseAmount
    val rest: Double get() = total - paid

    val status: String
        get() = when {
            total <= 0.0 -> STATUS_UNPAID
            paid <= 0.0 -> STATUS_UNPAID
            rest > 0.009 -> STATUS_PARTIAL
            else -> STATUS_PAID
        }

    companion object {
        const val STATUS_PAID = "PAID"
        const val STATUS_PARTIAL = "PARTIAL"
        const val STATUS_UNPAID = "UNPAID"
    }
}
