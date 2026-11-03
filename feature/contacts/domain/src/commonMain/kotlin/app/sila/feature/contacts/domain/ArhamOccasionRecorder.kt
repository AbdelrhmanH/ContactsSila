package app.sila.feature.contacts.domain

import app.sila.core.common.AppResult

/**
 * جسر تسجيل مناسبات أرحام — **أضيق عقد ممكن** يملكه `:feature:contacts` (T6 · J-4 ·
 * Issue #27).
 *
 * **لماذا واجهة هنا والتنفيذ في `sharedLogic`؟** قاعدة AGENTS.md: الميزات لا تعتمد
 * على بعضها أبداً. تعتمد `:feature:contacts` على `:feature:arham` (`ArhamRepository`)
 * يُخالف الحدود (يفحصها `checkArchitectureBoundaries`). فالعقد يعيش هنا، ويُنفَّذ
 * في جذر تركيب `sharedLogic` (`KoinModule`) بمفوِّض إلى `ArhamRepository` — عكس
 * التبعية فقط بلا أي معرفة اتصال بين الميزتين.
 *
 * **النطاق الضيق عمداً (J-4):** تسجيل عيد ميلاد جهة **عائلة** بلا صف أرحام قائم
 * كمناسبة مشتقة. الـ relation/layer الافتراضيان قرار تنفيذي الجذر (افتراضي
 * `Relation.CUSTOM` — `KinshipLayer.OTHER`) — تحرير العلاقة تفصيل T8 ولا يمر من هنا.
 */
interface ArhamOccasionRecorder {

    /**
     * إنشاء رابط أرحام افتراضي لجهة عائلة لديها عيد ميلاد مسجَّل حديثاً.
     *
     * @return `Success` عند إنشاء الرابط (أو وجود رابط قائم — idempotent)، وخطأ
     *   مُصنَّف عند الفشل — **لا يُفشل حفظ جهة الاتصال أبداً** (جانبي بنيوياً:
     *   يُستهلك بعد نجاح حفظ التفاصيل النهائي).
     */
    suspend fun recordBirthdayOccasion(contactId: Long, month: Int, day: Int): AppResult<Unit>
}

/**
 * حالة استخدام إنشاء مناسبة أرحام من عيد ميلاد (T6 · J-4) — تفويض نقي للجسر.
 */
class CreateArhamOccasionUseCase(
    private val recorder: ArhamOccasionRecorder,
) {
    suspend operator fun invoke(contactId: Long, month: Int, day: Int): AppResult<Unit> =
        recorder.recordBirthdayOccasion(contactId, month, day)
}
