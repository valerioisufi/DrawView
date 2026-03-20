package com.studiomath.drawview.data.repository

import android.content.Context
import android.util.Log
import com.studiomath.drawview.data.db.DocumentEntity
import com.studiomath.drawview.data.db.DrawDatabase
import com.studiomath.drawview.data.db.FolderEntity
import com.studiomath.drawview.data.db.ResourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class FileRepository(context: Context) {
    private val database = DrawDatabase.getInstance(context)
    private val folderDao = database.folderDao()
    private val documentDao = database.documentDao()
    private val pageDao = database.pageDao()
    private val resourceDao = database.resourceDao()


    companion object {
        private const val TAG = "FileRepository"
    }

    // --- FOLDER OPERATIONS ---
    suspend fun createFolder(name: String, parentId: Int?): Boolean {
        if (folderDao.getFolderByNameAndParent(name, parentId) != null) return false

        val folder = FolderEntity(name = name, parentId = parentId)
        folderDao.insert(folder)
        return true
    }

    suspend fun getRootFolders(): List<FolderEntity> = folderDao.getRootFolders()

    suspend fun getSubFolders(parentId: Int): List<FolderEntity> = folderDao.getSubFolders(parentId)

    suspend fun renameFolder(folderId: Int, newName: String): Boolean {
        val folder = folderDao.getFolderById(folderId) ?: return false
        if (folderDao.getFolderByNameAndParent(newName, folder.parentId) != null) return false

        folderDao.renameFolder(folderId, newName)
        return true
    }

    /**
     * Elimina una cartella e tutto il suo contenuto (sottocartelle, documenti e file fisici).
     */
    suspend fun deleteFolder(folderId: Int): Boolean = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderById(folderId) ?: return@withContext false

        // 1. Ricorsione sulle sottocartelle
        val subFolders = folderDao.getSubFolders(folderId)
        for (subFolder in subFolders) {
            deleteFolder(subFolder.id)
        }

        // 2. Elimina i documenti dentro questa cartella usando il NOSTRO metodo (che cancella i file),
        // invece di lasciare fare solo al CASCADE silenzioso del DB.
        val documentsInFolder = documentDao.getDocumentsInFolder(folderId)
        for (doc in documentsInFolder) {
            deleteDocument(doc.id)
        }

        // 3. Infine, elimina la cartella stessa dal DB
        folderDao.deleteById(folderId)

        return@withContext true
    }

    suspend fun moveFolder(folderId: Int, newParentId: Int?): Boolean {
        if (folderId == newParentId) return false
        val folder = folderDao.getFolderById(folderId) ?: return false
        if (folderDao.getFolderByNameAndParent(folder.name, newParentId) != null) return false

        folderDao.moveFolder(folderId, newParentId)
        return true
    }

    // --- DOCUMENT OPERATIONS ---
    suspend fun createDocument(name: String, folderId: Int?): Boolean {
        val existing = if (folderId == null) documentDao.getRootDocumentByName(name)
        else documentDao.getDocumentByNameAndFolder(name, folderId)
        if (existing != null) return false

        // 1. Crea il documento e salva l'ID generato
        val document = DocumentEntity(name = name, folderId = folderId)
        val newDocId = documentDao.insert(document).toInt()

        // 2. Crea la primissima pagina di default (Foglio A4)
        // Assicurati che l'import di PageEntity sia corretto
        val dbPage = com.studiomath.drawview.data.db.PageEntity(
            documentId = newDocId,
            pageNumber = 0,
            width = 210f, // Larghezza A4 in mm (o usa i tuoi valori di default)
            height = 297f // Altezza A4 in mm
        )
        pageDao.insert(dbPage)

        return true
    }

    suspend fun getDocumentsInFolder(folderId: Int?): List<DocumentEntity> {
        return if (folderId == null) documentDao.getRootDocuments()
        else documentDao.getDocumentsInFolder(folderId)
    }

    suspend fun renameDocument(documentId: Int, newName: String): Boolean {
        val document = documentDao.getDocumentById(documentId) ?: return false
        val existing = if (document.folderId == null) documentDao.getRootDocumentByName(newName)
        else documentDao.getDocumentByNameAndFolder(newName, document.folderId)
        if (existing != null) return false

        documentDao.renameDocument(documentId, newName)
        return true
    }

    suspend fun moveDocument(documentId: Int, newFolderId: Int?): Boolean {
        val document = documentDao.getDocumentById(documentId) ?: return false
        val existing = if (newFolderId == null) documentDao.getRootDocumentByName(document.name)
        else documentDao.getDocumentByNameAndFolder(document.name, newFolderId)
        if (existing != null) return false

        documentDao.moveDocument(documentId, newFolderId)
        return true
    }

    /**
     * Elimina un documento, le sue entità nel DB (in cascata) e i file fisici associati.
     */
    suspend fun deleteDocument(documentId: Int): Boolean = withContext(Dispatchers.IO) {
        val doc = documentDao.getDocumentById(documentId) ?: return@withContext false

        // 1. RECUPERA LE RISORSE PRIMA DI ELIMINARE IL DOCUMENTO
        // Se lo facessimo dopo, il CASCADE avrebbe già eliminato le righe e non sapremmo quali file cancellare.
        val resources = resourceDao.getResourcesForDocument(documentId)

        // 2. ELIMINA DAL DATABASE (Il CASCADE di Room pulirà pages, strokes, texts, images, pdfs, resources)
        documentDao.delete(doc)

        // 3. GARBAGE COLLECTION: ELIMINA I FILE FISICI DAL DISCO
        deletePhysicalFiles(resources)

        return@withContext true
    }


    /**
     * Helper privato per eliminare i file fisici dalla memoria interna.
     */
    private fun deletePhysicalFiles(resources: List<ResourceEntity>) {
        resources.forEach { resource ->
            // Selezioniamo solo i tipi che rappresentano file fisici
            if (resource.type == "image" || resource.type == "pdf") {
                try {
                    val file =
                        File(resource.uri) // Assumendo che uri sia il percorso assoluto del file
                    if (file.exists()) {
                        val deleted = file.delete()
                        if (deleted) {
                            Log.d(TAG, "Garbage Collection: Eliminato file fisico -> ${resource.uri}")
                        } else {
                            Log.w(TAG, "Garbage Collection: Impossibile eliminare il file -> ${resource.uri}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Errore durante l'eliminazione del file: ${resource.uri}", e)
                }
            }
        }
    }

    suspend fun updateLastOpened(documentId: Int) = documentDao.updateLastOpened(documentId)

    suspend fun getRecentDocuments(limit: Int): List<DocumentEntity> = documentDao.getRecentDocuments(limit)

    fun getRecentDocumentsFlow(limit: Int): Flow<List<DocumentEntity>> {
        return documentDao.getRecentDocumentsFlow(limit)
    }

    suspend fun getFolderById(folderId: Int): FolderEntity? = folderDao.getFolderById(folderId)

    suspend fun getDocumentById(documentId: Int): DocumentEntity? = documentDao.getDocumentById(documentId)

    // --- COMBINED OPERATIONS (UI) ---
    data class FileItem(
        val id: Int,
        val name: String,
        val type: FileType,
        val parentId: Int?,
        val createdAt: Long,
        val modifiedAt: Long,
        val lastOpenedAt: Long?
    )

    enum class FileType { FOLDER, DOCUMENT }

    suspend fun getItemsInFolder(parentId: Int?): List<FileItem> {
        val items = mutableListOf<FileItem>()

        val folders = if (parentId == null) folderDao.getRootFolders() else folderDao.getSubFolders(parentId)
        folders.forEach { f ->
            items.add(FileItem(f.id, f.name, FileType.FOLDER, f.parentId, f.createdAt, f.modifiedAt, null))
        }

        val docs = getDocumentsInFolder(parentId)
        docs.forEach { d ->
            items.add(FileItem(d.id, d.name, FileType.DOCUMENT, d.folderId, d.createdAt, d.modifiedAt, d.lastOpenedAt))
        }

        // Ordina le cartelle prima, e poi in ordine alfabetico (o di data, come preferisci)
        return items.sortedWith(compareBy({ it.type }, { it.name }))
    }
}