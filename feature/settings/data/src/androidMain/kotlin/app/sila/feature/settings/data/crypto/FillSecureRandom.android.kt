package app.sila.feature.settings.data.crypto

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun fillSecureRandom(bytes: ByteArray) {
    secureRandom.nextBytes(bytes)
}
