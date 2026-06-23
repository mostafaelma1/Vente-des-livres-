package com.prixref.ao.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AnalysisDao {

    @Insert
    suspend fun insert(analysis: AnalysisEntity): Long

    @Delete
    suspend fun delete(analysis: AnalysisEntity)

    @Query("SELECT * FROM analyses ORDER BY date DESC")
    suspend fun getAll(): List<AnalysisEntity>

    @Query(
        "SELECT * FROM analyses WHERE reference LIKE '%' || :query || '%' " +
            "OR objet LIKE '%' || :query || '%' ORDER BY date DESC"
    )
    suspend fun search(query: String): List<AnalysisEntity>

    @Query("SELECT * FROM analyses WHERE id = :id")
    suspend fun getById(id: Long): AnalysisEntity?
}
