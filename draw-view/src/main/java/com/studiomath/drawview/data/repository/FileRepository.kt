package com.studiomath.drawview.data.repository

import android.content.Context
import com.studiomath.drawview.data.db.DocumentEntity
import com.studiomath.drawview.data.db.DrawDatabase
import com.studiomath.drawview.data.db.FolderEntity

class FileRepository(context: Context) {
    private val database = DrawDatabase.getInstance(context)
    private val folderDao = database.folderDao()
    private val documentDao = database.documentDao()

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

    suspend fun deleteFolder(folderId: Int): Boolean {
        val folder = folderDao.getFolderById(folderId) ?: return false

        // Cascading manuale per le sottocartelle
        val subFolders = folderDao.getSubFolders(folderId)
        for (subFolder in subFolders) {
            deleteFolder(subFolder.id)
        }

        // Non serve eliminare manualmente i documenti qui perché
        // in DocumentEntity hai messo onDelete = ForeignKey.CASCADE!
        // Eliminando la cartella, Room eliminerà i documenti da solo.

        folderDao.deleteById(folderId)
        return true
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

        val document = DocumentEntity(name = name, folderId = folderId)
        documentDao.insert(document)
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

    suspend fun deleteDocument(documentId: Int): Boolean {
        val doc = documentDao.getDocumentById(documentId) ?: return false
        documentDao.delete(doc)
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

    suspend fun updateLastOpened(documentId: Int) = documentDao.updateLastOpened(documentId)

    suspend fun getRecentDocuments(limit: Int): List<DocumentEntity> = documentDao.getRecentDocuments(limit)

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