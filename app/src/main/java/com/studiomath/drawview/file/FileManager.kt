package com.studiomath.drawview.file

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.write


class FileManager(filesDir: File, filePath: String, val options: Options = Options(false, false)) {
    data class Options(val readOnly: Boolean, val compressGZIP: Boolean)

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

        if (!justCreated && (file.extension == "txt" || file.extension == "json")) {
            readFromFile()
        }
    }


    // funzioni per la lettura e scrittura su file
    private fun readFromFile() {
        if (options.compressGZIP) {
            file.inputStream().use { inputStream ->
                GZIPInputStream(inputStream).use { gzip ->
                    InputStreamReader(gzip).use { streamReader ->
                        text = streamReader.readText()
                    }
                }
            }

            return
        }
        text = file.readText()

    }

    private fun writeToFile() {
        if (options.readOnly) throw Exception("File is read-only")
        if (options.compressGZIP) {
            val outputStream = ByteArrayOutputStream()
            GZIPOutputStream(outputStream).use { gzip ->
                gzip.write(text.toByteArray())
            }
            file.writeBytes(outputStream.toByteArray())

            return
        }
        file.writeText(text)

    }


}