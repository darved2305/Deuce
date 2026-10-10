package com.elevate.app.data.upload

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Write-temp-then-rename persistence: a reader sees the old file or the new one, never half of one,
 * even if the process dies mid-write. The temp file is fsynced before the rename.
 */
class AtomicFile(val file: File) {
    private val tmp = File(file.parentFile, file.name + ".tmp")

    @Synchronized
    fun writeText(text: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun readText(): String? = file.takeIf { it.isFile }?.readText(Charsets.UTF_8)

    @Synchronized
    fun delete() {
        tmp.delete()
        file.delete()
    }
}
