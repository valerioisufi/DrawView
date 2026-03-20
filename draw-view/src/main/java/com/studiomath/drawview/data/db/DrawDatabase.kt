package com.studiomath.drawview.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 3, // Aggiornato alla v3 per l'introduzione dei Trigger
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
                    .fallbackToDestructiveMigration(true)
                    // Aggiungiamo la callback per iniettare i Trigger SQLite
                    .addCallback(DatabaseCallback())
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }

    /**
     * Callback di Room per eseguire operazioni SQL native.
     * Usiamo onCreate in modo che i trigger vengano creati quando il DB viene generato.
     */
    private class DatabaseCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            createTriggers(db)
        }

        // Se preferisci essere sicuro al 100% che i trigger ci siano anche dopo
        // eventuali riavvii senza migrazioni distruttive, puoi de-commentare onOpen:
        /*
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            createTriggers(db)
        }
        */

        private fun createTriggers(db: SupportSQLiteDatabase) {
            // Formula SQLite per ottenere i millisecondi attuali (simile a System.currentTimeMillis())
            val nowMs = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

            // 1. TRIGGER PER LE PAGINE (Se aggiungo/modifico/rimuovo una pagina)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS update_doc_on_page_insert
                AFTER INSERT ON pages BEGIN
                    UPDATE documents SET modifiedAt = $nowMs WHERE id = NEW.documentId;
                END;
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS update_doc_on_page_delete
                AFTER DELETE ON pages BEGIN
                    UPDATE documents SET modifiedAt = $nowMs WHERE id = OLD.documentId;
                END;
            """)

            // 2. TRIGGER PER I TRATTI (Strokes)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS update_doc_on_stroke_change
                AFTER INSERT ON strokes BEGIN
                    UPDATE documents SET modifiedAt = $nowMs 
                    WHERE id = (SELECT documentId FROM pages WHERE id = NEW.pageId);
                END;
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS update_doc_on_stroke_delete
                AFTER DELETE ON strokes BEGIN
                    UPDATE documents SET modifiedAt = $nowMs 
                    WHERE id = (SELECT documentId FROM pages WHERE id = OLD.pageId);
                END;
            """)

            // 3. TRIGGER PER I TESTI (Texts)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS update_doc_on_text_change
                AFTER INSERT ON texts BEGIN
                    UPDATE documents SET modifiedAt = $nowMs 
                    WHERE id = (SELECT documentId FROM pages WHERE id = NEW.pageId);
                END;
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS update_doc_on_text_update
                AFTER UPDATE ON texts BEGIN
                    UPDATE documents SET modifiedAt = $nowMs 
                    WHERE id = (SELECT documentId FROM pages WHERE id = NEW.pageId);
                END;
            """)

            // 4. TRIGGER PER LE IMMAGINI (Images)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS update_doc_on_image_change
                AFTER INSERT ON images BEGIN
                    UPDATE documents SET modifiedAt = $nowMs 
                    WHERE id = (SELECT documentId FROM pages WHERE id = NEW.pageId);
                END;
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS update_doc_on_image_update
                AFTER UPDATE ON images BEGIN
                    UPDATE documents SET modifiedAt = $nowMs 
                    WHERE id = (SELECT documentId FROM pages WHERE id = NEW.pageId);
                END;
            """)

            // 5. TRIGGER PER I PDF (Pdfs)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS update_doc_on_pdf_change
                AFTER INSERT ON pdfs BEGIN
                    UPDATE documents SET modifiedAt = $nowMs 
                    WHERE id = (SELECT documentId FROM pages WHERE id = NEW.pageId);
                END;
            """)

            // 6. TRIGGER PER LE CARTELLE (Propaga la modifica del documento alla cartella padre)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS update_folder_on_doc_mod
                AFTER UPDATE OF modifiedAt ON documents 
                WHEN NEW.folderId IS NOT NULL 
                BEGIN
                    UPDATE folders SET modifiedAt = NEW.modifiedAt WHERE id = NEW.folderId;
                END;
            """)
        }
    }
}