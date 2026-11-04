package app.sila.feature.arham.domain

/**
 * نتيجة تشغيل تجسيد «أهم 5 أشخاص» (T8-04 · §F.2–§F.10).
 */
sealed interface TopFiveMaterializationOutcome {

    /** العلامة مرفوعة سابقاً — بلا أي قراءة إضافية أو كتابة (§F.10 · اختبار F.11-هـ). */
    data object AlreadyMaterialized : TopFiveMaterializationOutcome

    /** لا اختيارات معلّقة — لا شيء ليُجسَّد؛ العلامة لا تُرفع حمايةً لدلالة §F.6
     *  (إعادة الاختيار أثناء التهيئة تكتب القائمة قبل التجسيد). */
    data object NothingPending : TopFiveMaterializationOutcome

    /** سجل جهات الاتصال غير جاهز بعد (التزامن T2 لم يُدخل دفتر العناوين) — بلا إسقاط
     *  دائم وبلا رفع للعلامة؛ يُعاد المحاولة عند الإقلاع التالي (§F.2 حارس الجاهزية). */
    data object ContactsNotReady : TopFiveMaterializationOutcome

    /** نجح التشغيل (المعاملة التزمت والعلامة رُفعت بعدها): أعداد ما بعد التسابق. */
    data class Completed(
        /** جهات مُوِّدت فعلاً من الاختيار (§F.4). */
        val applied: Int,
        /** جهات حُفظت معلوماتها الذاتية القائمة — التصنيف وُحِّد حصراً (§F.5). */
        val preservedUserOwned: Int,
        /** اختيارات أُسقطت لغياب جهة حيّة (محذوفة/غير مُزامنة/خارج نطاق الإذن —
         *  §F.7) + ما تخلّف وسطياً؛ الإسقاط دائم ولا طابور إعادة (§F.10). */
        val dropped: Int,
    ) : TopFiveMaterializationOutcome
}
