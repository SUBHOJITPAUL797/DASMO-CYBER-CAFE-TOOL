package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val personName: String,
    val documentType: String,
    val localFilePath: String,
    val timestamp: Long,
    val isUploaded: Boolean,
    val drivePath: String? = null
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY timestamp DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity): Long

    @Update
    suspend fun updateDocument(document: DocumentEntity)

    @Query("DELETE FROM documents")
    suspend fun deleteAllDocuments()
}

@Database(entities = [DocumentEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
}
