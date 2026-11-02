package app.sila.feature.contacts.data

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes

/**
 * جسر NSData ↔ ByteArray المشترك لكود الكتابة على iOS (T6) — نفس أنماط المصدر T1
 * (نسخ صريحة، بلا واجهات API مُرجّحة)، مشتركة بين الكاتب ومعالج الصور.
 * (الأسماء مختلفة عمداً عن خصوصيات IosContactsPlatformSource كي لا تتعارض في الحزمة.)
 */

/** NSData → ByteArray (نسخة صريحة — بلا الاعتماد على واجهات API مُرجّحة). */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.iosDataToBytes(): ByteArray {
    val byteCount = length.toInt()
    if (byteCount <= 0) return ByteArray(0)
    val source = bytes?.reinterpret<ByteVar>() ?: return ByteArray(0)
    return source.readBytes(byteCount)
}

/** ByteArray → NSData (usePinned يضمن بقاء الأصل حتى تنتهي نسخة `dataWithBytes` — النسخة تُنتج NSData مستقلاً). */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.iosBytesToData(): NSData = usePinned { pinned ->
    NSData.Companion.dataWithBytes(pinned.addressOf(0), size.toULong())
}
