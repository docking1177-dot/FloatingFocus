package app.floatingfocus

/** Equal-width digits for this format only; prefixes keep their existing font fallback. */
internal fun timeSlotSample(formattedTime: String): String = formattedTime.replace(Regex("[0-9]"), "0")
