package com.ventelivres.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AppDao {

    // ---- Clients ----
    @Query("SELECT * FROM clients ORDER BY name COLLATE NOCASE")
    suspend fun clients(): List<Client>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun client(id: Long): Client?

    @Upsert
    suspend fun upsertClient(client: Client): Long

    @Delete
    suspend fun deleteClient(client: Client)

    // ---- Books ----
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE")
    suspend fun books(): List<Book>

    @Upsert
    suspend fun upsertBook(book: Book): Long

    @Delete
    suspend fun deleteBook(book: Book)

    // ---- Invoices ----
    @Query("SELECT * FROM invoices WHERE id = :id")
    suspend fun invoice(id: Long): Invoice?

    @Upsert
    suspend fun upsertInvoice(invoice: Invoice): Long

    @Delete
    suspend fun deleteInvoice(invoice: Invoice)

    @Query(
        """
        SELECT i.*,
          (SELECT COALESCE(SUM(unitPrice * quantity), 0) FROM invoice_items WHERE invoiceId = i.id) AS subtotal,
          (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE invoiceId = i.id) AS paid
        FROM invoices i
        ORDER BY i.date DESC, i.id DESC
        """
    )
    suspend fun invoicesWithTotals(): List<InvoiceWithTotals>

    // ---- Invoice items ----
    @Query("SELECT * FROM invoice_items WHERE invoiceId = :invoiceId ORDER BY id")
    suspend fun items(invoiceId: Long): List<InvoiceItem>

    @Upsert
    suspend fun upsertItem(item: InvoiceItem): Long

    @Delete
    suspend fun deleteItem(item: InvoiceItem)

    // ---- Payments ----
    @Query("SELECT * FROM payments WHERE invoiceId = :invoiceId ORDER BY date, id")
    suspend fun payments(invoiceId: Long): List<Payment>

    @Upsert
    suspend fun upsertPayment(payment: Payment): Long

    @Delete
    suspend fun deletePayment(payment: Payment)
}
