package app.floatingfocus

import kotlin.math.roundToInt
import kotlin.math.pow

/** Immutable input shared by the real clock and the isolated debug simulator. */
data class ReminderSnapshot(val remainingMillis: Long, val progress: Float, val overtimeMillis: Long = 0,
                            val mode: TimerMode = TimerMode.COUNTDOWN, val elapsedMillis: Long = 0,
                            val durationMillis: Long = 0) {
    val stage: ReminderStage get() = ReminderPolicy.stage(progress)

    companion object {
        fun at(timer: Countdown, now: Long) = ReminderSnapshot(
            timer.remainingAt(now), timer.progressAt(now),
            timer.overtimeAt(now), timer.mode, timer.elapsedAt(now), timer.durationMillis)
    }
}

enum class ReminderStyle { COLOR, SIZE, CONFIRM }
enum class ChangeMode { CONTINUOUS, STEPS }
// Keep the persisted legacy key: ROUNDED_RECTANGLE now renders as a horizontal capsule.
enum class OverlayShape(val label: String) {
    ROUNDED_RECTANGLE("横向胶囊"), VERTICAL_CAPSULE("纵向胶囊"), CIRCLE("圆形")
}
enum class OverlayColor(val argb: Int, val label: String) {
    GRAPHITE(0xFF303833.toInt(), "石墨"), BLUE(0xFF264B73.toInt(), "蓝色"),
    GREEN(0xFF28553C.toInt(), "绿色"), PURPLE(0xFF583A70.toInt(), "紫色"),
    BROWN(0xFF70452D.toInt(), "棕色")
}

data class ReminderSettings(
    val style: ReminderStyle = ReminderStyle.COLOR,
    val colorMode: ChangeMode = ChangeMode.CONTINUOUS,
    val sizeMode: ChangeMode = ChangeMode.CONTINUOUS,
    val intensity: Float = 1f,
    val baseSize: Float = 1f,
    val opacity: Float = 0.94f,
    val color: OverlayColor = OverlayColor.GRAPHITE,
    val shape: OverlayShape = OverlayShape.ROUNDED_RECTANGLE,
    // style remains for legacy storage and the debug confirmation experiment.
    val colorEnabled: Boolean = style == ReminderStyle.COLOR,
    val sizeEnabled: Boolean = style == ReminderStyle.SIZE,
    val strategy: ReminderStrategy = ReminderStrategy()
) {
    init {
        require(intensity.isFinite() && intensity in 0f..1f)
        require(baseSize.isFinite() && baseSize in 0.75f..1.5f)
        require(opacity.isFinite() && opacity in 0.3f..1f)
    }
}

/** Rendering data only; no Android, service, clock mutation or vibration side effects. */
data class ReminderPresentation(
    val title: String,
    val finished: Boolean,
    val colorArgb: Int,
    val size: Float,
    val opacity: Float,
    val pulseDepth: Float,
    val confirmationRequired: Boolean,
    val shape: OverlayShape = OverlayShape.ROUNDED_RECTANGLE,
    val animationStrength: Float = 1f
)

object ReminderPresenter {
    const val GREY = 0xFF303833.toInt()
    const val ORANGE = 0xFFC47F00.toInt()
    const val RED = 0xFFD10B25.toInt()
    const val HIGHLIGHT_DEPTH = 0.4f

    fun present(
        snapshot: ReminderSnapshot,
        settings: ReminderSettings = ReminderSettings(),
        acknowledgedStage: ReminderStage? = null
    ): ReminderPresentation {
        val p = settings.strategy.effectProgress(snapshot)
        val stage = settings.strategy.stage(snapshot)
        val finished = stage == ReminderStage.FINISHED
        val baseColor = settings.color.argb
        val color = when (settings.colorMode) {
            ChangeMode.STEPS -> when {
                p < ReminderPolicy.WARM_START -> baseColor
                p < ReminderPolicy.URGENT_START -> ORANGE
                else -> RED
            }
            ChangeMode.CONTINUOUS -> when {
                p < ReminderPolicy.WARM_START -> baseColor
                p < ReminderPolicy.URGENT_START -> mix(baseColor, ORANGE, (p - 0.5f) / 0.3f)
                else -> mix(ORANGE, RED, (p - 0.8f) / 0.2f)
            }
        }
        val sizeProgress = when (settings.sizeMode) {
            ChangeMode.CONTINUOUS -> ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
            ChangeMode.STEPS -> when {
                p < 0.5f -> 0f
                p < 0.8f -> 0.4f
                p < 1f -> 0.8f
                else -> 1f
            }
        }
        return ReminderPresentation(
            title = when {
                snapshot.mode == TimerMode.STOPWATCH -> "◷  ${formatElapsed(snapshot.elapsedMillis)}"
                snapshot.overtimeMillis > 0 -> "◷  超时 +${formatElapsed(snapshot.overtimeMillis)}"
                finished -> "◷  时间到了"
                else -> "◷  ${formatRemaining(snapshot.remainingMillis)}"
            },
            finished = finished,
            colorArgb = if (settings.colorEnabled) mix(baseColor, color, settings.intensity) else baseColor,
            size = settings.baseSize * if (settings.sizeEnabled) 1f + 0.5f * sizeProgress * settings.intensity else 1f,
            opacity = settings.opacity,
            pulseDepth = if (stage == ReminderStage.URGENT) HIGHLIGHT_DEPTH * settings.intensity else 0f,
            confirmationRequired = settings.style == ReminderStyle.CONFIRM &&
                stage != ReminderStage.QUIET && stage != acknowledgedStage,
            shape = settings.shape,
            animationStrength = settings.intensity
        )
    }

    /** C4 has its own theme; no ordinary settings or progress-derived paint enter it. */
    fun forC4(frame: C4Frame) = ReminderPresentation(
        title = "◷  ${formatRemaining(frame.remainingMillis)}", finished = false,
        colorArgb = C4Theme.COMPACT_COLOR, size = 1f, opacity = C4Theme.OPACITY,
        pulseDepth = 0f, confirmationRequired = false, shape = C4Theme.COMPACT_SHAPE, animationStrength = 0f)

    /** Brighten the actual shape paint, never fade the window or add a rectangular layer. */
    fun highlight(color: Int, amount: Float): Int = mix(color, 0xFFFFFFFF.toInt(), amount)

    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun contentColor(background: Int): Int =
        if (luminance(background) > 0.179) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    private fun mix(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 255
            val b = (to ushr shift) and 255
            return (a + (b - a) * t).roundToInt() shl shift
        }
        return (255 shl 24) or channel(16) or channel(8) or channel(0)
    }
}
