package app.sila.feature.contacts.domain.onboarding

import app.sila.core.model.TopFiveSelection
import kotlinx.coroutines.flow.Flow

/**
 * عقد مستودع التهيئة الأولى (:feature:contacts:domain — Dependency Inversion).
 * محلي 100% (DataStore عبر :core:datastore من طبقة data)؛ لا شبكة ولا سحابة إطلاقاً.
 *
 * ## عقد التسليم إلى T8 (ميزة أرحام)
 * الاختيارات المحفوظة عبر [saveTopFiveSelections] هي **سجل تسليم** فقط (Handoff record):
 * - المرحلة 1 لا تحتوي جدول جهات اتصال ممتلئاً — ينشأ بعد مهمة T2 (مزامنة جهات الاتصال).
 *   لذلك لا تُنشأ هنا سجلات جهات/أرحام حقيقية، ولا بيانات وهمية بديلة (لا fake-data).
 * - عند توفر جهات الاتصال، تنشئ ميزة أرحام سجلات بتصنيف `Category.FAMILY` ومعلومة
 *   أرحام `ArhamInfo(relation = relation, frequency = WEEKLY)` من سجل [TopFiveSelection].
 * - فصل الميزات إلزامي (قاعدة Konsist رقم 3): لا يمكن استيراد ميزة أرحام من هنا،
 *   فيبقى هذا السجل هو الربط المستندي بين الميزتين.
 */
interface OnboardingRepository {
    fun observeState(): Flow<OnboardingState>

    /**
     * يعلن اكتمال [step] ويدوّم الخطوة التالية (أول خطوة غير مكتملة) بحيث تبقى
     * باقية بعد انتهاء العملية ثم العودة (process death).
     * عند اكتمال الخطوة الأخيرة (ORGANIZING) يتِم اعتبار التهيئة مكتملة بالكامل.
     */
    suspend fun completeStep(step: OnboardingStep)

    /** يدوّم نتيجة الصلاحية التي أبلغت عنها طبقة المنصة (لا طلب صلحية أبداً من الدومين). */
    suspend fun setContactsPermission(state: OnboardingPermissionState)

    /** يدوّم اختيارات "أهم 5 أشخاص" (يقصها إلى الحد الأقصى 5) لتستهلكها ميزة أرحام لاحقاً. */
    suspend fun saveTopFiveSelections(selections: List<TopFiveSelection>)

    /** يقفل التهيئة نهائياً: onboardingCompleted = true مع isGuest = true وتصفير الخطوة. */
    suspend fun completeOnboarding()
}
