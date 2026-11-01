package app.sila.core.database.legacy

import java.io.File

internal actual object PlatformFileOps {

    actual fun fileExists(path: String): Boolean = File(path).exists()

    actual fun fileSizeBytes(path: String): Long? {
        val file = File(path)
        return if (file.exists()) file.length() else null
    }

    actual fun freeSpaceBytes(directoryPath: String): Long? {
        val dir = File(directoryPath.ifEmpty { "." })
        if (!dir.exists()) return null
        val usable = dir.usableSpace
        return if (usable > 0L) usable else null
    }

    actual fun deleteFileIfExists(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return true
        return file.delete() || !file.exists()
    }
}
