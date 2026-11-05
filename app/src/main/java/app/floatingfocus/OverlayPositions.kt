package app.floatingfocus

enum class OverlayOrientation {
    PORTRAIT, LANDSCAPE;
    companion object { fun at(width: Int, height: Int) = if (width > height) LANDSCAPE else PORTRAIT }
}

/** Preferred anchors only: neither measured/clamped frames nor expansion pins belong here. */
data class OverlayPositions(val portrait: OverlayAnchor? = null, val landscape: OverlayAnchor? = null,
                            val legacy: OverlayAnchor? = null, val legacyOrientation: OverlayOrientation? = null) {
    fun anchor(direction: OverlayOrientation) = if (direction == OverlayOrientation.PORTRAIT) portrait else landscape
    fun migrate(direction: OverlayOrientation): OverlayPositions {
        if (legacy == null || legacyOrientation != null || portrait != null || landscape != null) return this
        return remember(direction, legacy).copy(legacyOrientation = direction)
    }
    fun remember(direction: OverlayOrientation, anchor: OverlayAnchor) =
        if (direction == OverlayOrientation.PORTRAIT) copy(portrait = anchor) else copy(landscape = anchor)
}

object OverlayPositionsCodec {
    fun encode(value: OverlayPositions): Map<String, Any> = buildMap {
        fun point(key: String, anchor: OverlayAnchor?) { anchor?.let { put(key, mapOf("x" to it.xFraction,"y" to it.yFraction)) } }
        point("portrait",value.portrait); point("landscape",value.landscape); point("legacy",value.legacy)
        value.legacyOrientation?.let { put("legacyOrientation",it.name) }
    }
    fun decode(value: Any?): OverlayPositions? { return try {
        if (value == null) OverlayPositions() else {
            val row = value as? Map<*, *> ?: return null
            fun point(key: String): OverlayAnchor? {
                if (!row.containsKey(key)) return null
                val p = row[key] as? Map<*, *> ?: error("Invalid position")
                return OverlayAnchor.decode(p["x"], p["y"]) ?: error("Invalid coordinates")
            }
            val direction = if (row.containsKey("legacyOrientation"))
                OverlayOrientation.entries.firstOrNull { it.name == row["legacyOrientation"] } ?: error("Invalid orientation") else null
            OverlayPositions(point("portrait"),point("landscape"),point("legacy"),direction)
        }
    } catch (_: RuntimeException) { null } }
}

data class OverlayViewport(val width: Int,val height: Int)

/** Only UP in the original viewport can commit. Measurements may read the pending anchor. */
internal class OverlayDragCheckpoint {
    private var started: OverlayViewport? = null
    private var anchor: OverlayAnchor? = null
    fun begin(viewport: OverlayViewport) { started=viewport;anchor=null }
    fun move(viewport: OverlayViewport,value: OverlayAnchor) { if(started==viewport) anchor=value else cancel() }
    fun accepts(viewport: OverlayViewport)=started==viewport
    fun pending(viewport: OverlayViewport)=anchor.takeIf { started==viewport }
    fun finish(viewport: OverlayViewport): OverlayAnchor? = pending(viewport).also { cancel() }
    fun cancel() { started=null;anchor=null }
}
