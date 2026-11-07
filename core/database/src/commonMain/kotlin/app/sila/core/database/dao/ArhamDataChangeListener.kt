package app.sila.core.database.dao

/**
 * خطاف «تغيّرت بيانات أرحام» (T8-10 · قرار D3: تحديثات مُحرَّكة بالتغيير · Issue #29).
 *
 * تعريفه في `LocalSilaDatabase` (core:database) والإشتراك عليه في جذر التركيب —
 * نفس نمط الجسر `ArhamBridgeOccasionRecorder`: الاتجاه قانوني (الواجهة لا تعتمد
 * على المنصة)، والمستمع الوحيد اليوم مُنعِش الودجت على Android (worker يُطلق
 * مرةً واحدة مُدمجةً لكل دفعة كتابات — `REPLACE` يُدمِّج تلقائياً).
 *
 * **بعد COMMIT حصراً**: يُستدعى بعد نجاح الكتابة واكتمالها — فشل المستمع نفسه
 * مسؤولية المشترك (يُحاط runCatching هناك) ولا يجب أن يُفسد الكتابة.
 */
fun interface ArhamDataChangeListener {

    /** يُستدعى بعد COMMIT عملية كتابة مؤثرة على حالة أرحام المشتقة (آخر تواصل/أحداث/جهات). */
    fun onArhamDataChanged()
}
