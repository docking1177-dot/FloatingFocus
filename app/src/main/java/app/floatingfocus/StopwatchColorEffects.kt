package app.floatingfocus

import kotlin.math.roundToInt
import kotlin.random.Random

enum class StopwatchColorMode(val label: String) {
    NONE("不变色"), TRANSITION("A→B"), ALTERNATING("A/B 交替"), RANDOM("随机颜色")
}

/** Background colors only: opacity, scale, shape and timing remain separate. */
data class StopwatchColorSettings(
    val mode: StopwatchColorMode = StopwatchColorMode.NONE,
    val a: Int = 0xff4285f4.toInt(),
    val b: Int = 0xffffc857.toInt()
) {
    init { require(a.ushr(24) == 255 && b.ushr(24) == 255) }

    /** Called once by the event producer, never by rendering or recomposition. */
    fun sample(randomIndex: (Int) -> Int = { Random.nextInt(it) }): List<Int> = when (mode) {
        StopwatchColorMode.NONE -> emptyList()
        StopwatchColorMode.TRANSITION -> listOf(a, b)
        StopwatchColorMode.ALTERNATING -> listOf(a, b, a, b, a)
        StopwatchColorMode.RANDOM -> buildList {
            repeat(4) {
                val options = PALETTE.filter { it != lastOrNull() }
                add(options[randomIndex(options.size)])
            }
        }
    }

    companion object {
        val PALETTE = listOf(0xff4285f4.toInt(), 0xffffc857.toInt(), 0xff35a879.toInt(),
            0xffdc6378.toInt(), 0xff9664ce.toInt(), 0xff36b9c5.toInt())
    }
}

/** The same finite 1.6s keyframes serve real cues and local previews. */
object StopwatchColorTimeline {
    const val DURATION_MILLIS = ReminderEffectTimeline.DURATION_MILLIS

    fun progress(elapsedMillis: Long): Float = ReminderEffectTimeline.progress(elapsedMillis)

    fun color(base: Int, colors: List<Int>, progress: Float, mode: StopwatchColorMode = StopwatchColorMode.TRANSITION): Int {
        if (colors.isEmpty() || progress <= 0f || progress >= 1f || !progress.isFinite()) return base
        if(mode==StopwatchColorMode.ALTERNATING || mode==StopwatchColorMode.RANDOM)
            return colors[(progress*colors.size).toInt().coerceIn(0,colors.lastIndex)]
        val frames = listOf(base) + colors + base
        val position = progress * (frames.size - 1)
        val index = position.toInt().coerceIn(0, frames.lastIndex - 1)
        val fraction = position - index
        val from = frames[index]
        val to = frames[index + 1]
        fun channel(shift: Int) = (((from ushr shift) and 255) +
            (((to ushr shift) and 255) - ((from ushr shift) and 255)) * fraction).roundToInt().coerceIn(0, 255)
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/** A visual edit may replay visuals, but can never replay saved sound/vibration. */
internal fun stopwatchPreviewAudio(explicitEffect: OvertimeEffect?): Set<OvertimeEffect> =
    if (explicitEffect == OvertimeEffect.SOUND || explicitEffect == OvertimeEffect.VIBRATION) setOf(explicitEffect) else emptySet()
