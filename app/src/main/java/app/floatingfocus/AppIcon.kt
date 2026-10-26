package app.floatingfocus

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local package artwork; labels on the parent control supply accessibility text. */
@Composable
fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val bitmap by produceState<ImageBitmap?>(null, packageName, pixels) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(pixels, pixels).asImageBitmap()
            } catch (_: PackageManager.NameNotFoundException) { null }
              catch (_: SecurityException) { null }
        }
    }
    bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.size(size)) }
        ?: Icon(painterResource(R.drawable.ic_app_placeholder), contentDescription = null,
            modifier = Modifier.size(size))
}
