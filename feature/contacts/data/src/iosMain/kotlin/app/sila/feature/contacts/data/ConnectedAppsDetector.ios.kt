package app.sila.feature.contacts.data

import app.sila.feature.contacts.domain.ConnectedApp
import app.sila.feature.contacts.domain.ConnectedAppsDetector
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * تنفيذ iOS لكاشف التطبيقات المتصلة [ConnectedAppsDetector] (T6 · Issue #27):
 *
 * `UIApplication.canOpenURL` بمخططات مصرَّح بها في `Info.plist`
 * (`LSApplicationQueriesSchemes` — الحد الأعظمي 50 مخططاً؛ القائمة المغلقة
 * الثلاثية بعيدة عن الحد). المخطط غير المصرَّح به يردّ `false` دائماً فيظهر
 * كأنه غير مثبت — سلوك iOS الموثق؛ المخططات تُضاف في iosApp (خطوة Layer 5).
 */
class IosConnectedAppsDetector : ConnectedAppsDetector {

    override suspend fun detect(): List<ConnectedApp> =
        CANDIDATES.filter { (_, scheme) -> canOpen(scheme) }
            .map { (id, scheme) -> ConnectedApp(id = id, scheme = scheme) }

    private fun canOpen(scheme: String): Boolean {
        val url = NSURL(string = "$scheme://") ?: return false
        return UIApplication.sharedApplication.canOpenURL(url)
    }

    private companion object {
        /** القائمة المغلقة الموثقة في المواصفة 03 — مخططات iOS لأزرار التطبيقات المتصلة. */
        val CANDIDATES = listOf(
            "whatsapp" to "whatsapp",
            "telegram" to "tg",
            "signal" to "sgnl",
        )
    }
}
