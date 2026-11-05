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
 * اختبارات متحكم دور الفرز (T8-09 · AC-4) على Robolectric (شادو RoleManager):
 * الافتراضي «غير متاح/غير محتفظ به»، والمتاح يُنتج نية طلب، والمحتفَظ به يُقرأ
 * صحيحاً. طلبُ الموافقة من الواجهة نفسه مسؤولية T8-11 (انحراف مُفصح — بلا اختبار
 * واجهة هنا).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreeningRoleControllerHostTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun roleManager(): RoleManager = app.getSystemService(RoleManager::class.java)

    @Test
    fun `role neither available nor held by default`() {
        val controller = ScreeningRoleController(app)
        assertFalse(controller.isRoleHeld(), "بلا تهيئة ⇒ غير محتفظ به")
        assertNull(controller.createRequestRoleIntent(), "الدور غير متاح بالظل افتراضياً ⇒ لا نية طلب")
    }

    @Test
    fun `available role produces a request intent`() {
        shadowOf(roleManager()).addAvailableRole(RoleManager.ROLE_CALL_SCREENING)
        assertTrue(ScreeningRoleController(app).createRequestRoleIntent() != null, "متاح ⇒ نية طلب صالحة")
    }

    @Test
    fun `held role is reported`() {
        val shadow = shadowOf(roleManager())
        shadow.addAvailableRole(RoleManager.ROLE_CALL_SCREENING)
        shadow.addHeldRole(RoleManager.ROLE_CALL_SCREENING)
        assertTrue(ScreeningRoleController(app).isRoleHeld(), "الظل يحتفظ بالدور ⇒ المقروء صحيح")
    }
}
