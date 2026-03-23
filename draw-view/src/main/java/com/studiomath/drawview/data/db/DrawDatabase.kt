package com.studiomath.drawview.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The main database entry point for the application's persistent storage using the Room persistence library.
 *
 * This abstract class defines the database configuration, including the list of entities
 * and the data access objects (DAOs) used to interact with the underlying SQLite database.
 * It follows the Singleton pattern to ensure only one instance of the database is active at any time.
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
        ResourceEntity::class,
        UserPreferencesEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class DrawDatabase : RoomDatabase() {

    /**
     * Provides access to folder-related persistence operations.
     * @return An instance of [FolderDao].
     */
    abstract fun folderDao(): FolderDao

    /**
     * Provides access to document-related persistence operations.
     * @return An instance of [DocumentDao].
     */
    abstract fun documentDao(): DocumentDao

    /**
     * Provides access to page-related persistence operations.
     * @return An instance of [PageDao].
     */
    abstract fun pageDao(): PageDao

    /**
     * Provides access to stroke/drawing-related persistence operations.
     * @return An instance of [StrokeDao].
     */
    abstract fun strokeDao(): StrokeDao

    /**
     * Provides access to text-element-related persistence operations.
     * @return An instance of [TextDao].
     */
    abstract fun textDao(): TextDao

    /**
     * Provides access to image-element-related persistence operations.
     * @return An instance of [ImageDao].
     */
    abstract fun imageDao(): ImageDao

    /**
     * Provides access to PDF-related persistence operations.
     * @return An instance of [PdfDao].
     */
    abstract fun pdfDao(): PdfDao

    /**
     * Provides access to generic resource-related persistence operations.
     * @return An instance of [ResourceDao].
     */
    abstract fun resourceDao(): ResourceDao

    
    abstract fun preferencesDao(): PreferencesDao

    companion object {
        /**
         * The singleton instance of the database, marked as Volatile to ensure
         * atomic access across multiple threads.
         */
        @Volatile
        private var INSTANCE: DrawDatabase? = null

        /**
         * Retrieves the singleton instance of the database.
         * * If the instance does not exist, it is initialized using a thread-safe synchronized block.
         * Destructive migration is enabled by default, meaning the database will be cleared if the
         * version number is incremented without a defined migration path.
         *
         * @param context The application context used to initialize the database builder.
         * @return The initialized [DrawDatabase] instance.
         */
        fun getInstance(context: Context): DrawDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DrawDatabase::class.java,
                    "draw_database"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}