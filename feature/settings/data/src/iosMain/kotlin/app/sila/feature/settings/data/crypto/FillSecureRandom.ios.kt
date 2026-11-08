package app.sila.feature.settings.data.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun fillSecureRandom(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), pinned.addressOf(0))
        check(status == errSecSuccess) { "SecRandomCopyBytes failed with status: $status" }
    }
}
