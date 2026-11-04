package com.deeplab.translator

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 统一朗读管理器（v4.1）：仅使用在线语音合成（微软 Edge 引擎）。
 * 音色自然、覆盖全部 15 种语言；本地 TTS 引擎已完全弃用。
 */
class TtsManager(private val context: Context) {

    interface Listener {
        /** 开始合成 */
        fun onStart()

        /** 朗读结束 */
        fun onSuccess(modeUsed: String)

        /** 失败（messageResId 为本地化文案资源） */
        fun onError(messageResId: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var busy = false

    /** 朗读代数：用于取消进行中的合成/播放 */
    @Volatile
    private var generation = 0

    val isBusy: Boolean get() = busy

    /** 朗读一段文本。再次调用会打断上一次；调用 stop() 可手动停止。 */
    fun speak(text: String, language: Languages.Language, listener: Listener) {
        if (text.isBlank()) {
            listener.onError(R.string.tts_no_content)
            return
        }
        stop()
        busy = true
        val gen = ++generation
        listener.onStart()

        val capped = if (text.length > 3000) text.substring(0, 3000) else text
        if (language.edgeVoice.isEmpty()) {
            busy = false
            listener.onError(R.string.tts_lang_unsupported)
            return
        }

        Thread {
            val bytes = EdgeTts.synthesize(capped, language.edgeVoice)
            if (generation != gen) return@Thread
            if (bytes != null && bytes.isNotEmpty()) {
                playMp3(bytes, gen, listener)
            } else {
                mainHandler.post {
                    if (generation == gen) {
                        busy = false
                        listener.onError(R.string.tts_synthesis_failed)
                    }
                }
            }
        }.start()
    }

    /** 停止当前朗读/合成 */
    fun stop() {
        generation++
        busy = false
        stopPlayback()
    }

    /** 释放资源 */
    fun release() {
        stop()
    }

    // ---------- 在线音频播放 ----------

    private fun stopPlayback() {
        try {
            mediaPlayer?.let { if (it.isPlaying) it.stop() }
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    private fun playMp3(bytes: ByteArray, gen: Int, listener: Listener) {
        mainHandler.post {
            if (generation != gen) return@post
            stopPlayback()
            var file: File? = null
            try {
                file = File.createTempFile("tts_", ".mp3", context.cacheDir)
                file.writeBytes(bytes)

                val mp = MediaPlayer()
                mediaPlayer = mp
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    it.release()
                    if (mediaPlayer === it) mediaPlayer = null
                    file.delete()
                    busy = false
                    listener.onSuccess("在线语音")
                }
                mp.setOnErrorListener { mp2, _, _ ->
                    mp2.release()
                    if (mediaPlayer === mp2) mediaPlayer = null
                    file.delete()
                    busy = false
                    listener.onError(R.string.tts_play_failed)
                    true
                }
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                try {
                    mediaPlayer?.release()
                } catch (_: Exception) {
                }
                mediaPlayer = null
                file?.delete()
                busy = false
                listener.onError(R.string.tts_play_failed)
            }
        }
    }
}
