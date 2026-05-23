package com.example.dale

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.example.dale.utils.performKeypadHaptic
import kotlin.math.hypot

private data class PatternNode(val id: Int, val center: Offset)

@Composable
fun PatternLockPad(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticIntensity: Int = 100,
    onPatternDrawn: (String) -> Unit
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val connectedNodes = remember { mutableStateListOf<PatternNode>() }
    var dragPoint by remember { mutableStateOf<Offset?>(null) }
    val context = LocalContext.current

    Box(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(enabled, canvasSize) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { startOffset ->
                        connectedNodes.clear()
                        dragPoint = startOffset

                        val nodes = buildPatternNodes(canvasSize)
                        val hitRadius = hitRadius(canvasSize)
                        addNodeIfHit(
                            connectedNodes,
                            nodes,
                            startOffset,
                            hitRadius,
                            onNodeAdded = {
                                performKeypadHaptic(context, intensityPercent = hapticIntensity)
                            }
                        )
                    },
                    onDragEnd = {
                        dragPoint = null
                        if (connectedNodes.isNotEmpty()) {
                            onPatternDrawn(connectedNodes.joinToString(separator = "") { it.id.toString() })
                        }
                        connectedNodes.clear()
                    },
                    onDragCancel = {
                        dragPoint = null
                        connectedNodes.clear()
                    }
                ) { change, _ ->
                    val nodes = buildPatternNodes(canvasSize)
                    val hitRadius = hitRadius(canvasSize)

                    dragPoint = change.position
                    addNodeIfHit(
                        connectedNodes,
                        nodes,
                        change.position,
                        hitRadius,
                        onNodeAdded = {
                            performKeypadHaptic(context, intensityPercent = hapticIntensity)
                        }
                    )
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nodes = buildPatternNodes(IntSize(size.width.toInt(), size.height.toInt()))
            val nodeRadius = nodeRadius(IntSize(size.width.toInt(), size.height.toInt()))

            // Draw connected lines first so dots stay above lines.
            connectedNodes.zipWithNext().forEach { (from, to) ->
                drawLine(
                    color = Color(0xFF9575CD),
                    start = from.center,
                    end = to.center,
                    strokeWidth = nodeRadius * 0.45f,
                    cap = StrokeCap.Round
                )
            }

            val currentDrag = dragPoint
            val lastConnected = connectedNodes.lastOrNull()
            if (currentDrag != null && lastConnected != null) {
                drawLine(
                    color = Color(0xAA9575CD),
                    start = lastConnected.center,
                    end = currentDrag,
                    strokeWidth = nodeRadius * 0.35f,
                    cap = StrokeCap.Round
                )
            }

            nodes.forEach { node ->
                val isSelected = connectedNodes.any { it.id == node.id }
                drawCircle(
                    color = if (isSelected) Color(0xFF9575CD) else Color(0xFF3A4B5D),
                    radius = nodeRadius,
                    center = node.center
                )
                if (isSelected) {
                    drawCircle(
                        color = Color(0x449575CD),
                        radius = nodeRadius * 1.7f,
                        center = node.center
                    )
                }
            }
        }
    }
}

private fun addNodeIfHit(
    connectedNodes: MutableList<PatternNode>,
    nodes: List<PatternNode>,
    touch: Offset,
    hitRadius: Float,
    onNodeAdded: (PatternNode) -> Unit
) {
    val candidate = nodes.firstOrNull { node ->
        distance(node.center, touch) <= hitRadius
    } ?: return

    if (connectedNodes.none { it.id == candidate.id }) {
        val lastConnected = connectedNodes.lastOrNull()
        if (lastConnected != null) {
            val intermediate = findIntermediateNode(lastConnected, candidate, nodes)
            if (intermediate != null && connectedNodes.none { it.id == intermediate.id }) {
                connectedNodes.add(intermediate)
                onNodeAdded(intermediate)
            }
        }
        connectedNodes.add(candidate)
        onNodeAdded(candidate)
    }
}

private fun findIntermediateNode(
    from: PatternNode,
    to: PatternNode,
    nodes: List<PatternNode>
): PatternNode? {
    val fromRow = (from.id - 1) / 3
    val fromCol = (from.id - 1) % 3
    val toRow = (to.id - 1) / 3
    val toCol = (to.id - 1) % 3
    val rowDelta = toRow - fromRow
    val colDelta = toCol - fromCol

    val isStraight = rowDelta == 0 && kotlin.math.abs(colDelta) == 2 ||
        colDelta == 0 && kotlin.math.abs(rowDelta) == 2
    val isDiagonal = kotlin.math.abs(rowDelta) == 2 && kotlin.math.abs(colDelta) == 2

    if (!isStraight && !isDiagonal) {
        return null
    }

    val midRow = (fromRow + toRow) / 2
    val midCol = (fromCol + toCol) / 2
    val midId = midRow * 3 + midCol + 1
    return nodes.firstOrNull { it.id == midId }
}

private fun buildPatternNodes(size: IntSize): List<PatternNode> {
    if (size.width <= 0 || size.height <= 0) return emptyList()

    val cols = 3
    val rows = 3

    // Spread dots farther apart by using a larger active grid area.
    val horizontalPadding = size.width * 0.12f
    val verticalPadding = size.height * 0.14f
    val usableWidth = (size.width - (horizontalPadding * 2f)).coerceAtLeast(1f)
    val usableHeight = (size.height - (verticalPadding * 2f)).coerceAtLeast(1f)
    val horizontalStep = usableWidth / (cols - 1)
    val verticalStep = usableHeight / (rows - 1)

    val nodes = ArrayList<PatternNode>(9)
    var id = 1
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            nodes.add(
                PatternNode(
                    id = id++,
                    center = Offset(
                        x = horizontalPadding + (horizontalStep * col),
                        y = verticalPadding + (verticalStep * row)
                    )
                )
            )
        }
    }
    return nodes
}

private fun hitRadius(size: IntSize): Float {
    val minSide = minOf(size.width, size.height).toFloat()
    return (minSide / 11f).coerceAtLeast(18f)
}

private fun nodeRadius(size: IntSize): Float {
    val minSide = minOf(size.width, size.height).toFloat()
    return (minSide / 20f).coerceIn(10f, 22f)
}

private fun distance(a: Offset, b: Offset): Float {
    return hypot(a.x - b.x, a.y - b.y)
}






