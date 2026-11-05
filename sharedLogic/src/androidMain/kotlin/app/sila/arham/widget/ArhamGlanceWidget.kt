package app.sila.arham.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.FilledButton
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.sila.arham.notification.resolveArhamKoin
import app.sila.feature.arham.domain.ArhamWidgetSnapshot
import app.sila.feature.arham.domain.GetArhamWidgetSnapshotUseCase
import app.sila.feature.arham.domain.RecordDialAttemptUseCase

/**
 * ودجت أرحام (T8-10 · AC-15 · Issue #29) — ثلاث فئات حجم (2×2 عدد أحمر، 4×2 أعلى-3
 * بأزرار اتصال، 4×4 + شريط تقدم وإحصاءات). القراءة من Room المشتق حصراً عبر
 * [GetArhamWidgetSnapshotUseCase] عند كل إعادة تركيب (SSOT · D3)، وكل النصوص من
 * موارد الترجمة — **بلا نصٍّ ثابت في الكود** (RTL/داكن عبر GlanceTheme والموارد).
 *
 * الكادنس خارج الودجت حصراً: `updatePeriodMillis = 0` والانعاش عبر worker D3 —
 * كلمة «دقيق» لا تظهر في أي نصٍّ للمستخدم (§D3-13)، وتقادمٌ مؤقت يبقى بآخر تركيب
 * مخزَّن (سقوط §O.8 الودجتي). الألوان دلالية من [GlanceTheme.colors] (داكن/فاتح
 * تلقائياً — Glance 1.2: `ColorProvider` لونٌ واحد فقط، بلا زوجي day/night).
 */
object ArhamGlanceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        sizes = setOf(
            DpSize(width = 110.dp, height = 110.dp), // 2×2
            DpSize(width = 250.dp, height = 110.dp), // 4×2
            DpSize(width = 300.dp, height = 300.dp), // 4×4
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val koin = resolveArhamKoin() ?: return // بلا Koin ⇒ آخر تركيب مخزَّن (سقوط §O.8)
        val snapshot = runCatching { koin.get<GetArhamWidgetSnapshotUseCase>()() }
            .getOrNull() ?: return // فشل القراءة ⇒ سقوطٌ هادئ لآخر تركيب (§O.5/§O.8)

        provideContent { WidgetRoot(snapshot) }
    }

    @Composable
    private fun WidgetRoot(snapshot: ArhamWidgetSnapshot) {
        val width = LocalSize.current.width
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(8.dp)
                .background(GlanceTheme.colors.background),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            if (width <= SMALL_BREAKPOINT) {
                SmallContent(snapshot)
            } else {
                WideContent(snapshot, includeStats = width >= LARGE_BREAKPOINT)
            }
        }
    }

    /** 2×2: العدد الأحمر + التسمية — نقرة تفتح التطبيق (بلا PII في أي intent). */
    @Composable
    private fun SmallContent(snapshot: ArhamWidgetSnapshot) {
        val context = LocalContext.current
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(4.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(
                text = snapshot.overdueCount.toString(),
                modifier = GlanceModifier.padding(bottom = 4.dp),
                style = TextStyle(
                    color = GlanceTheme.colors.error,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = context.getString(app.sila.sharedLogic.R.string.arham_widget_overdue_label, snapshot.overdueCount),
                style = TextStyle(color = GlanceTheme.colors.onSurface),
            )
        }
    }

    /** 4×2 (+4×4): أعلى-3 بصفوف مع زر اتصال لكل صف؛ الكبير يضيف شريط التقدم والإحصاءات. */
    @Composable
    private fun WideContent(snapshot: ArhamWidgetSnapshot, includeStats: Boolean) {
        val context = LocalContext.current
        Column(modifier = GlanceModifier.fillMaxSize().padding(4.dp)) {
            if (snapshot.entries.isEmpty()) {
                Text(
                    text = context.getString(app.sila.sharedLogic.R.string.arham_widget_empty),
                    style = TextStyle(color = GlanceTheme.colors.onSurface),
                )
                return@Column
            }
            snapshot.entries.forEach { entry ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = entry.displayName,
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                            maxLines = 1,
                        )
                        Text(
                            text = daysSinceLabel(context, entry.daysSinceLastContact),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 11.sp,
                            ),
                            maxLines = 1,
                        )
                    }
                    FilledButton(
                        text = context.getString(app.sila.sharedLogic.R.string.arham_widget_call),
                        onClick = actionRunCallback<WidgetCallAction>(
                            actionParametersOf(WidgetCallAction.KEY_CONTACT_ID to entry.contactId),
                        ),
                    )
                }
            }
            if (includeStats) {
                Spacer(modifier = GlanceModifier.padding(top = 4.dp))
                LinearProgressIndicator(
                    progress = snapshot.goalCompletionFraction,
                    modifier = GlanceModifier.fillMaxWidth(),
                    color = GlanceTheme.colors.primary,
                    backgroundColor = GlanceTheme.colors.surfaceVariant,
                )
                Text(
                    text = context.getString(
                        app.sila.sharedLogic.R.string.arham_widget_stats_line,
                        snapshot.totalFamilyCalls,
                        snapshot.longestCallSec,
                    ),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
        }
    }

    /** أيام منذ آخر تواصل — `null` (لا تواصل سُجِّل) ⇒ نص «لم يُسجَّل تواصل». */
    @Composable
    private fun daysSinceLabel(context: Context, days: Int?): String = when (days) {
        null -> context.getString(app.sila.sharedLogic.R.string.arham_widget_no_contact_recorded)
        else -> context.getString(app.sila.sharedLogic.R.string.arham_widget_days_since, days)
    }

    private val SMALL_BREAKPOINT = 180.dp
    private val LARGE_BREAKPOINT = 300.dp
}

/**
 * فعل زر الاتصال من الودجت (T8-10 · AC-15) — **معرف حصراً في البارامترات** (بلا PII في
 * أي extras): الرقم يُستعاد لحظياً من Room عبر Koin، تُسجَّل المحاولة `IN_APP` عبر
 * [RecordDialAttemptUseCase] (لا اكتمالٍ مُختلق — AC-3)، ثم طلب الاتصال عبر الطلبار.
 * أي إخفاق يُسجَّل بنوعٍ حصراً (§O.5).
 */
class WidgetCallAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val contactId = parameters[KEY_CONTACT_ID] ?: return
        val koin = resolveArhamKoin() ?: return
        runCatching {
            val contact = koin.get<app.sila.core.database.dao.ContactDao>().getContactById(contactId)
                ?: return
            val phone = contact.phones.firstOrNull { it.isPrimary }?.e164
                ?: contact.phones.firstOrNull()?.e164
                ?: return
            val clock = koin.get<app.sila.core.common.AppClock>()
            koin.get<RecordDialAttemptUseCase>()(
                contactId = contactId,
                remoteNumber = phone,
                timestampMillis = clock.nowEpochMillis(),
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("tel:$phone"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Log.w("ArhamWidgetCall", "Widget call failed: ${it.javaClass.simpleName}")
        }
    }

    companion object {
        val KEY_CONTACT_ID = ActionParameters.Key<Long>("arham_widget_contact_id")
    }
}

/** المستقبل — نقطة الربط مع المنصة (provider XML عبر meta-data في الـ manifest). */
class ArhamWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ArhamGlanceWidget
}
