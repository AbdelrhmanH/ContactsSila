package app.sila.core.database.legacy

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformFileOps {

    private val fm: NSFileManager get() = NSFileManager.defaultManager

    actual fun fileExists(path: String): Boolean = fm.fileExistsAtPath(path)

    actual fun fileSizeBytes(path: String): Long? {
        if (!fm.fileExistsAtPath(path)) return null
        val attrs = fm.attributesOfItemAtPath(path, error = null) ?: return null
        return (attrs[NSFileSize] as? NSNumber)?.longLongValue
    }

    actual fun freeSpaceBytes(directoryPath: String): Long? {
        val normalizedDir = directoryPath.ifEmpty { "." }
        if (!fm.fileExistsAtPath(normalizedDir)) return null
        val attrs = fm.attributesOfFileSystemForPath(normalizedDir, error = null) ?: return null
        return (attrs[NSFileSystemFreeSize] as? NSNumber)?.longLongValue
    }

    actual fun deleteFileIfExists(path: String): Boolean {
        if (!fm.fileExistsAtPath(path)) return true
        return fm.removeItemAtPath(path, error = null) || !fm.fileExistsAtPath(path)
    }

    actual fun tempTestDbPath(fileName: String): String {
        val base = NSTemporaryDirectory().trimEnd('/')
        if (!fm.fileExistsAtPath(base)) {
            fm.createDirectoryAtPath(base, withIntermediateDirectories = true, attributes = null, error = null)
        }
        return "$base/$fileName"
    }
}
