package com.xnotes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

private val DefaultFavoriteZoomValues = listOf(2f, 4f, 6f)
private val ZoomBarShape = RoundedCornerShape(12.dp)
private val ZoomBarElevation = 6.dp
private val ZoomBarHorizontalPadding = 6.dp
private val ZoomBarVerticalPadding = 4.dp
private val ZoomTextHorizontalPadding = 8.dp
private val ZoomTextVerticalPadding = 4.dp
private val ZoomNumberWidth = 40.dp
private val ZoomItemHorizontalPadding = 4.dp
private val ZoomIconSpacing = 2.dp
private val ZoomIconButtonSize = 40.dp
private val ZoomIconSize = 20.dp
private const val DefaultZoomMatchTolerance = 0.01f
private const val DefaultAutoHideDelayMillis = 2_500L

private fun formatZoomPercent(scale: Float): String =
    "${(scale * 100f).roundToInt()}%"

private fun formatZoomNumber(scale: Float): String =
    (scale * 100f).roundToInt().toString()

private fun Float.matchesZoom(other: Float, tolerance: Float): Boolean =
    kotlin.math.abs(this - other) <= tolerance

data class ZoomBarState(
    val zoomValue: Float,
    val isZoomLocked: Boolean,
    val favoriteZoomValues: List<Float>,
    val visible: Boolean = true,
    val position: IntOffset = IntOffset.Zero
)

data class ZoomBarConfig(
    val isDraggable: Boolean = true,
    val autoHideWhenLocked: Boolean = true,
    val autoHideDelayMillis: Long = DefaultAutoHideDelayMillis,
    val zoomMatchTolerance: Float = DefaultZoomMatchTolerance
)

sealed interface ZoomBarEvent {
    data class ZoomSelected(val zoom: Float) : ZoomBarEvent
    data class ZoomLockChanged(val locked: Boolean) : ZoomBarEvent
    data class FavoriteToggled(val zoom: Float) : ZoomBarEvent
    data class FavoriteRemoved(val zoom: Float) : ZoomBarEvent
    data class PositionChanged(val position: IntOffset) : ZoomBarEvent
    data class VisibilityChanged(val visible: Boolean) : ZoomBarEvent
}

