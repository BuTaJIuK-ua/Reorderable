package sh.calvin.reorderable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object ReorderableLazyListDefaults {
    val ScrollThreshold = 48.dp
    val ScrollSpeed = 0.05f
    val IgnoreContentPaddingForScroll = false
}

/**
 * Creates a [ReorderableLazyListState] that is remembered across compositions.
 *
 * Changes to the provided initial values will **not** result in the state being recreated or
 * changed in any way if it has already been created.
 * @param lazyListState The return value of [rememberLazyListState](androidx.compose.foundation.lazy.LazyListStateKt.rememberLazyListState)
 * @param scrollThreshold The distance in dp from the top or bottom of the list that will trigger scrolling
 * @param scrollSpeed The fraction of the Column's size that will be scrolled when dragging an item within the scrollThreshold
 * @param ignoreContentPaddingForScroll Whether to ignore content padding for scrollThreshold
 * @param onMove The function that is called when an item is moved
 */
@Composable
fun rememberReorderableLazyColumnState(
    lazyListState: LazyListState,
    scrollThreshold: Dp = ReorderableLazyListDefaults.ScrollThreshold,
    scrollSpeed: Float = ReorderableLazyListDefaults.ScrollSpeed,
    ignoreContentPaddingForScroll: Boolean = ReorderableLazyListDefaults.IgnoreContentPaddingForScroll,
    onMove: (from: LazyListItemInfo, to: LazyListItemInfo) -> Unit
) = rememberReorderableLazyListState(
    lazyListState,
    scrollThreshold,
    scrollSpeed,
    Orientation.Vertical,
    ignoreContentPaddingForScroll,
    onMove,
)

/**
 * Creates a [ReorderableLazyListState] that is remembered across compositions.
 *
 * Changes to the provided initial values will **not** result in the state being recreated or
 * changed in any way if it has already been created.
 * @param lazyListState The return value of [rememberLazyListState](androidx.compose.foundation.lazy.LazyListStateKt.rememberLazyListState)
 * @param scrollThreshold The distance in dp from the left or right of the list that will trigger scrolling
 * @param scrollSpeed The fraction of the Row's size that will be scrolled when dragging an item within the scrollThreshold
 * @param ignoreContentPaddingForScroll Whether to ignore content padding for scrollThreshold
 * @param onMove The function that is called when an item is moved
 */
@Composable
fun rememberReorderableLazyRowState(
    lazyListState: LazyListState,
    scrollThreshold: Dp = ReorderableLazyListDefaults.ScrollThreshold,
    scrollSpeed: Float = ReorderableLazyListDefaults.ScrollSpeed,
    ignoreContentPaddingForScroll: Boolean = ReorderableLazyListDefaults.IgnoreContentPaddingForScroll,
    onMove: (from: LazyListItemInfo, to: LazyListItemInfo) -> Unit
) = rememberReorderableLazyListState(
    lazyListState,
    scrollThreshold,
    scrollSpeed,
    Orientation.Horizontal,
    ignoreContentPaddingForScroll,
    onMove,
)

@Composable
internal fun rememberReorderableLazyListState(
    lazyListState: LazyListState,
    scrollThreshold: Dp,
    scrollSpeed: Float,
    orientation: Orientation,
    ignoreContentPaddingForScroll: Boolean,
    onMove: (from: LazyListItemInfo, to: LazyListItemInfo) -> Unit
): ReorderableLazyListState {
    val density = LocalDensity.current
    val scrollThresholdPx = with(density) { scrollThreshold.toPx() }

    val scope = rememberCoroutineScope()
    val state = remember(lazyListState) {
        ReorderableLazyListState(
            state = lazyListState,
            scope = scope,
            onMove = onMove,
            ignoreContentPaddingForScroll = ignoreContentPaddingForScroll,
            scrollThreshold = scrollThresholdPx,
            scrollSpeed = scrollSpeed,
            orientation = orientation,
        )
    }
    return state
}

private fun LazyListLayoutInfo.getContentOffset(
    orientation: Orientation, ignoreContentPadding: Boolean
): Pair<Int, Int> {
    val contentStartOffset = 0
    val contentPadding = if (!ignoreContentPadding) {
        beforeContentPadding - afterContentPadding
    } else {
        0
    }
    val contentEndOffset = when (orientation) {
        Orientation.Vertical -> viewportSize.height
        Orientation.Horizontal -> viewportSize.width
    } - contentPadding

    return contentStartOffset to contentEndOffset
}

