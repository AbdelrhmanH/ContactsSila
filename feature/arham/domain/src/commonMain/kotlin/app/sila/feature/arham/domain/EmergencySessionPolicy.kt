package app.sila.feature.arham.domain

/**
 * سياسات جلسات الطوارئ الحُسمة — قرار D4 المُصادق حرفياً (§N.2/§N.4/§N.5 · Issue #29):
 * حد المعدل **3 جلسات / 24 ساعة دحرجة** (كل الحالات تُحصى — الصفوف المنتهية تبقى في
 * النافذة لهذا الغرض ويطهَّر بعد 48 ساعة)، وسقف **10 محاولات لكل جلسة** (§C.1-8:
 * CALL→IDLE→5ث→CALL حتى الإجابة/التوقف/10 محاولات)، وحد حياة الجلسة لتسوية موت
 * العملية (§N.5 «أقدم من حد الحياة» — القيمة غير مثبتة في الخطة؛ **مقترح 30 دقيقة**
 * انحرافٌ مُفصَح: جولة شرعية ≤ 10 × (مكالمة + 5ث) ≪ 30 د).
 */
data class EmergencySessionPolicy(
    val maxSessionsPer24h: Int = 3,
    val maxAttemptsPerSession: Int = 10,
    val sessionLivenessBoundMillis: Long = 30L * 60L * 1000L,
) {

    /** بداية جلسة جديدة تشترط عدّاد النافذة أدنى من الحد — مقارنة صارمة (strict `<`). */
    fun canStartSession(sessionsStartedInWindow: Int): Boolean =
        sessionsStartedInWindow < maxSessionsPer24h

    /** بلوغ السقف (§N.4: MAX_REACHED عند 10) — العدد *بعد* التسجيل يُقارن بـ ≥. */
    fun isAttemptCeilingReached(attemptsAfterRecord: Int): Boolean =
        attemptsAfterRecord >= maxAttemptsPerSession

    /** هل الجلسة العالقة أقدم من حد الحياة (تسوية §N.5)؟ */
    fun isStaleInProgress(lastAttemptAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - lastAttemptAtMillis > sessionLivenessBoundMillis

    private companion object {
        const val MILLIS_PER_24H = 24L * 60L * 60L * 1000L
    }

    /** نافذة حد المعدل بالمللي — 24 ساعة دحرجة (D4). */
    val rateLimitWindowMillis: Long = MILLIS_PER_24H
}
