package app.floatingfocus

import kotlin.math.roundToInt

/** Immutable input shared by the real clock and the isolated debug simulator. */
data class ReminderSnapshot(val remainingMillis: Long, val progress: Float, val overtimeMillis: Long = 0) {
    val stage: ReminderStage get() = ReminderPolicy.stage(progress)

    companion object {
        fun at(timer: Countdown, now: Long) = ReminderSnapshot(
            timer.remainingAt(now), timer.progressAt(now),
            ((now - timer.startedAt).coerceAtLeast(0) - timer.durationMillis).coerceAtLeast(0))
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
    val shape: OverlayShape = OverlayShape.ROUNDED_RECTANGLE
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
    val shape: OverlayShape = OverlayShape.ROUNDED_RECTANGLE
)

object ReminderPresenter {
    const val GREY = 0xFF303833.toInt()
    const val ORANGE = 0xFFAF580D.toInt()
    const val RED = 0xFFB52E36.toInt()

    fun present(
        snapshot: ReminderSnapshot,
        settings: ReminderSettings = ReminderSettings(),
        acknowledgedStage: ReminderStage? = null
    ): ReminderPresentation {
        val p = snapshot.progress.coerceIn(0f, 1f)
        val finished = snapshot.stage == ReminderStage.FINISHED
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
                snapshot.overtimeMillis > 0 -> "◷  超时 ${formatRemaining(snapshot.overtimeMillis)}"
                finished -> "◷  时间到了"
                else -> "◷  ${formatRemaining(snapshot.remainingMillis)}"
            },
            finished = finished,
            colorArgb = if (settings.style == ReminderStyle.COLOR) mix(baseColor, color, settings.intensity) else baseColor,
            size = settings.baseSize * if (settings.style == ReminderStyle.SIZE) 1f + 0.5f * sizeProgress * settings.intensity else 1f,
            opacity = settings.opacity,
            pulseDepth = if (snapshot.stage == ReminderStage.URGENT) 0.18f * settings.intensity else 0f,
            confirmationRequired = settings.style == ReminderStyle.CONFIRM &&
                snapshot.stage != ReminderStage.QUIET && snapshot.stage != acknowledgedStage,
            shape = settings.shape
        )
    }

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
