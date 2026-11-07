package app.sila.feature.arham.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sila.core.designsystem.SilaColors
import app.sila.core.designsystem.SilaDimens
import app.sila.core.model.KinshipLayer
import app.sila.feature.arham.domain.ArhamFamilyTree

/** هندسة العقدة في الشجرة (AC-13) — موضع أقصى-اليسار (إحداثيات مطلقة بلا RTL). */
private data class TreeNodePlacement(
    val contactId: Long,
    val left: Dp,
    val top: Dp,
)

/**
 * التخطيط الحتمي الصافي للشجرة (T8-11 · AC-13) — يُشارَك بين رسم الوصلات (Canvas)
 * وتموضع البطاقات عبر `remember(tree)` بحيث لا يتباعد الرسم عن النقر (وظيفة نقية
 * واحدة — خطة §P-T8-11 «computed positions shared»).
 */
private data class TreeLayout(
    val placements: Map<Long, TreeNodePlacement>,
    val layerRows: List<Pair<KinshipLayer, Dp>>, // الطبقة + قمة الصف (ترتيب الإعلان)
    val addButtons: List<Triple<KinshipLayer, Dp, Dp>>, // الطبقة + (يس، أعلى) زر +
    val contentWidth: Dp,
    val contentHeight: Dp,
)

private val NODE_WIDTH = 132.dp
private val NODE_HEIGHT = 56.dp
private val H_GAP = 16.dp
private val V_GAP = 40.dp
private val ADD_SIZE = 40.dp

private fun computeTreeLayout(tree: ArhamFamilyTree): TreeLayout {
    val placements = mutableMapOf<Long, TreeNodePlacement>()
    val layerRows = mutableListOf<Pair<KinshipLayer, Dp>>()
    val addButtons = mutableListOf<Triple<KinshipLayer, Dp, Dp>>()
    var top = 0.dp
    var maxWidth = 0.dp
    tree.generations.forEach { generation ->
        layerRows += generation.layer to top
        var left = 0.dp
        generation.nodes.forEach { node ->
            placements[node.contactId] = TreeNodePlacement(contactId = node.contactId, left = left, top = top)
            left += NODE_WIDTH + H_GAP
        }
        // «+» في نهاية كل صف توليدة (AC-13: عقدة فارغة = ربط قائم بأرحام).
        addButtons += Triple(generation.layer, left, top + (NODE_HEIGHT - ADD_SIZE) / 2)
        left += ADD_SIZE + H_GAP
        maxWidth = maxOf(maxWidth, left)
        top += NODE_HEIGHT + V_GAP
    }
    return TreeLayout(
        placements = placements,
        layerRows = layerRows,
        addButtons = addButtons,
        contentWidth = maxWidth,
        contentHeight = if (top > 0.dp) top - V_GAP else 0.dp,
    )
}

/**
 * شاشة شجرة العائلة (T8-11 · AC-13 · Issue #29) — حالةٌ داخل تبويب أرحام (بلا
 * NavHost — انحراف مُفصح): تقريب/تحريك بإصبعين (`detectTransformGestures`)،
 * «توسيطي» يعيد الكادر إلى أعلى الشجرة (صف الفرع الذاتي ظاهر)، وصلات الآباء
 * تُرسم على Canvas بإحداثيات التخطيط الحتمي نفسه، و«+» لكل توليدة تفتح منتقي
 * الربط ([ArhamIntent.OpenGenerationAdd]). النقر على العقدة ورقةُ [ArhamIntent.OpenTreeNodeSheet].
 */
@Composable
fun ArhamTreeScreen(
    tree: ArhamFamilyTree?,
    strings: ArhamStrings,
    onIntent: (ArhamIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // حالة الكادر (توسيط/تكبير) — معلنة قبل الاستخدام في «توسيطي» أدناه.
        var scale by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SilaDimens.Space16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
        ) {
            TreePill(label = strings.closeTree, onClick = { onIntent(ArhamIntent.ToggleTree) })
            Text(
                text = strings.treeTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TreePill(
                label = strings.treeCenterOnMe,
                // «توسيطي»: إعادة الكادر إلى الوضع الأول — أعلى الشجرة (صف الفرع الذاتي ظاهر).
                onClick = {
                    scale = 1f
                    pan = Offset.Zero
                },
            )
        }

        val isEmpty = tree == null || tree.generations.all { it.nodes.isEmpty() }
        if (isEmpty) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(SilaDimens.Space32),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = strings.treeEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return
        }

        val layout = remember(tree) { computeTreeLayout(tree) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan.x
                        translationY = pan.y
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panDelta, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 2.5f)
                            pan += panDelta
                        }
                    }
                    .size(width = layout.contentWidth, height = layout.contentHeight),
            ) {
                // الوصلات أولاً — تحت البطاقات (ابن←والد: قاع الوالد لقمة الابن).
                val edgeColor = MaterialTheme.colorScheme.outline
                Canvas(modifier = Modifier.matchParentSize()) {
                    val strokeWidth = 2.dp.toPx()
                    tree?.edges?.forEach { edge ->
                        val parent = layout.placements[edge.parentContactId]
                        val child = layout.placements[edge.childContactId]
                        if (parent != null && child != null) {
                            drawLine(
                                color = edgeColor,
                                start = Offset((parent.left + NODE_WIDTH / 2).toPx(), (parent.top + NODE_HEIGHT).toPx()),
                                end = Offset((child.left + NODE_WIDTH / 2).toPx(), child.top.toPx()),
                                strokeWidth = strokeWidth,
                            )
                        }
                    }
                }
                layout.placements.values.forEach { placement ->
                    val node = tree
                        ?.generations
                        ?.flatMap { it.nodes }
                        ?.firstOrNull { it.contactId == placement.contactId }
                    if (node != null) {
                        TreeContactNode(
                            displayName = node.displayName,
                            relationLabel = strings.relationLabel(node.relation),
                            statusColor = SilaColors.forArhamStatus(node.status),
                            onClick = { onIntent(ArhamIntent.OpenTreeNodeSheet(node.contactId)) },
                            modifier = Modifier.absoluteOffset(x = placement.left, y = placement.top).size(NODE_WIDTH, NODE_HEIGHT),
                        )
                    }
                }
                layout.addButtons.forEach { (layer, left, top) ->
                    Surface(
                        shape = RoundedCornerShape(SilaDimens.MinTouchTarget / 2),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .absoluteOffset(x = left, y = top)
                            .size(ADD_SIZE),
                    ) {
                        Text(
                            text = strings.treeAdd,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clickable { onIntent(ArhamIntent.OpenGenerationAdd(layer)) }
                                .fillMaxSize()
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** حبة رأس الشجرة (رجوع/توسيط) — هدف لمس 48 دن موثق. */
@Composable
private fun TreePill(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(SilaDimens.MinTouchTarget / 2),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = SilaDimens.Space12, vertical = 8.dp),
        )
    }
}

/** بطاقة عقدة الشجرة — حلقة الحالة دلالية، والنقر يفتح ورقة العقدة. */
@Composable
private fun TreeContactNode(
    displayName: String,
    relationLabel: String,
    statusColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(2.dp, statusColor),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = SilaDimens.Space8, vertical = SilaDimens.Space4),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = relationLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
