package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import app.sila.core.model.KinshipLayer
import app.sila.core.model.calculateUrgency
import app.sila.core.model.toArhamStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * حالة استخدام أحادية المسؤولية: بناء نموذج بيانات شجرة العائلة (T8-07 · AC-13) من
 * أقارب أرحام (`Relation.layer`/`ArhamInfo.parentContactId`) — الرسم واللمس T8-11.
 *
 * قواعد البناء (مثبتة بالاختبارات):
 * - **التوليدات بالطبقات:** العقد تُجمع على `ArhamInfo.layer` (منسوخة من `Relation.layer`
 *   افتراضياً) بترتيب إعلان `KinshipLayer` (الأسلاف أولاً) — لا مشىً على سلسلة الآباء
 *   إطلاقاً، فلا تعليق عند الحلقات.
 * - **الوصلات:** (ابن←والد) حين يكون `parentContactId` عقدةً موجودة وغير نفسِ الابن؛
 *   الوالد الغائب عن الشجرة (يتيم) أو الذاتي تُهمَل وصلته وتبقى العقدة في طبقتها.
 * - **الحتمية:** العقد مرتبة (الاسم المعروض ثم المعرف) داخل كل توليدة؛ التوليدات الفارغة
 *   لا تُصدَر.
 */
class BuildArhamFamilyTreeUseCase(
    private val repository: ArhamRepository,
    private val clock: AppClock,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers()
) {
    operator fun invoke(): Flow<ArhamFamilyTree> = repository.observeArhamContacts().map { contacts ->
        val now = clock.now()
        val nowMs = now.toEpochMilliseconds()

        val nodes = contacts
            .map { contact ->
                val info = requireNotNull(contact.arham) { "عقدة الشجرة تتطلب قريباً أرحام (arham != null)" }
                val urgency = info.calculateUrgency(now)
                ArhamTreeNode(
                    contactId = contact.id,
                    displayName = contact.name.displayName,
                    photoUri = contact.photoUri,
                    relation = info.relation,
                    layer = info.layer,
                    parentContactId = info.parentContactId,
                    urgency = urgency,
                    status = urgency.toArhamStatus(),
                    daysSinceLastContact = info.lastContactedAt?.let { last ->
                        ((nowMs - last).coerceAtLeast(0L) / MILLIS_PER_DAY).toInt()
                    },
                )
            }
            .sortedWith(compareBy({ it.displayName }, { it.contactId }))
        val ids = nodes.map { it.contactId }.toSet()

        val generations = KinshipLayer.entries.mapNotNull { layer ->
            val members = nodes.filter { it.layer == layer }
            if (members.isEmpty()) null else ArhamTreeGeneration(layer = layer, nodes = members)
        }
        val edges = nodes
            .filter { it.parentContactId != null && it.parentContactId != it.contactId }
            .filter { it.parentContactId in ids }
            .map { ArhamTreeEdge(childContactId = it.contactId, parentContactId = it.parentContactId!!) }

        ArhamFamilyTree(generations = generations, edges = edges)
    }.flowOn(dispatchers.default)
}
