package app.sila.arham.screening

import android.app.role.RoleManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * فرع ما قبل API 29 لمتحكم دور الفرز (T8-09 · AC-4): الدور لم يوجد قبل 29 —
 * كل القراءات تعيد «غير محتفظ/غير قابل للطلب» بلا انهيار، ومسار التسجيل داخل
 * التطبيق + التأكيد اليدوي هو البديل المضمون.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreeningRoleControllerPreRoleHostTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun `before API 29 role is never held and never requestable`() {
        val controller = ScreeningRoleController(app)
        assertFalse(controller.isRoleHeld(), "الدور لم يوجد قبل 29")
        assertNull(controller.createRequestRoleIntent(), "لا نية طلب قبل 29")
    }
}
