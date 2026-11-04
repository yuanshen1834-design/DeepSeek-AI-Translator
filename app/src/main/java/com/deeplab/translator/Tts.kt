package com.deeplab.translator

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Edge 在线语音合成（微软 Edge「大声朗读」引擎，免费、无需密钥、音色自然）。
 *
 * 协议与开源 edge-tts 项目一致：
 * 1. 连接 wss://speech.platform.bing.com/...，携带 TrustedClientToken 与 Sec-MS-GEC 鉴权参数
 * 2. 发送 speech.config（输出 MP3 24kHz）与 SSML 合成请求
 * 3. 接收二进制帧（前 2 字节为头长度，其后为音频数据），直到收到 Path:turn.end
 */
object EdgeTts {

    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val CHROMIUM_VERSION = "130.0.2849.68"

    /** Windows 纪元与 Unix 纪元的秒差 */
    private const val WIN_EPOCH_OFFSET = 11644473600L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 生成鉴权参数 Sec-MS-GEC：纳秒级 Windows 时间戳（取整到 5 分钟）+ 令牌的 SHA-256 */
    private fun secMsGec(): String {
        val ticks = (System.currentTimeMillis() / 1000 + WIN_EPOCH_OFFSET) * 1_000_000_000L
        val rounded = ticks / 300_000_000_000L * 300_000_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$rounded$TRUSTED_TOKEN".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    private fun noDashUuid(): String = UUID.randomUUID().toString().replace("-", "")

    /** 服务端要求的 X-Timestamp 格式 */
    private fun timestamp(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * 同步合成语音（必须在后台线程调用）。
     * @param text 待朗读文本
     * @param voice Edge 音色名，如 zh-CN-XiaoxiaoNeural
     * @return MP3 音频字节；失败返回 null
     */
    fun synthesize(text: String, voice: String, timeoutSec: Long = 25): ByteArray? {
        if (text.isBlank() || voice.isBlank()) return null
        val url = "$WSS_URL?TrustedClientToken=$TRUSTED_TOKEN" +
            "&Sec-MS-GEC=${secMsGec()}" +
            "&Sec-MS-GEC-Version=1-$CHROMIUM_VERSION" +
            "&ConnectionId=${noDashUuid()}"

        val latch = CountDownLatch(1)
        val audio = ByteArrayOutputStream()
        val finished = booleanArrayOf(false)

        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ts = timestamp()
                // 1) 输出格式配置
                val config = "X-Timestamp:$ts\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                webSocket.send(config)

                // 2) SSML 合成请求（音色语言 = 音色名前两段）
                val locale = voice.split("-").subList(0, 2).joinToString("-")
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$locale'>" +
                    "<voice name='$voice'>" +
                    "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>${escapeXml(text)}</prosody>" +
                    "</voice></speak>"
                val message = "X-RequestId:${noDashUuid()}\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:$ts\r\n" +
                    "Path:ssml\r\n\r\n$ssml"
                webSocket.send(message)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 二进制帧：[2字节头长度][头][音频数据]
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                if (headerLen <= 0 || headerLen > data.size - 2) return
                val headers = String(data, 2, headerLen, Charsets.UTF_8)
                if (headers.contains("Path:audio")) {
                    synchronized(audio) {
                        audio.write(data, 2 + headerLen, data.size - 2 - headerLen)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    finished[0] = true
                    latch.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })

        try {
            latch.await(timeoutSec, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        ws.cancel()

        val bytes = synchronized(audio) { audio.toByteArray() }
        return if (finished[0] && bytes.isNotEmpty()) bytes else null
    }
}
