package com.studiomath.drawview.file

import java.io.File


class FileManager(filesDir: File, filePath: String) {
    private var rootSequence = filePath.split("/")
    private val directoryRoot: String
        get() {
            var path = "/"
            for (index in rootSequence.indices) {
                if (index == rootSequence.lastIndex) break
                path += "${rootSequence[index]}/"
            }
            return path
        }

    // creo l'oggetto File(), lo apro e lo leggo
    private var directory: File = File(filesDir, directoryRoot)
    var file: File = File(filesDir, filePath)
    var justCreated = false

    // variabili che ospitano il testo contenuto nel file
    var text = ""
        set(value) {
            field = value
            writeToFile()
        }

    init {
        if (!file.exists()) {
            directory.mkdirs()
            file.createNewFile()
            justCreated = true
        }

        if (file.extension == "txt" || file.extension == "json") {
            readFromFile()
        }
    }


    // funzioni per la lettura e scrittura su file
    private fun readFromFile() {
        text = file.readText()
    }

    private fun writeToFile() {
        file.writeText(text)
    }


}