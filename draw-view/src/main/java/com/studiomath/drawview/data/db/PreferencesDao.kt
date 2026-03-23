package com.studiomath.drawview.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PreferencesDao {

    /**
     * Inserisce o aggiorna l'unica riga delle preferenze.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(preferences: UserPreferencesEntity)

    /**
     * Ritorna le preferenze in modo reattivo.
     * Il ViewModel si metterà in ascolto di questo Flow.
     */
    @Query("SELECT * FROM user_preferences WHERE id = 1")
    fun getPreferencesFlow(): Flow<UserPreferencesEntity?>

    /**
     * Lettura sincrona "one-shot" (utile per l'inizializzazione o funzioni non reattive).
     */
    @Query("SELECT * FROM user_preferences WHERE id = 1")
    suspend fun getPreferences(): UserPreferencesEntity?
}