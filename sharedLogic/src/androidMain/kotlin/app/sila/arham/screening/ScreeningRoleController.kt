package app.sila.arham.screening

import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.role.RoleManager
import android.util.Log

/**
 * متحكم دور فرز المكالمات (T8-09 · AC-4) — فحص الاحتفاظ ونية الطلب حصراً؛ واجهة
 * طلب الموافقة نفسها (العرض وشرح الأثر) مسؤولية T8-11 (انحراف مُفصح: قسم واجهة
 * الموافقة من خطة DeepSeek أُجِّل مع إعادة بناء العرض).
 *
 * الدور غير موجود قبل API 29 ⇒ «غير محتفظ به» و«لا نية طلب» — بلا انهيار، ويسلك
 * التطبيق مسار التسجيل داخل التطبيق + التأكيد اليدوي (بديل AC-4 المضمون).
 */
class ScreeningRoleController(private val context: Context) {

    /** هل دور فرز المكالمات محتفظ به حالياً؟ (قبل API 29: الدور لم يوجد ⇒ `false`). */
    fun isRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    /**
     * نية طلب الدور من الواجهة (T8-11 تُطلقها)، أو `null` إن كان غير متاح —
     * قبل API 29، أو غياب `RoleManager`، أو عدم توفّر الدور على الجهاز.
     */
    fun createRequestRoleIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 29) return null
        val roleManager = context.getSystemService(RoleManager::class.java) ?: run {
            Log.w(TAG, "RoleManager unavailable ? screening role not requestable")
            return null
        }
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            Log.w(TAG, "ROLE_CALL_SCREENING not available on device ? not requestable")
            return null
        }
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }

    private companion object {
        const val TAG = "ScreeningRoleController"
    }
}
