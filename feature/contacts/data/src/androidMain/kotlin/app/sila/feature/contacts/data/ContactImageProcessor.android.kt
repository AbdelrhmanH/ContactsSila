package app.sila.feature.contacts.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import app.sila.feature.contacts.domain.ContactImageProcessor
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * تنفيذ Android لمعالج الصور [ContactImageProcessor] (T6 · J-3 · Issue #27):
 *
 * فك `BitmapFactory` → قصّ مركزي مربّع + تصغير إلى `maxSide` في خطوة رسم واحدة
 * (`Matrix` بقصّ مركزي مُدمج — بلا bitmap وسيط) → ترميز JPEG بجودة ~85.
 *
 * - **بلا ترقية (no-upscale):** صورة أصغر من `maxSide` تُقصّ مركزياً بالحجم الأصلي فقط.
 * - **الفشل صمت:** فك/رسم/ترميز فاشل يعيد `null` — الصورة تحسين اختياري ولا يُفشَل
 *   الحفظ كله (عقد المعالج).
 * - **اتجاه EXIF:** مدخلات منتقي الصور المنصوي تصل مُصحَّحة الاتجاه في الممارسة
 *   (قرار موثق — خط الأنابيب لا يعالج EXIF على أي منصة، اتساقاً بين المنصتين).
 */
class AndroidContactImageProcessor : ContactImageProcessor {

    override suspend fun process(bytes: ByteArray, maxSide: Int): ByteArray? {
        if (maxSide <= 0 || bytes.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext null
            val jpeg = runCatching {
                val side = minOf(decoded.width, decoded.height)
                if (side <= 0) return@runCatching null

                // no-upscale: أصغر من maxSide → قصّ مركزي بحجم side×side بلا تكبير.
                val canvasSize = minOf(side, maxSide)
                val scale = if (side > maxSide) maxSide.toFloat() / side else 1f
                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(
                        (canvasSize - decoded.width * scale) / 2f,
                        (canvasSize - decoded.height * scale) / 2f,
                    )
                }
                val square = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
                Canvas(square).drawBitmap(
                    decoded,
                    matrix,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
                val output = ByteArrayOutputStream()
                square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }.getOrNull()
            decoded.recycle()
            jpeg
        }
    }

    private companion object {
        /** جودة ترميز JPEG — كفاية صور مصغّرة عالية بلا تضخيم حجم الدفتر (قرار T6). */
        const val JPEG_QUALITY = 85
    }
}
