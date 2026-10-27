package app.floatingfocus

fun durationLabel(seconds: Int): String = if (seconds % 60 == 0) "${seconds / 60} 分钟"
    else "${seconds / 60} 分 ${seconds % 60} 秒"
