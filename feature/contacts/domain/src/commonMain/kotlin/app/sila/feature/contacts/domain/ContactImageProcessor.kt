package app.sila.feature.contacts.domain

/**
 * معالج صور جهات الاتصال — عقد نطاقي نقي بلا أنواع منصات (T6 · J-3 · Issue #27).
 *
 * التنفيذات المنصوية (`androidMain` عبر `BitmapFactory`/`Bitmap`، و`iosMain` عبر
 * `UIImage`/`CGImage`) تنفّذ خط الأنابيب نفسه حرفياً:
 * 1. فك الترميز (فشل الفك = `null` — لا استثناءات عبر الحد).
 * 2. قصّ مركزي مربّع (in-app فقط — لا مكتبة قص خارجية، قرار T6).
 * 3. تصغير إلى [maxSide]×[maxSide] (الافتراضي 512 — كفاية عرض مصغّرات عالية،
 *    وتقليل حجم الدفتر وحدود مزامنة المنصة).
 * 4. ترميز JPEG بجودة ~85 (JPEG حصراً — توافق كامل بين المنصتين).
 *
 * الناتج يمرّ إلى [PlatformWritableContact.photoBytes] حصراً — Room يحفظ
 * `photoUri` كمرآة للمسار المنصوي كما هو (لا نسخ ثنائية في Room — ADR-0012).
 */
interface ContactImageProcessor {

    /**
     * @param bytes محتوى الصورة الخام (من منتقي الصور المنصوي).
     * @param maxSide أقصى بُعد للمربع الناتج.
     * @return بايتات JPEG مُعالَجة، أو `null` إن فشل الفك/المعالجة (يُتجاهل حفظ
     *   الصورة ولا يُفشَل الحفظ كله — الصورة تحسين اختياري لا حقل جوهري).
     */
    suspend fun process(bytes: ByteArray, maxSide: Int = DEFAULT_MAX_SIDE): ByteArray?

    companion object {
        const val DEFAULT_MAX_SIDE: Int = 512
    }
}
