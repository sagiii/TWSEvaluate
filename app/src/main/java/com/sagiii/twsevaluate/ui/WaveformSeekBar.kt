package com.sagiii.twsevaluate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 音量の概形波形をシークバーに重ねて表示する、いわゆるボイスメッセージ風の
 * 再生バー。タップ/ドラッグした位置(0f..1f)を[onSeek]で通知する。
 */
@Composable
fun WaveformSeekBar(
    amplitudes: FloatArray,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val unplayedColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSeek((down.position.x / size.width).coerceIn(0f, 1f))
                    var pointer = down
                    while (pointer.pressed) {
                        val event = awaitPointerEvent()
                        pointer = event.changes.first()
                        onSeek((pointer.position.x / size.width).coerceIn(0f, 1f))
                        pointer.consume()
                    }
                }
            },
    ) {
        if (amplitudes.isEmpty()) return@Canvas
        val barWidth = size.width / amplitudes.size
        val progressX = size.width * progress.coerceIn(0f, 1f)
        val strokeWidth = (barWidth * 0.6f).coerceAtLeast(2f)

        amplitudes.forEachIndexed { index, amplitude ->
            val barHeight = (amplitude * size.height).coerceAtLeast(3f)
            val x = index * barWidth + barWidth / 2
            drawLine(
                color = if (x <= progressX) playedColor else unplayedColor,
                start = Offset(x, size.height / 2 - barHeight / 2),
                end = Offset(x, size.height / 2 + barHeight / 2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
