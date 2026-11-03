package app.sila.feature.contacts.data

import android.content.Context
import android.content.pm.PackageManager
import app.sila.feature.contacts.domain.ConnectedApp
import app.sila.feature.contacts.domain.ConnectedAppsDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * تنفيذ Android لكاشف التطبيقات المتصلة [ConnectedAppsDetector] (T6 · Issue #27):
 *
 * استعلام `getPackageInfo` للحزم المرشحة حصراً — **بلا** `QUERY_ALL_PACKAGES`
 * (الاستعلام المسماة مسموح ضمن `<queries>` في المانيفست) وبلا وصول شبكي إطلاقاً
 * (خصوصية AGENTS.md). الحزمة غير المثبتة تردّ `NameNotFoundException` = غير متصل.
 */
class AndroidConnectedAppsDetector(
    private val applicationContext: Context,
) : ConnectedAppsDetector {

    override suspend fun detect(): List<ConnectedApp> = withContext(Dispatchers.IO) {
        val packageManager = applicationContext.packageManager
        CANDIDATES.mapNotNull { candidate ->
            if (isInstalled(packageManager, candidate.packageName)) candidate.toApp() else null
        }
    }

    private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean =
        runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private data class Candidate(val id: String, val scheme: String, val packageName: String)

    private fun Candidate.toApp() = ConnectedApp(id = id, scheme = scheme)

    private companion object {
        /** القائمة المغلقة الموثقة في المواصفة 03 — حزم Android لأزرار التطبيقات المتصلة. */
        val CANDIDATES = listOf(
            Candidate(id = "whatsapp", scheme = "whatsapp", packageName = "com.whatsapp"),
            Candidate(id = "telegram", scheme = "tg", packageName = "org.telegram.messenger"),
            Candidate(id = "signal", scheme = "sgnl", packageName = "org.thoughtcrime.securesms"),
        )
    }
}
