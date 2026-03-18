package com.studiomath.drawview.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Configurazione principale del Database Room.
 * Quando si modificano le entità (es. aggiungendo una colonna),
 * incrementare il valore di 'version'.
 */
@Database(
    entities = [
        FolderEntity::class,
        DocumentEntity::class,
        PageEntity::class,
        StrokeEntity::class,
        TextEntity::class,
        ImageEntity::class,
        PdfEntity::class,
        ResourceEntity::class
    ],
    version = 2, // Versione 2 perché abbiamo cambiato drasticamente lo schema
    exportSchema = false
)
abstract class DrawDatabase : RoomDatabase() {

    // Dichiarazione dei DAO accessibili
    abstract fun folderDao(): FolderDao
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun textDao(): TextDao
    abstract fun imageDao(): ImageDao
    abstract fun pdfDao(): PdfDao
    abstract fun resourceDao(): ResourceDao

    companion object {
        @Volatile
        private var INSTANCE: DrawDatabase? = null

        fun getInstance(context: Context): DrawDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DrawDatabase::class.java,
                    "draw_database"
                )
                    // Se lo schema cambia (version up), elimina e ricrea il DB
                    // In produzione si dovrebbero usare le Migration
                    .fallbackToDestructiveMigration(true)
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}