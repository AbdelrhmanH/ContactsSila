package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import app.sila.core.model.ArhamDashboardStats
import app.sila.core.model.ArhamItem
import app.sila.core.model.ArhamPeriod
import app.sila.core.model.ArhamStatus
import app.sila.core.model.CallEvent
import app.sila.core.model.rankable
import app.sila.core.model.calculateUrgency
import app.sila.core.model.toArhamStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.Instant

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * حالة استخدام أحادية المسؤولية: تجميع إحصائيات لوحة أرحام من أحداث مكالمات **حقيقية**
 * (PRD §10.1 · خطة §D.3-1/§D.3-2 — Issue #29).
 *
 * تصحيح العيوب المثبتة (خطة §D.3):
 * - **D.3-1** الإحصاءات المُختلَقة (`items.size * 2 + connected`) استُبدلت بتجميع
 *   `call_events` فوق قاعدة AC-8: يُحتسب حدثٌ فقط إذا قال `CallEventStateMachine
 *   .updatesLastContacted(event)` نعم — **آلة الحالات هي المصدر الوحيد للقاعدة**
 *   (لا إعادة كتابتها هنا) — ومرتبطاً بقريب أرحام فعلاً.
 * - **D.3-2** المدة المُختلَقة (`540`) استُبدلت بـ `MAX(durationSec)` فوق الأحداث
 *   المؤهَّلة في الفترة؛ المدد `null` غير موثوقة فتُستبعد، ولا تُختلق مدة أبداً
 *   (صفر عند غياب كل المدد).
 * - **O-9** المنصة الثلاثية (`topThree`) كانت تمرّرِ الثلاثةَ **الأقل** إلحاحاً
 *   (`sortedBy { urgency }` تصاعدي — عيبٌ سابق كشفته فلترة T8-05). المواصفة §10.1:
 *   منصة الثلاثة الأكثر تواصلاً — **نداءاتٌ ناجحة ثم مدة** في الفترة (تسوية حتمية
 *   بمعرف جهة الاتصال)، ولا منصة بلا أي نداء ناجح (قائمة فارغة صادقة).
 *
 * قواعد مثبتة بالاختبارات (قرارات مشروحة في تقرير T8-07):
 * - **نافذة الفترة:** متدحرجة تنتهي عند `now` (ساعة محقونة — دلالة زمن النهاية للخطة):
 *   WEEK = `[now − 7 أيام, now]` وMONTH = `[now − 30 يوماً, now]`، الطرفان شاملان
 *   (`BETWEEN`). تُحسب عند التركيب (لكل اشتراك)؛ تغيير الفترة يعيد الاشتراك.
 * - **هدف الفترة (FA-001 — مواصفة §06 سطر 56):** نسبة الأقارب (غير المناسبات
 *   `OCCASIONS` وغير الموقوفين حالياً) الذين حالتهم `OK`. الحالة من اشتقاق النموذج
 *   الموحَّد نفسه (`calculateUrgency` ← `toArhamStatus`) فلا تنحرف عن القائمة المرتبة
 *   (درس O-7)؛ البسط جزءٌ من المقام بِنائِه فالقيمة ضمن `[0,1]` دوماً؛ المقام الفارغ
 *   (لا أقارب مؤهلين) ⇒ 0 بأمانة (لا 100 وهمية).
 * - `pausedCount` (AC-2 «موقوف بعدد» — مؤجل من T8-05): عدد الأقارب الموقوفين حالياً
 *   من غير المناسبات — الخفيون من القائمة المرتبة.
 * - المنصة تشمل الموقوفَين (المنصة عدُّ نشاطٍ لا ترتيب إلحاح).
 */
class GetArhamDashboardStatsUseCase(
    private val arhamRepository: ArhamRepository,
    private val callEventRepository: ArhamCallEventRepository,
    private val clock: AppClock,
    private val stateMachine: CallEventStateMachine = CallEventStateMachine(),
    private val dispatchers: AppDispatchers = DefaultAppDispatchers()
) {
    operator fun invoke(period: ArhamPeriod = ArhamPeriod.WEEK): Flow<ArhamDashboardStats> {
        val now: Instant = clock.now()
        val windowMillis = when (period) {
            ArhamPeriod.WEEK -> 7L * MILLIS_PER_DAY
            ArhamPeriod.MONTH -> 30L * MILLIS_PER_DAY
        }
        val fromMillis = now.toEpochMilliseconds() - windowMillis
        val toMillis = now.toEpochMilliseconds()

        return combine(
            arhamRepository.observeArhamContacts(),
            callEventRepository.observeEventsBetween(fromMillis = fromMillis, toMillis = toMillis),
        ) { contacts, windowEvents ->
            // `observeArhamContacts` يعود بأقارب أرحام حصراً (FAMILY && arham != null — عقد DAO).
            val arhamIds = contacts.map { it.id }.toSet()

            // قاعدة AC-8 من آلة الحالات حصراً — المرشّح المؤهَّل المرتبط بقريب أرحام.
            val qualifying: List<Pair<Long, CallEvent>> = windowEvents.mapNotNull { event ->
                val contactId = event.contactId ?: return@mapNotNull null
                if (contactId in arhamIds && stateMachine.updatesLastContacted(event)) {
                    contactId to event
                } else {
                    null
                }
            }

            val totalFamilyCalls = qualifying.size
            val contactedRelatives = qualifying.map { it.first }.distinct().size
            val longestCallSec = qualifying.mapNotNull { it.second.durationSec }.maxOrNull() ?: 0

            // عدّاد الموقوفين (AC-2): غير المناسبات الموقوفين حالياً — خفيو القائمة.
            val pausedCount = contacts.count { c ->
                val info = c.arham ?: return@count false
                if (!info.frequency.rankable) return@count false
                val paused = info.pausedUntil
                paused != null && paused > toMillis
            }

            // هدف الفترة (مواصفة ميزة أرحام — معايير القبول سطر 56): نسبة الأقارب
            // (غير المناسبات `OCCASIONS` وغير الموقوفين) في حالة `OK`. الاشتقاق عبر
            // دالتي النموذج الموحّدتين — مصدر قاعدة الحالة الوحيد (درس O-7)، لا إعادة
            // كتابتها هنا. البسط جزء من المقام بِنائِه ⇒ القيمة ضمن [0,1] دوماً،
            // والمقام الفارغ (لا أقارب مؤهلين) ⇒ 0 بأمانة.
            val goalPopulation = contacts.mapNotNull { it.arham }.filter { info ->
                if (!info.frequency.rankable || info.frequency.days <= 0) return@filter false
                val paused = info.pausedUntil
                !(paused != null && paused > toMillis)
            }
            val goalCompletion = if (goalPopulation.isEmpty()) {
                0f
            } else {
                goalPopulation.count { it.calculateUrgency(now).toArhamStatus() == ArhamStatus.OK }
                    .toFloat() / goalPopulation.size.toFloat()
            }

            // المنصة الثلاثية (O-9): الأكثر نداءاتٍ ناجحة، فالمدة الكلية، فالمعرف — حتمية.
            val perContact = qualifying
                .groupBy({ it.first }, { it.second })
                .entries
                .map { (contactId, events) ->
                    contactId to (events.size to events.sumOf { it.durationSec ?: 0 })
                }
                .sortedWith(
                    compareByDescending<Pair<Long, Pair<Int, Int>>> { it.second.first }
                        .thenByDescending { it.second.second }
                        .thenBy { it.first },
                )
                .take(3)
            val topThree: List<ArhamItem> = if (perContact.isEmpty()) {
                emptyList()
            } else {
                perContact.mapIndexed { index, (contactId, _) ->
                    val contact = contacts.first { it.id == contactId }
                    contact.toArhamItem(now).copy(rank = index + 1)
                }
            }

            ArhamDashboardStats(
                period = period,
                topThree = topThree,
                totalFamilyCalls = totalFamilyCalls,
                longestCallSec = longestCallSec,
                contactedRelatives = contactedRelatives,
                goalCompletion = goalCompletion,
                pausedCount = pausedCount
            )
        }.flowOn(dispatchers.default)
    }
}
