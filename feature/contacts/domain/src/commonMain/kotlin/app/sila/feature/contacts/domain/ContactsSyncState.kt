package app.sila.feature.contacts.domain

/**
 * حالة محرك المزامنة التزايدية لجهات الاتصال (Issue #23 · ADR-0010 §8).
 *
 * حالة مرئية للواجهة عبر `StateFlow` حصراً — تُستخدم لربط حالات Loading/Error/Permission-denied
 * في UI (معيار القبول: "Sync status is observable"). القيم السبع تغطي الحالات الأربع المطلوبة
 * في الـ Issue (Idle / Running / PermissionDenied / Failed) بتقسيم أدقّ لـ "Running" وفق ADR-0010:
 *
 * - [IDLE]: المحرك متوقّف أو لم يبدأ بعد (بما في ذلك الاستئناد بعد إلغاء مزامنة جارية).
 * - [INITIAL_IMPORT]: أول استيراد كامل جارٍ (لا توجد لقطة محفوظة سابقاً) — دفعات من ~500.
 * - [INCREMENTAL_SYNC]: تطبيق دلتا منذ اللقطة المحفوظة جارٍ.
 * - [READY]: القاعدة متزامنة مع الدفتر — لا عمل جارٍ.
 * - [PERMISSION_DENIED]: الصلاحية DENIED أو PERMANENTLY_DENIED — توقف المزامنة وتبقى Room
 *   مقروءة (منع معياري: إبطال الصلاحية ليس حذفاً — ADR-0010 §5).
 * - [FAILED]: فشل حقيقي (استثناء من المنصة أو التخزين) — لا تقديم لقطة/رمز، وتُعاد المحاولة
 *   عند الإشارة/الانتقال التالي. الإلغاء (CancellationException) **ليس** فشلاً.
 * - [FULL_RESYNC_REQUIRED]: إعادة مزامنة كاملة جارية لأسباب غير أولية — انتقال صلاحية
 *   إلى/بين {GRANTED, LIMITED}، أو اكتشاف لقطة فاسدة/من منصة أخرى (قبل الاسترجاع التلقائي)،
 *   أو مصالحة دورية مستحقة (بوابة الأسبوع).
 */
enum class ContactsSyncState {
    IDLE,
    INITIAL_IMPORT,
    INCREMENTAL_SYNC,
    READY,
    PERMISSION_DENIED,
    FAILED,
    FULL_RESYNC_REQUIRED,
}
