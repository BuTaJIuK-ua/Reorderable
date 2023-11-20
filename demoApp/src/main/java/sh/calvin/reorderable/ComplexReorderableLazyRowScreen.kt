package sh.calvin.reorderable

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComplexReorderableLazyRowScreen() {
    val view = LocalView.current

    var list by remember { mutableStateOf(items) }
    val lazyListState = rememberLazyListState()
    val reorderableLazyRowState = rememberReorderableLazyRowState(lazyListState) { from, to ->
        list = list.toMutableList().apply {
            // can't use .index because there are other items in the list (headers, footers, etc)
            val fromIndex = indexOfFirst { it.id == from.key }
            val toIndex = indexOfFirst { it.id == to.key }

            add(toIndex, removeAt(fromIndex))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
    }

    LazyRow(
        modifier = Modifier.fillMaxSize(),
        state = lazyListState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Header",
                Modifier
                    .height(144.dp)
                    .padding(8.dp),
            )
        }
        list.chunked(5).forEachIndexed { index, subList ->
            stickyHeader {
                Text(
                    "$index",
                    Modifier
                        .animateItemPlacement()
                        .height(144.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(8.dp),
                )
            }
            items(subList, key = { it.id }) {
                ReorderableItem(reorderableLazyRowState, it.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 1.dp)

                    Card(
                        modifier = Modifier
                            .width(it.size.dp)
                            .height(128.dp)
                            .padding(vertical = 8.dp),
                        shadowElevation = elevation,
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            IconButton(
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                            view.performHapticFeedback(HapticFeedbackConstants.DRAG_START)
                                        }
                                    },
                                    onDragStopped = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                                        }
                                    },
                                ),
                                onClick = {},
                            ) {
                                Icon(Icons.Rounded.Reorder, contentDescription = "Reorder")
                            }
                            Text(it.text, Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
        item {
            Text("Footer", Modifier.padding(8.dp))
        }
    }
}