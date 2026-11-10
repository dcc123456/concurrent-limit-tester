package com.example.concurrentlimittester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TesterScreen() } }
    }
}

data class TestResult(
    val device: String,
    val allowed: Boolean,
    val status: Int,
    val activeCount: Int?,
    val message: String
)

@Composable
fun TesterScreen() {
    var baseUrl by remember { mutableStateOf("http://10.0.2.2:3000") }
    var testToken by remember { mutableStateOf("replace-with-staging-test-token") }
    var deviceCount by remember { mutableStateOf("10") }
    var maxAllowed by remember { mutableStateOf("3") }
    var accountId by remember { mutableStateOf("qa-account-001") }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(listOf<TestResult>()) }
    var summary by remember { mutableStateOf("尚未开始测试") }
    val scope = rememberCoroutineScope()

    val allowedCount = results.count { it.allowed }
    val deniedCount = results.count { !it.allowed && it.status in 200..499 }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("并发限制安全测试", style = MaterialTheme.typography.headlineSmall)
        Text("仅用于你拥有或获准测试的 staging / QA 环境。不会访问腾讯视频正式播放接口。",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("测试服务 Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(testToken, { testToken = it }, label = { Text("测试 Token") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(accountId, { accountId = it }, label = { Text("测试账号 ID") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(deviceCount, { deviceCount = it.filter(Char::isDigit).take(2) },
                label = { Text("模拟设备数 (1-50)") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(maxAllowed, { maxAllowed = it.filter(Char::isDigit).take(2) },
                label = { Text("预期上限") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val n = deviceCount.toIntOrNull()?.coerceIn(1, 50) ?: 10
                val limit = maxAllowed.toIntOrNull()?.coerceIn(1, 50) ?: 3
                val endpoint = baseUrl.trim().trimEnd('/')
                if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                    summary = "Base URL 必须以 http:// 或 https:// 开头"
                } else if (testToken.isBlank() || accountId.isBlank()) {
                    summary = "测试 Token 和账号 ID 不能为空"
                } else {
                    running = true
                    results = emptyList()
                    summary = "正在同时发起 $n 个授权请求…"
                    scope.launch {
                        val client = TestApi(endpoint, testToken.trim())
                        val batch = withContext(Dispatchers.IO) {
                            coroutineScope {
                                (1..n).map { index ->
                                    async(Dispatchers.IO) {
                                        client.requestPlayback(
                                            accountId = accountId.trim(),
                                            deviceId = "qa-device-\${index}-\${UUID.randomUUID()}",
                                            requestId = UUID.randomUUID().toString()
                                        )
                                    }
                                }.awaitAll()
                            }
                        }
                        results = batch.sortedBy { it.device }
                        val success = batch.count { it.allowed }
                        val errors = batch.count { it.status == 0 }
                        summary = when {
                            errors > 0 -> "完成：授权成功 $success 台；网络/协议错误 $errors 台。请检查接口日志。"
                            success > limit -> "发现异常：成功授权 $success 台，超过预期上限 $limit 台。请结合服务端日志复核。"
                            success == limit -> "符合预期：成功授权 $success 台，达到上限 $limit 台。"
                            else -> "结果：成功授权 $success 台，低于上限 $limit 台；检查账号状态或接口策略。"
                        }
                        running = false
                    }
                }
            }
        ) { Text(if (running) "测试运行中…" else "开始并发授权测试") }

        OutlinedButton(
            enabled = !running && results.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                running = true
                summary = "正在释放本轮获得授权的测试设备…"
                scope.launch {
                    val snapshot = results.filter { it.allowed }
                    val api = TestApi(baseUrl.trim().trimEnd('/'), testToken.trim())
                    withContext(Dispatchers.IO) {
                        snapshot.map { r ->
                            async(Dispatchers.IO) {
                                api.releasePlayback(accountId.trim(), r.device)
                            }
                        }.awaitAll()
                    }
                    summary = "释放请求已发送 \${snapshot.size} 个。请以服务端会话状态为准。"
                    running = false
                }
            }
        ) { Text("释放本轮已授权设备") }

        Spacer(Modifier.height(12.dp))
        Text(summary, style = MaterialTheme.typography.bodyMedium)
        Text("授权成功：$allowedCount    拒绝/非成功：$deniedCount", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(results) { item ->
                ListItem(
                    headlineContent = { Text(item.device) },
                    supportingContent = { Text("HTTP \${item.status} · \${item.message} · active=\${item.activeCount ?: "未知"}") },
                    trailingContent = {
                        Text(if (item.allowed) "允许" else "拒绝",
                            color = if (item.allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

/** 只调用明确配置的测试接口，不获取真实视频流或探测正式环境接口。 */
class TestApi(private val baseUrl: String, private val token: String) {
    fun requestPlayback(accountId: String, deviceId: String, requestId: String): TestResult {
        val body = JSONObject()
            .put("accountId", accountId)
            .put("deviceId", deviceId)
            .put("requestId", requestId)
            .put("purpose", "concurrency-security-test")
        return post("/v1/test/playback-sessions", body, deviceId)
    }

    fun releasePlayback(accountId: String, deviceId: String): TestResult {
        val body = JSONObject().put("accountId", accountId).put("deviceId", deviceId)
        return post("/v1/test/playback-sessions/release", body, deviceId)
    }

    private fun post(path: String, body: JSONObject, deviceId: String): TestResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Security-Test", "true")
                setRequestProperty("X-Request-ID", body.optString("requestId", UUID.randomUUID().toString()))
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            val allowed = code in 200..299 && json?.optBoolean("allowed", false) == true
            TestResult(
                device = deviceId,
                allowed = allowed,
                status = code,
                activeCount = if (json?.has("activeCount") == true) json.optInt("activeCount") else null,
                message = json?.optString("message")?.take(120)?.ifBlank { "HTTP response" } ?: raw.take(120).ifBlank { "无响应正文" }
            )
        } catch (e: Exception) {
            TestResult(deviceId, false, 0, null, e.message ?: "network error")
        } finally {
            connection?.disconnect()
        }
    }
}
