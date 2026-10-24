package app.floatingfocus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LaunchableAppPicker(title: String, onDismiss: () -> Unit, onChoose: (LaunchableApp) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { apps = withContext(Dispatchers.IO) { context.launchableApps() } }
        catch (_: RuntimeException) { failed = true }
        loading = false
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(search, { search = it }, label = { Text("搜索名称或包名") }, singleLine = true)
            val matching = apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }
            if (loading) Text("正在加载…")
            else if (failed) Text("读取失败，请关闭后重试。")
            else if (matching.isEmpty()) Text("未找到可打开的 App")
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(matching, key = { it.packageName }) { app ->
                    Column(Modifier.fillMaxWidth().clickable { onChoose(app) }.padding(vertical = 12.dp)) {
                        Text(app.label)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
