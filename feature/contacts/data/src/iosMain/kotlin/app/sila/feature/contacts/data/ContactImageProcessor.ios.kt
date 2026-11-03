package app.sila.feature.contacts.data

import app.sila.feature.contacts.domain.ContactImageProcessor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/**
 * تنفيذ iOS لمعالج الصور [ContactImageProcessor] (T6 · J-3 · Issue #27):
 *
 * فك `UIImage(data:)` → قصّ مركزي مربّع + تصغير إلى `maxSide` في رسم واحد داخل
 * `UIGraphicsImageRenderer` (مستطيل رسم أكبر من اللوحة ومُوسَّطاً = قصّ مركزي
 * بيت-map وسيط، والاتجاه/القلب تُدبَّره UIKit تلقائياً) → ترميز JPEG بجودة ~85
 * عبر `UIImageJPEGRepresentation`.
 *
 * - **بلا ترقية (no-upscale):** صورة أصغر من `maxSide` تُقصّ مركزياً بالحجم الأصلي فقط.
 * - `format.scale = 1.0`: مخرجات بمقاس بكسل حرفي (الافتراضي يُضاعف بمقياس الشاشة).
 * - **الفشل صمت:** فك/رسم/ترميز فاشل يعيد `null` — الصورة تحسين اختياري ولا يُفشَل
 *   الحفظ كله (عقد المعالج).
 * - **اتجاه EXIF:** مدخلات منتقي الصور المنصوي تصل مُصحَّحة الاتجاه في الممارسة
 *   (قرار موثق — خط الأنابيب لا يعالج EXIF على أي منصة، اتساقاً بين المنصتين).
 */
class IosContactImageProcessor : ContactImageProcessor {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun process(bytes: ByteArray, maxSide: Int): ByteArray? {
        if (maxSide <= 0 || bytes.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            runCatching {
                val decoded = UIImage(data = bytes.iosBytesToData())
                    ?: return@runCatching null
                val imgW = decoded.size.useContents { width }
                val imgH = decoded.size.useContents { height }
                if (imgW < 1.0 || imgH < 1.0) return@runCatching null

                val side = minOf(imgW, imgH)
                // no-upscale: أصغر من maxSide → قصّ مركزي بحجم side×side بلا تكبير.
                val canvasSize = minOf(side, maxSide.toDouble())
                val drawScale = if (side > maxSide) maxSide / side else 1.0
                val drawnW = imgW * drawScale
                val drawnH = imgH * drawScale

                val format = UIGraphicsImageRendererFormat()
                format.scale = 1.0
                val renderer = UIGraphicsImageRenderer(
                    size = CGSizeMake(canvasSize, canvasSize),
                    format = format,
                )
                val rendered = renderer.imageWithActions { _ ->
                    // مستطيل مُوسَّط أكبر من اللوحة — الفائض يُقصّ عند حدود اللوحة.
                    decoded.drawInRect(
                        CGRectMake(
                            (canvasSize - drawnW) / 2.0,
                            (canvasSize - drawnH) / 2.0,
                            drawnW,
                            drawnH,
                        )
                    )
                }
                UIImageJPEGRepresentation(rendered, JPEG_QUALITY / 100.0)?.iosDataToBytes()
            }.getOrNull()
        }
    }

    private companion object {
        /** جودة ترميز JPEG — كفاية صور مصغّرة عالية بلا تضخيم حجم الدفتر (قرار T6). */
        const val JPEG_QUALITY = 85
    }
}
