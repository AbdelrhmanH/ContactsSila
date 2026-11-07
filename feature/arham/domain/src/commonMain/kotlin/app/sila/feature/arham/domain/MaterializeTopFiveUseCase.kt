package app.sila.feature.arham.domain

import app.sila.core.model.ArhamInfo

/**
 * حالة استخدام: تجسيد اختيارات «أهم 5 أشخاص» من التهيئة الأولى في سجل جهات صِلَة
 * (T8-04 · §F.1–§F.10 من الخطة المُصادقة · Issue #29).
 *
 * التشغيل مرة واحدة لكل تثبيت (§F.2): حارس العلامة الواحدة أولاً، ثم قراءة القائمة
 * المعلّقة من DataStore عبر المستودع (§F.1)، ثم حارس الجاهزية (لا إسقاط دائم قبل
 * امتلاء الدفتر — §F.2)، فالتطابق بالهوية النظامية حصراً (§F.3 — الاسم للعرض فقط)،
 * فالتطبيق داخل معاملة Room واحدة عبر المستودع (§F.8/§F.9)، والعلامة تُرفع **بعد**
 * نجاح المعاملة حصراً (فشلٌ بينهما آمن لأن الإعادة idempotent — §F.6).
 *
 * الإسقاط (§F.7) لمن لا جهة حيّة له: يُحسب في التقرير، ولا صف وهمي يُنشأ، ولا طابور
 * إعادة؛ وقد يكون دائماً بعد نجاح أول تشغيل (§F.10 «العلامة لا تُعاد فتحها»).
 * الاستثناءات تنتشر بلا ابتلاع — لا يُرفع الخاد على فشل أبداً (اختبار F.11-د).
 */
class MaterializeTopFiveUseCase(
    private val repository: TopFiveMaterializationRepository,
) {

    suspend operator fun invoke(): TopFiveMaterializationOutcome {
        // الحارس الأول: تشغيل مكتمل سابقاً — لا كتابة إطلاقاً (اختبار F.11-هـ).
        if (repository.isMaterialized()) {
            return TopFiveMaterializationOutcome.AlreadyMaterialized
        }

        // القائمة المعلّقة؛ وتكرار الهوية نفسها ينهار إلى أول اختيار (F.6 idempotent).
        val selections = repository.pendingSelections().distinctBy { it.systemContactId }
        if (selections.isEmpty()) {
            return TopFiveMaterializationOutcome.NothingPending
        }

        // حارس الجاهزية (§F.2): قبل امتلاء الدفتر لا يُسقط شيء ولا يُرفع الخاد —
        // وإلا صارت «الفشل الجاهزي» إسقاطاً دائماً بموجب §F.10.
        if (!repository.hasLiveContacts()) {
            return TopFiveMaterializationOutcome.ContactsNotReady
        }

        val liveById = repository.findContactsBySystemContactIds(
            selections.map { it.systemContactId }.toSet(),
        )

        var dropped = 0
        val targets = mutableListOf<TopFiveMaterializationTarget>()
        selections.forEach { selection ->
            // §F.3: المطابقة هوية المنصة حصراً؛ `displayName` للعرض فقط ولا يطابق أبداً.
            // تكرار الهوية النظامية بين صفّين (فهرس غير فريد) يُحسم بمعرف القاعدة الأصغر
            // (حتمي وفي أي ترتيب كاش).
            val match = liveById
                .filter { it.systemContactId == selection.systemContactId }
                .minByOrNull { it.id }
            if (match == null) {
                dropped++
            } else {
                // §F.4/§F.5: التصنيف يُوحد إلى FAMILY، ومعلومة أرحام جديدة تُملأ فقط
                // بغيابها — الحارس النهائي على حقيقة القاعدة وقت الكتابة (المستودع)،
                // و`lastContactedAt` لا يُلمس إطلاقاً (AC-8).
                targets += TopFiveMaterializationTarget(
                    contactId = match.id,
                    arham = ArhamInfo(relation = selection.relation, frequency = selection.frequency),
                )
            }
        }

        // §F.8: معاملة واحدة عبر المستودع؛ الفشل ينتشر — تُرجَع الدفعة كلها ويظل الخاد
        // غير مرفوع (إعادة التشغيل التالية تكمل idempotently). الأعداد الواردة هنا هي
        // حقيقة ما بعد التسابق، فتُدمج إسقاطات القرار مع ما تخلّف وسطياً (§F.7).
        val counts = repository.applyMaterialization(targets)
        repository.markMaterialized()
        return TopFiveMaterializationOutcome.Completed(
            applied = counts.applied,
            preservedUserOwned = counts.preservedUserOwned,
            dropped = dropped + counts.skippedMissing,
        )
    }
}
