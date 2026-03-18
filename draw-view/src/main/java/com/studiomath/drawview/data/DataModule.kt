package com.studiomath.drawview.data

import android.content.Context
import com.studiomath.drawview.data.repository.FileRepository

/**
 * Un semplice "Service Locator" per fornire le dipendenze del modulo
 * assicurando che vengano create una sola volta (Singleton).
 */
object DataModule {

    @Volatile
    private var fileRepository: FileRepository? = null

    /**
     * Restituisce l'istanza unica del FileRepository.
     * Usa l'applicationContext per evitare memory leak legati alle Activity.
     */
    fun getFileRepository(context: Context): FileRepository {
        return fileRepository ?: synchronized(this) {
            // Se non esiste ancora, lo crea usando il contesto globale dell'app
            val instance = FileRepository(context.applicationContext)
            fileRepository = instance
            instance
        }
    }
}