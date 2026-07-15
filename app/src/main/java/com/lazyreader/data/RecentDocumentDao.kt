package com.lazyreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<RecentDocument>>

    @Query("SELECT * FROM recent_documents WHERE uri = :uri")
    suspend fun getByUri(uri: String): RecentDocument?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(document: RecentDocument)

    @Query("UPDATE recent_documents SET currentPage = :page, lastOpenedAt = :openedAt WHERE uri = :uri")
    suspend fun updateProgress(uri: String, page: Int, openedAt: Long)

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