private class ProgrammaticScroller(
    private val state: LazyListState,
    private val scope: CoroutineScope,
    private val orientation: Orientation,
    private val ignoreContentPaddingForScroll: Boolean,
    private val scrollSpeed: Float,
    private val reorderableKeys: HashSet<Any?>,
    private val swapItems: (
        draggingItem: LazyListItemInfo, targetItem: LazyListItemInfo
    ) -> Unit,
) {
    enum class ProgrammaticScrollDirection {
        BACKWARD, FORWARD
    }

    private data class ScrollJobInfo(
        val direction: ProgrammaticScrollDirection,
        val speedMultiplier: Float,
    )

    private var programmaticScrollJobInfo: ScrollJobInfo? = null
    private var programmaticScrollJob: Job? = null
    val isScrolling: Boolean
        get() = programmaticScrollJobInfo != null

    fun start(
        draggingItemProvider: () -> LazyListItemInfo?,
        direction: ProgrammaticScrollDirection,
        speedMultiplier: Float = 1f,
    ) {
        val scrollJobInfo = ScrollJobInfo(direction, speedMultiplier)

        if (programmaticScrollJobInfo == scrollJobInfo) return

        val viewportSize = when (orientation) {
            Orientation.Vertical -> state.layoutInfo.viewportSize.height
            Orientation.Horizontal -> state.layoutInfo.viewportSize.width
        }
        val multipliedScrollOffset = viewportSize * scrollSpeed * speedMultiplier

        programmaticScrollJob?.cancel()
        programmaticScrollJobInfo = null

        if (!canScroll(direction)) return

        programmaticScrollJobInfo = scrollJobInfo
        programmaticScrollJob = scope.launch {
            while (true) {
                try {
                    if (!canScroll(direction)) break

                    val duration = 100L
                    val diff = when (direction) {
                        ProgrammaticScrollDirection.BACKWARD -> -multipliedScrollOffset
                        ProgrammaticScrollDirection.FORWARD -> multipliedScrollOffset
                    }
                    launch {
                        state.animateScrollBy(
                            diff, tween(durationMillis = duration.toInt(), easing = LinearEasing)
                        )
                    }

                    launch {
                        // keep dragging item in visible area to prevent it from disappearing
                        swapDraggingItemToEndIfNecessary(draggingItemProvider, direction)
                    }

                    delay(duration)
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun canScroll(direction: ProgrammaticScrollDirection): Boolean {
        return when (direction) {
            ProgrammaticScrollDirection.BACKWARD -> state.canScrollBackward
            ProgrammaticScrollDirection.FORWARD -> state.canScrollForward
        }
    }

    /**
     * Swap the dragging item with first item in the visible area in the direction of scrolling.
     */
    private fun swapDraggingItemToEndIfNecessary(
        draggingItemProvider: () -> LazyListItemInfo?,
        direction: ProgrammaticScrollDirection,
    ) {
        val draggingItem = draggingItemProvider() ?: return
        val itemsInContentArea = state.layoutInfo.getItemsInContentArea(
            orientation, ignoreContentPaddingForScroll
        )
        val draggingItemIsAtTheEnd = when (direction) {
            ProgrammaticScrollDirection.BACKWARD -> itemsInContentArea.firstOrNull()?.index?.let { draggingItem.index < it }
            ProgrammaticScrollDirection.FORWARD -> itemsInContentArea.lastOrNull()?.index?.let { draggingItem.index > it }
        } ?: false

        if (draggingItemIsAtTheEnd) return

        val isReorderable = { item: LazyListItemInfo -> item.key in reorderableKeys }

        val targetItem = itemsInContentArea.let {
            when (direction) {
                ProgrammaticScrollDirection.BACKWARD -> it.find(isReorderable)
                ProgrammaticScrollDirection.FORWARD -> it.findLast(isReorderable)
            }
        }
        if (targetItem != null && targetItem.index != draggingItem.index) {
            swapItems(draggingItem, targetItem)
        }
    }

    fun stop() {
        programmaticScrollJob?.cancel()
        programmaticScrollJobInfo = null
    }

    private fun LazyListLayoutInfo.getItemsInContentArea(
        orientation: Orientation, ignoreContentPadding: Boolean
    ): List<LazyListItemInfo> {
        val (contentStartOffset, contentEndOffset) = getContentOffset(
            orientation, ignoreContentPadding
        )
        return visibleItemsInfo.filter { item ->
            item.offset >= contentStartOffset && item.offset + item.size <= contentEndOffset
        }
    }
}

// base on https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/foundation/foundation/integration-tests/foundation-demos/src/main/java/androidx/compose/foundation/demos/LazyColumnDragAndDropDemo.kt
class ReorderableLazyListState internal constructor(
    private val state: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: LazyListItemInfo, to: LazyListItemInfo) -> Unit,
    internal val orientation: Orientation,

    /**
     * Whether to ignore content padding for scrollThreshold.
     */
    private val ignoreContentPaddingForScroll: Boolean,

    /**
     * The threshold in pixels for scrolling the list when dragging an item.
     * If the dragged item is within this threshold of the top or bottom of the list, the list will scroll.
     * Must be greater than 0.
     */
    private val scrollThreshold: Float,
    scrollSpeed: Float,
) {
    internal var draggingItemKey by mutableStateOf<Any?>(null)
        private set
    private val draggingItemIndex: Int?
        get() = draggingItemLayoutInfo?.index

    private var draggingItemDraggedDelta by mutableFloatStateOf(0f)
    private var draggingItemInitialOffset by mutableIntStateOf(0)

    // visibleItemsInfo doesn't update immediately after onMove, draggingItemLayoutInfo.item may be outdated for a short time.
    // not a clean solution, but it works.
    private var draggingItemTargetIndex: Int? = null
    private var predictedDraggingItemOffset: Int? = null
    private val draggingItemLayoutInfo: LazyListItemInfo?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingItemKey }
    internal val draggingItemOffset: Float
        get() = draggingItemLayoutInfo?.let { item ->
            val offset = if (item.index == draggingItemTargetIndex) {
                predictedDraggingItemOffset = null
                item.offset
            } else {
                predictedDraggingItemOffset ?: item.offset
            }
            draggingItemInitialOffset + draggingItemDraggedDelta - offset
        } ?: 0f

    internal val reorderableKeys = HashSet<Any?>()

    private val programmaticScroller = ProgrammaticScroller(
        state,
        scope,
        orientation,
        ignoreContentPaddingForScroll,
        scrollSpeed,
        reorderableKeys,
        this::swapItems
    )

    internal var previousKeyOfDraggedItem by mutableStateOf<Any?>(null)
        private set
    internal var previousItemOffset = Animatable(0f)
        private set

    internal fun onDragStart(key: Any) {
        state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key == key
        }?.also {
            draggingItemKey = key
            draggingItemInitialOffset = it.offset
        }
    }

    internal fun onDragStop() {
        if (draggingItemIndex != null) {
            previousKeyOfDraggedItem = draggingItemKey
            val startOffset = draggingItemOffset
            scope.launch {
                previousItemOffset.snapTo(startOffset)
                previousItemOffset.animateTo(
                    0f, spring(
                        stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f
                    )
                )
                previousKeyOfDraggedItem = null
            }
        }
        draggingItemDraggedDelta = 0f
        draggingItemKey = null
        draggingItemInitialOffset = 0
        programmaticScroller.stop()
        draggingItemTargetIndex = null
        predictedDraggingItemOffset = null
    }

    internal fun onDrag(offset: Float) {
        draggingItemDraggedDelta += offset

        val draggingItem = draggingItemLayoutInfo ?: return
        val startOffset = draggingItem.offset + draggingItemOffset
        val endOffset = startOffset + draggingItem.size
        val (contentStartOffset, contentEndOffset) = state.layoutInfo.getContentOffset(
            orientation, ignoreContentPaddingForScroll
        )

        if (!programmaticScroller.isScrolling) {
            // find a target item to swap with
            val targetItem = state.layoutInfo.visibleItemsInfo.find { item ->
                item.offsetMiddle in startOffset..endOffset && draggingItem.index != item.index && item.key in reorderableKeys
            }
            if (targetItem != null) {
                swapItems(draggingItem, targetItem)
            }
        }

        // check if the dragging item is in the scroll threshold
        val distanceFromStart = startOffset - contentStartOffset
        val distanceFromEnd = contentEndOffset - endOffset

        if (distanceFromStart < scrollThreshold) {
            programmaticScroller.start(
                { draggingItemLayoutInfo },
                ProgrammaticScroller.ProgrammaticScrollDirection.BACKWARD,
                getScrollSpeedMultiplier(distanceFromStart)
            )
        } else if (distanceFromEnd < scrollThreshold) {
            programmaticScroller.start(
                { draggingItemLayoutInfo },
                ProgrammaticScroller.ProgrammaticScrollDirection.FORWARD,
                getScrollSpeedMultiplier(distanceFromEnd)
            )
        } else {
            programmaticScroller.stop()
        }
    }

    private fun swapItems(
        draggingItem: LazyListItemInfo, targetItem: LazyListItemInfo
    ) {
        if (draggingItem.index == targetItem.index) return

        predictedDraggingItemOffset = if (targetItem.index > draggingItem.index) {
            targetItem.size + targetItem.offset - draggingItem.size
        } else {
            targetItem.offset
        }
        draggingItemTargetIndex = targetItem.index

        val scrollToIndex = if (targetItem.index == state.firstVisibleItemIndex) {
            draggingItem.index
        } else if (draggingItem.index == state.firstVisibleItemIndex) {
            targetItem.index
        } else {
            null
        }
        if (scrollToIndex != null) {
            scope.launch {
                // this is needed to neutralize automatic keeping the first item first.
                state.scrollToItem(scrollToIndex, state.firstVisibleItemScrollOffset)
                onMove(draggingItem, targetItem)
            }
        } else {
            onMove(draggingItem, targetItem)
        }
    }

    private fun getScrollSpeedMultiplier(distance: Float): Float {
        // map distance in scrollThreshold..-scrollThreshold to 1..10
        return (1 - ((distance + scrollThreshold) / (scrollThreshold * 2)).coerceIn(0f, 1f)) * 10
    }

    private val LazyListItemInfo.offsetMiddle: Float
        get() = offset + size / 2f
}

interface ReorderableItemScope {
    /**
     * Make the UI element the draggable handle for the reorderable item.
     *
     * @param onDragStarted The function that is called when the item starts being dragged
     * @param onDragStopped The function that is called when the item stops being dragged
     */
    fun Modifier.draggableHandle(
        onDragStarted: suspend CoroutineScope.(startedPosition: Offset) -> Unit = {},
        onDragStopped: suspend CoroutineScope.(velocity: Float) -> Unit = {},
        interactionSource: MutableInteractionSource? = null,
    ): Modifier
}

internal class ReorderableItemScopeImpl(
    private val reorderableLazyListState: ReorderableLazyListState,
    private val key: Any,
    private val orientation: Orientation,
) : ReorderableItemScope {
    override fun Modifier.draggableHandle(
        onDragStarted: suspend CoroutineScope.(startedPosition: Offset) -> Unit,
        onDragStopped: suspend CoroutineScope.(velocity: Float) -> Unit,
        interactionSource: MutableInteractionSource?,
    ) = composed {
        draggable(
            state = rememberDraggableState { reorderableLazyListState.onDrag(offset = it) },
            orientation = orientation,
            interactionSource = interactionSource,
            onDragStarted = {
                reorderableLazyListState.onDragStart(key)
                onDragStarted(it)
            },
            onDragStopped = {
                reorderableLazyListState.onDragStop()
                onDragStopped(it)
            },
        )
    }
}

/**
 * A composable that allows items in a LazyColumn to be reordered by dragging.
 *
 * @param state The return value of [rememberReorderableLazyColumnState] or [rememberReorderableLazyRowState]
 * @param key The key of the item, must be the same as the key passed to [LazyColumn.item](androidx.compose.foundation.lazy.LazyDsl.item), [LazyRow.item](androidx.compose.foundation.lazy.LazyDsl.item) or similar functions in [LazyListScope](androidx.compose.foundation.lazy.LazyListScope)
 */
@ExperimentalFoundationApi
@Composable
fun LazyItemScope.ReorderableItem(
    reorderableLazyListState: ReorderableLazyListState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable ReorderableItemScope.(isDragging: Boolean) -> Unit
) {
    reorderableLazyListState.reorderableKeys.add(key)

    val orientation = reorderableLazyListState.orientation
    val dragging = key == reorderableLazyListState.draggingItemKey
    val draggingModifier = if (dragging) {
        Modifier
            .zIndex(1f)
            .then(when (orientation) {
                Orientation.Vertical -> Modifier.graphicsLayer {
                    translationY = reorderableLazyListState.draggingItemOffset
                }

                Orientation.Horizontal -> Modifier.graphicsLayer {
                    translationX = reorderableLazyListState.draggingItemOffset
                }
            })
    } else if (key == reorderableLazyListState.previousKeyOfDraggedItem) {
        Modifier
            .zIndex(1f)
            .then(when (orientation) {
                Orientation.Vertical -> Modifier.graphicsLayer {
                    translationY = reorderableLazyListState.previousItemOffset.value
                }

                Orientation.Horizontal -> Modifier.graphicsLayer {
                    translationX = reorderableLazyListState.previousItemOffset.value
                }
            })
    } else {
        Modifier.animateItemPlacement()
    }
    Column(modifier = modifier.then(draggingModifier)) {
        ReorderableItemScopeImpl(
            reorderableLazyListState, key, orientation
        ).content(dragging)
    }
}