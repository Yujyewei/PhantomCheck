package com.phantom.checker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phantom.checker.data.MessageEntity
import com.phantom.checker.service.AppDatabaseHolder
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhantomCheckApp() }
    }
}

@Composable
fun PhantomCheckApp() {
    val ctx = LocalContext.current
    var messages by remember { mutableStateOf(listOf<MessageEntity>()) }

    LaunchedEffect(Unit) {
        // TODO: 以 applicationContext 建立單例 DB 後查詢；query 空白時載入 recent()
    }

    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(12.dp)) {
            Text("幻訊 PhantomCheck", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(messages) { m: MessageEntity ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Row {
                                Text("[" + m.appLabel + "] ", style = MaterialTheme.typography.labelSmall)
                                Text(m.sender, style = MaterialTheme.typography.titleSmall)
                                if (m.riskLabel == "scam") Text("  ⚠️ 詐騙 " + m.riskScore, color = Color.Red, style = MaterialTheme.typography.labelSmall)
                                if (m.riskLabel == "suspicious") Text("  ❓ 疑似 " + m.riskScore, color = Color(0xFFE6A100), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(m.content, style = MaterialTheme.typography.bodySmall)
                            if (m.riskReason.isNotEmpty()) Text(m.riskReason, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
