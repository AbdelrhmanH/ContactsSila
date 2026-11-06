package app.sila.feature.arham.domain

import app.sila.core.model.CallEvent
import kotlinx.coroutines.flow.Flow

/**
 * حالة استخدام: مرصد الأحداث غير المحسومة (AC-7 — خطة §D.3-4/T8-06): أحداث مرصودة
 * منصياً تأكيدُها `PENDING` — تُعرض على المستخدم ليحسمها («تم التواصل؟»)، وهي لا
 * تُحدّث `lastContactedAt` قبل الحسم (بوابة AC-8 في آلة الحالات).
 *
 * تمريرٌ رقيق فوق [ArhamCallEventRepository] — بلا منطق إضافي؛ الاستهلاك في العارض (T8-11).
 */
class ObservePendingConfirmationsUseCase(
    private val repository: ArhamCallEventRepository,
) {
    operator fun invoke(): Flow<List<CallEvent>> = repository.observePendingConfirmations()
}
