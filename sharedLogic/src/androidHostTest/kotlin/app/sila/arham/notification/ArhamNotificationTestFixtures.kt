package app.sila.arham.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import app.sila.core.database.dao.ContactDao
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import org.koin.core.Koin
import org.robolectric.Shadows.shadowOf
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun Context.testNotificationManager(): NotificationManager =
    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

internal fun Context.testAlarmManager(): AlarmManager =
    getSystemService(Context.ALARM_SERVICE) as AlarmManager

/** يرصد إشعار التذكير الفعلي عبر خط الإطلاق القياسي (جهة اصطناعية بلا PII). */
internal suspend fun postDigestNotification(
    context: Context,
    koin: Koin,
    displayName: String = "عم التأخير",
) {
    koin.get<ContactDao>().upsertContact(
        Contact(
            id = 0L,
            systemContactId = "t8_an08_fixture",
            name = ContactName(displayName = displayName),
            phones = listOf(Phone(raw = "01000000000", e164 = "+201000000000", isPrimary = true)),
            category = Category.FAMILY,
            arham = ArhamInfo(
                relation = Relation.UNCLE_PATERNAL,
                frequency = ArhamFrequency.WEEKLY,
                lastContactedAt = System.currentTimeMillis() - 30L * 86_400_000L,
            ),
        ),
    )
    ArhamReminderDispatch.fireDailyReminder(
        context = context,
        preferences = koin.get(),
        planner = koin.get(),
        scheduler = koin.get(),
        notificationsEnabled = true,
    )
}

/** يقرأ `<intent-filter>` الفعلي لـ `.MainActivity` من `AndroidManifest.xml` ويسجله في Robolectric. */
internal fun registerRealManifestMainActivity(context: Context): ComponentName {
    val manifest = findAndroidAppManifestFile()
    val androidNs = "http://schemas.android.com/apk/res/android"
    val doc = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(manifest)

    val activityEl = findMainActivityElement(doc.getElementsByTagName("activity"), androidNs)
    val exported = attr(activityEl, androidNs, "exported") == "true"
    val (actions, categories) = extractIntentFilter(activityEl, androidNs)
    assertTrue(Intent.ACTION_MAIN in actions, "MainActivity filter declares ACTION_MAIN")
    assertTrue(Intent.CATEGORY_LAUNCHER in categories, "MainActivity filter declares CATEGORY_LAUNCHER")
    assertFalse(Intent.CATEGORY_DEFAULT in categories, "MainActivity launcher filter omits CATEGORY_DEFAULT")

    val mainComponent = ComponentName(context.packageName, ArhamNotificationPoster.MAIN_ACTIVITY_CLASS_NAME)
    val activityInfo = ActivityInfo().apply {
        packageName = context.packageName
        name = mainComponent.className
        this.exported = exported
    }
    val launcherFilter = IntentFilter().apply {
        actions.forEach(::addAction)
        categories.forEach(::addCategory)
    }
    shadowOf(context.packageManager).apply {
        addOrUpdateActivity(activityInfo)
        addIntentFilterForActivity(mainComponent, launcherFilter)
    }
    return mainComponent
}

private fun findAndroidAppManifestFile(): File {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (dir != null) {
        val candidate = dir.resolve("androidApp/src/main/AndroidManifest.xml")
        if (candidate.exists()) return candidate
        dir = dir.parentFile
    }
    error("androidApp/src/main/AndroidManifest.xml not found from ${System.getProperty("user.dir")}")
}

private fun findMainActivityElement(nodes: NodeList, androidNs: String): Element {
    for (i in 0 until nodes.length) {
        val el = nodes.item(i) as Element
        val name = attr(el, androidNs, "name")
        if (name == ".MainActivity" || name == ArhamNotificationPoster.MAIN_ACTIVITY_CLASS_NAME) {
            return el
        }
    }
    error(".MainActivity must exist in AndroidManifest.xml")
}

private fun extractIntentFilter(activityEl: Element, androidNs: String): Pair<List<String>, List<String>> {
    val actions = mutableListOf<String>()
    val categories = mutableListOf<String>()
    val filters = activityEl.getElementsByTagName("intent-filter")
    for (i in 0 until filters.length) {
        val filterEl = filters.item(i) as Element
        val actionNodes = filterEl.getElementsByTagName("action")
        for (j in 0 until actionNodes.length) {
            actions += attr(actionNodes.item(j) as Element, androidNs, "name")
        }
        val categoryNodes = filterEl.getElementsByTagName("category")
        for (j in 0 until categoryNodes.length) {
            categories += attr(categoryNodes.item(j) as Element, androidNs, "name")
        }
    }
    return actions to categories
}

private fun attr(element: Element, namespace: String, localName: String): String =
    element.getAttributeNS(namespace, localName).ifEmpty { element.getAttribute("android:$localName") }