@Composable
fun ZoomBar(
    state: ZoomBarState,
    onEvent: (ZoomBarEvent) -> Unit,
    modifier: Modifier = Modifier,
    config: ZoomBarConfig = ZoomBarConfig()
) {
    val isCurrentZoomFavorite = state.favoriteZoomValues.any {
        it.matchesZoom(state.zoomValue, config.zoomMatchTolerance)
    }
    val latestPosition by rememberUpdatedState(state.position)

    LaunchedEffect(
        state.visible,
        state.isZoomLocked,
        config.autoHideWhenLocked,
        config.autoHideDelayMillis
    ) {
        if (state.visible && state.isZoomLocked && config.autoHideWhenLocked) {
            delay(config.autoHideDelayMillis)
            onEvent(ZoomBarEvent.VisibilityChanged(false))
        }
    }

    BoxWithConstraints(modifier = modifier) {
        AnimatedVisibility(
            visible = state.visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier.offset { state.position }
            ) {
                ZoomBarCard(
                    zoomValue = state.zoomValue,
                    isZoomLocked = state.isZoomLocked,
                    isCurrentZoomFavorite = isCurrentZoomFavorite,
                    isDraggable = config.isDraggable,
                    favoriteZoomValues = state.favoriteZoomValues,
                    onZoomLockToggle = { locked ->
                        onEvent(ZoomBarEvent.ZoomLockChanged(locked))
                    },
                    onZoomSelected = { zoom ->
                        onEvent(ZoomBarEvent.ZoomSelected(zoom))
                    },
                    onFavoriteToggle = {
                        onEvent(ZoomBarEvent.FavoriteToggled(state.zoomValue))
                    },
                    onFavoriteRemove = { zoom ->
                        onEvent(ZoomBarEvent.FavoriteRemoved(zoom))
                    },
                    onDrag = { dragAmount ->
                        onEvent(
                            ZoomBarEvent.PositionChanged(
                                IntOffset(
                                    x = latestPosition.x + dragAmount.x.roundToInt(),
                                    y = latestPosition.y + dragAmount.y.roundToInt()
                                )
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun ZoomBar(
    zoomValue: Float,
    isZoomLocked: Boolean,
    favoriteZoomValues: List<Float>,
    visible: Boolean,
    onZoomSelected: (Float) -> Unit,
    onZoomLockToggle: (Boolean) -> Unit,
    onFavoriteToggle: (Float) -> Unit,
    onFavoriteRemove: (Float) -> Unit,
    modifier: Modifier = Modifier,
    position: IntOffset = IntOffset.Zero,
    isDraggable: Boolean = true,
    onPositionChange: (IntOffset) -> Unit = {},
    onVisibilityChange: (Boolean) -> Unit = {},
    autoHideWhenLocked: Boolean = true,
    autoHideDelayMillis: Long = DefaultAutoHideDelayMillis,
    zoomMatchTolerance: Float = DefaultZoomMatchTolerance
) {
    ZoomBar(
        state = ZoomBarState(
            zoomValue = zoomValue,
            isZoomLocked = isZoomLocked,
            favoriteZoomValues = favoriteZoomValues,
            visible = visible,
            position = position
        ),
        config = ZoomBarConfig(
            isDraggable = isDraggable,
            autoHideWhenLocked = autoHideWhenLocked,
            autoHideDelayMillis = autoHideDelayMillis,
            zoomMatchTolerance = zoomMatchTolerance
        ),
        onEvent = { event ->
            when (event) {
                is ZoomBarEvent.FavoriteRemoved -> onFavoriteRemove(event.zoom)
                is ZoomBarEvent.FavoriteToggled -> onFavoriteToggle(event.zoom)
                is ZoomBarEvent.PositionChanged -> onPositionChange(event.position)
                is ZoomBarEvent.VisibilityChanged -> onVisibilityChange(event.visible)
                is ZoomBarEvent.ZoomLockChanged -> onZoomLockToggle(event.locked)
                is ZoomBarEvent.ZoomSelected -> onZoomSelected(event.zoom)
            }
        },
        modifier = modifier
    )
}

@Composable
fun rememberZoomBarState(
    zoomValue: Float,
    isZoomLocked: Boolean,
    favoriteZoomValues: List<Float> = DefaultFavoriteZoomValues,
    visible: Boolean = true,
    position: IntOffset = IntOffset.Zero
): ZoomBarState = remember(
    zoomValue,
    isZoomLocked,
    favoriteZoomValues,
    visible,
    position
) {
    ZoomBarState(
        zoomValue = zoomValue,
        isZoomLocked = isZoomLocked,
        favoriteZoomValues = favoriteZoomValues,
        visible = visible,
        position = position
    )
}

@Composable
fun ZoomIndicator(
    zoomValue: Float,
    isZoomLocked: Boolean,
    onZoomLockToggle: (Boolean) -> Unit,
    onFavoriteZoomSelected: ((Float) -> Unit)? = null,
    favoriteZoomValues: List<Float> = DefaultFavoriteZoomValues,
    modifier: Modifier = Modifier
) {
    var position by remember { mutableStateOf(IntOffset.Zero) }
    var favorites by remember(favoriteZoomValues) { mutableStateOf(favoriteZoomValues) }

    ZoomBar(
        state = rememberZoomBarState(
            zoomValue = zoomValue,
            isZoomLocked = isZoomLocked,
            favoriteZoomValues = favorites,
            position = position
        ),
        onEvent = { event ->
            when (event) {
                is ZoomBarEvent.FavoriteRemoved -> {
                    favorites = favorites.filterNot { it.matchesZoom(event.zoom, DefaultZoomMatchTolerance) }
                }
                is ZoomBarEvent.FavoriteToggled -> {
                    favorites = if (favorites.any { it.matchesZoom(event.zoom, DefaultZoomMatchTolerance) }) {
                        favorites.filterNot { it.matchesZoom(event.zoom, DefaultZoomMatchTolerance) }
                    } else {
                        (favorites + event.zoom).sorted()
                    }
                }
                is ZoomBarEvent.PositionChanged -> {
                    position = event.position
                }
                is ZoomBarEvent.VisibilityChanged -> Unit
                is ZoomBarEvent.ZoomLockChanged -> {
                    onZoomLockToggle(event.locked)
                }
                is ZoomBarEvent.ZoomSelected -> {
                    onFavoriteZoomSelected?.invoke(event.zoom)
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun ZoomBarCard(
    zoomValue: Float,
    isZoomLocked: Boolean,
    isCurrentZoomFavorite: Boolean,
    isDraggable: Boolean,
    favoriteZoomValues: List<Float>,
    onZoomLockToggle: (Boolean) -> Unit,
    onZoomSelected: (Float) -> Unit,
    onFavoriteToggle: () -> Unit,
    onFavoriteRemove: (Float) -> Unit,
    onDrag: (dragAmount: Offset) -> Unit
) {
    Card(
        modifier = Modifier.then(
            if (isDraggable) {
                Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    }
                }
            } else {
                Modifier
            }
        ),
        shape = ZoomBarShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = ZoomBarElevation)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = ZoomBarHorizontalPadding,
                vertical = ZoomBarVerticalPadding
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ZoomLockButton(
                zoomValue = zoomValue,
                isZoomLocked = isZoomLocked,
                onZoomLockToggle = onZoomLockToggle
            )

            Spacer(modifier = Modifier.width(ZoomIconSpacing))

            ZoomValueText(
                zoomValue = zoomValue,
                favoriteZoomValues = favoriteZoomValues,
                onZoomSelected = onZoomSelected,
                onFavoriteRemove = onFavoriteRemove
            )

            Spacer(modifier = Modifier.width(ZoomIconSpacing))

            ZoomFavoriteButton(
                zoomValue = zoomValue,
                isFavorite = isCurrentZoomFavorite,
                onFavoriteToggle = onFavoriteToggle
            )
        }
    }
}

@Composable
private fun ZoomValueText(
    zoomValue: Float,
    favoriteZoomValues: List<Float>,
    onZoomSelected: (Float) -> Unit,
    onFavoriteRemove: (Float) -> Unit
) {
    var isFavoriteMenuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .pointerInput(Unit) {
                detectTapGestures { isFavoriteMenuExpanded = true }
            }
            .semantics {
                contentDescription = "Zoom ${formatZoomPercent(zoomValue)}"
            }
            .padding(
                horizontal = ZoomTextHorizontalPadding,
                vertical = ZoomTextVerticalPadding
            )
    ) {
        Row {
            Box(
                modifier = Modifier.width(ZoomNumberWidth),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = formatZoomNumber(zoomValue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        ZoomFavoritesMenu(
            expanded = isFavoriteMenuExpanded,
            favoriteZoomValues = favoriteZoomValues,
            onDismiss = { isFavoriteMenuExpanded = false },
            onFavoriteZoomSelected = { zoom ->
                onZoomSelected(zoom)
                isFavoriteMenuExpanded = false
            },
            onFavoriteRemove = onFavoriteRemove
        )
    }
}

@Composable
private fun ZoomLockButton(
    zoomValue: Float,
    isZoomLocked: Boolean,
    onZoomLockToggle: (Boolean) -> Unit
) {
    IconButton(
        onClick = { onZoomLockToggle(!isZoomLocked) },
        modifier = Modifier.size(ZoomIconButtonSize)
    ) {
        Icon(
            modifier = Modifier.size(ZoomIconSize),
            imageVector = if (isZoomLocked) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = if (isZoomLocked) {
                "Unlock zoom at ${formatZoomPercent(zoomValue)}"
            } else {
                "Lock zoom at ${formatZoomPercent(zoomValue)}"
            },
            tint = if (isZoomLocked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun ZoomFavoriteButton(
    zoomValue: Float,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit
) {
    IconButton(
        onClick = onFavoriteToggle,
        modifier = Modifier.size(ZoomIconButtonSize)
    ) {
        Icon(
            modifier = Modifier.size(ZoomIconSize),
            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = if (isFavorite) {
                "Remove ${formatZoomPercent(zoomValue)} from favorite zooms"
            } else {
                "Add ${formatZoomPercent(zoomValue)} to favorite zooms"
            },
            tint = if (isFavorite) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun ZoomFavoritesMenu(
    expanded: Boolean,
    favoriteZoomValues: List<Float>,
    onDismiss: () -> Unit,
    onFavoriteZoomSelected: (Float) -> Unit,
    onFavoriteRemove: (Float) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        favoriteZoomValues.forEach { zoom ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatZoomPercent(zoom),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onFavoriteRemove(zoom) },
                            modifier = Modifier.size(ZoomIconButtonSize)
                        ) {
                            Icon(
                                modifier = Modifier.size(ZoomIconSize),
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove ${formatZoomPercent(zoom)} from favorite zooms"
                            )
                        }
                    }
                },
                onClick = { onFavoriteZoomSelected(zoom) },
                modifier = Modifier.padding(horizontal = ZoomItemHorizontalPadding)
            )
        }
    }
}

private val VerticalZoomBarShape = RoundedCornerShape(999.dp)
private val DefaultVerticalZoomBarWidth = 10.dp
private val DefaultVerticalZoomBarThumbHeight = 56.dp
private val DefaultVerticalZoomBarEdgePadding = 2.dp
private val DefaultVerticalZoomBarTouchWidth = 28.dp
private val DefaultVerticalZoomButtonSize = 32.dp
private val DefaultVerticalZoomButtonSpacing = 8.dp

private fun normalizeZoomValue(
    value: Float,
    minZoom: Float,
    maxZoom: Float
): Float {
    val range = maxZoom - minZoom
    if (range <= 0f) return 0f
    return ((value - minZoom) / range).coerceIn(0f, 1f)
}

private fun lerpZoomValue(
    progress: Float,
    minZoom: Float,
    maxZoom: Float
): Float = minZoom + (maxZoom - minZoom) * progress.coerceIn(0f, 1f)

private fun applyZoomStep(
    zoomValue: Float,
    stepPercent: Float,
    minZoom: Float,
    maxZoom: Float,
    increase: Boolean
): Float {
    val stepFactor = (stepPercent / 100f).coerceAtLeast(0f)
    val nextZoom = if (increase) {
        zoomValue * (1f + stepFactor)
    } else {
        zoomValue * (1f - stepFactor)
    }
    return nextZoom.coerceIn(minZoom, maxZoom)
}

@Composable
fun VerticalZoomBar(
    zoomValue: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = DefaultVerticalZoomBarWidth,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    stepPercent: Float = 10f
) {
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { DefaultVerticalZoomBarThumbHeight.toPx() }
    val edgePaddingPx = with(density) { DefaultVerticalZoomBarEdgePadding.toPx() }
    val touchWidth = if (width > DefaultVerticalZoomBarTouchWidth) {
        width
    } else {
        DefaultVerticalZoomBarTouchWidth
    }
    val canInteract = enabled && maxZoom > minZoom
    val normalizedZoom = normalizeZoomValue(zoomValue, minZoom, maxZoom)
    val latestZoomValue by rememberUpdatedState(zoomValue)
    val latestMinZoom by rememberUpdatedState(minZoom)
    val latestMaxZoom by rememberUpdatedState(maxZoom)
    val latestStepPercent by rememberUpdatedState(stepPercent)
    var dragThumbOffsetPx by remember { mutableStateOf<Float?>(null) }
    var dragTouchOffsetPx by remember { mutableStateOf(thumbHeightPx / 2f) }

    Column(
        modifier = modifier
            .width(touchWidth)
            .semantics {
                contentDescription = "Vertical zoom bar ${formatZoomPercent(zoomValue)}"
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = {
                if (canInteract) {
                    onZoomChange(
                        applyZoomStep(
                            zoomValue = latestZoomValue,
                            stepPercent = latestStepPercent,
                            minZoom = latestMinZoom,
                            maxZoom = latestMaxZoom,
                            increase = true
                        )
                    )
                }
            },
            enabled = canInteract,
            modifier = Modifier.size(DefaultVerticalZoomButtonSize)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Increase zoom",
                tint = if (canInteract) color else color.copy(alpha = 0.45f)
            )
        }

        Spacer(modifier = Modifier.size(DefaultVerticalZoomButtonSpacing))

        BoxWithConstraints(
            modifier = Modifier
                .width(touchWidth)
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter
        ) {
            val containerHeightPx = with(density) { maxHeight.toPx() }
            val trackTravelPx = max(containerHeightPx - thumbHeightPx - edgePaddingPx * 2f, 0f)
            val settledThumbOffsetPx = (1f - normalizedZoom) * trackTravelPx
            val thumbOffsetPx = dragThumbOffsetPx ?: settledThumbOffsetPx

            fun updateZoomFromThumbOffset(offsetPx: Float) {
                if (!canInteract) return
                val progress = if (trackTravelPx == 0f) {
                    normalizedZoom
                } else {
                    1f - (offsetPx / trackTravelPx).coerceIn(0f, 1f)
                }
                onZoomChange(lerpZoomValue(progress, latestMinZoom, latestMaxZoom))
            }

            Box(
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight()
                    .background(
                        color = color.copy(alpha = if (canInteract) 0.22f else 0.12f),
                        shape = VerticalZoomBarShape
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (canInteract) {
                            Modifier
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        dragThumbOffsetPx = null
                                        val targetOffset = (offset.y - thumbHeightPx / 2f - edgePaddingPx)
                                            .coerceIn(0f, trackTravelPx)
                                        updateZoomFromThumbOffset(targetOffset)
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { dragStartOffset ->
                                            val currentThumbOffset = dragThumbOffsetPx ?: (
                                                1f - normalizeZoomValue(
                                                    latestZoomValue,
                                                    latestMinZoom,
                                                    latestMaxZoom
                                                )
                                            ) * trackTravelPx
                                            val currentThumbTop = edgePaddingPx + currentThumbOffset
                                            val currentThumbBottom = currentThumbTop + thumbHeightPx
                                            dragThumbOffsetPx = currentThumbOffset
                                            dragTouchOffsetPx = if (
                                                dragStartOffset.y in currentThumbTop..currentThumbBottom
                                            ) {
                                                dragStartOffset.y - currentThumbTop
                                            } else {
                                                thumbHeightPx / 2f
                                            }.coerceIn(0f, thumbHeightPx)
                                        },
                                        onDragEnd = {
                                            dragThumbOffsetPx = null
                                        },
                                        onDragCancel = {
                                            dragThumbOffsetPx = null
                                        }
                                    ) { change, _ ->
                                        change.consume()
                                        val targetOffset = (
                                            change.position.y - dragTouchOffsetPx - edgePaddingPx
                                        ).coerceIn(0f, trackTravelPx)
                                        dragThumbOffsetPx = targetOffset
                                        updateZoomFromThumbOffset(targetOffset)
                                    }
                                }
                        } else {
                            Modifier
                        }
                    )
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (edgePaddingPx + thumbOffsetPx).roundToInt()
                        )
                    }
                    .size(width = touchWidth, height = DefaultVerticalZoomBarThumbHeight)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = width, height = DefaultVerticalZoomBarThumbHeight)
                        .background(
                            color = if (canInteract) color else color.copy(alpha = 0.45f),
                            shape = VerticalZoomBarShape
                        )
                )
            }
        }

        Spacer(modifier = Modifier.size(DefaultVerticalZoomButtonSpacing))

        IconButton(
            onClick = {
                if (canInteract) {
                    onZoomChange(
                        applyZoomStep(
                            zoomValue = latestZoomValue,
                            stepPercent = latestStepPercent,
                            minZoom = latestMinZoom,
                            maxZoom = latestMaxZoom,
                            increase = false
                        )
                    )
                }
            },
            enabled = canInteract,
            modifier = Modifier.size(DefaultVerticalZoomButtonSize)
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Decrease zoom",
                tint = if (canInteract) color else color.copy(alpha = 0.45f)
            )
        }
    }
}
