package com.deeplab.translator

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 语音输入（识别）模块：
 * - 优先使用系统默认识别服务（小米手机→小爱/讯飞内核，vivo→蓝心/讯飞内核，其他→谷歌）
 * - 无默认服务时（国产 ROM 常见）尝试 Android 13+ 的离线识别
 * - 全部不可用时给出友好提示（引导用输入法语音键）
 *
 * 识别语言跟随源语言选择；「自动检测」用系统语言。
 */
class SpeechInput(private val activity: Activity) {

    interface Listener {
        /** 开始监听 */
        fun onListeningStarted()

        /** 中间结果（实时上屏） */
        fun onPartial(text: String)

        /** 最终识别结果 */
        fun onResult(text: String)

        /** 出错：messageResId 为本地化文案资源；needKeyboardHint=true 时建议改用输入法语音键 */
        fun onError(messageResId: Int, needKeyboardHint: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null

    /** 是否可用（默认服务 或 离线服务） */
    fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(activity) ||
            (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity))
    } catch (_: Exception) {
        false
    }

    fun isListening(): Boolean = recognizer != null

    /** 开始识别 */
    fun start(languageTag: String, l: Listener) {
        stop()
        listener = l

        val rec: SpeechRecognizer? = try {
            if (SpeechRecognizer.isRecognitionAvailable(activity)) {
                SpeechRecognizer.createSpeechRecognizer(activity)
            } else if (Build.VERSION.SDK_INT >= 33 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
            ) {
                // 离线识别（Android 13+，部分 OEM 支持）
                SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            } else {
                l.onError(R.string.asr_no_service, true)
                return
            }
        } catch (_: Exception) {
            null
        }
        if (rec == null) {
            l.onError(R.string.asr_cannot_start, true)
            return
        }
        recognizer = rec

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listener?.onListeningStarted()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) listener?.onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                finish()
                if (text.isEmpty()) {
                    listener?.onError(R.string.asr_no_voice, false)
                } else {
                    listener?.onResult(text)
                }
            }

            override fun onError(error: Int) {
                finish()
                val (msgRes, hint) = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> R.string.asr_audio to false
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.asr_permission to false
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        R.string.asr_network to true
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        R.string.asr_no_match to false
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.asr_busy to false
                    SpeechRecognizer.ERROR_SERVER -> R.string.asr_server to false
                    else -> R.string.asr_unavailable to true
                }
                listener?.onError(msgRes, hint)
            }
        })

        val langTag = if (languageTag.isNotEmpty()) languageTag else Locale.getDefault().toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            finish()
            listener?.onError(R.string.asr_cannot_start, true)
        }
    }

    /** 停止并释放本次识别 */
    fun stop() {
        val rec = recognizer ?: return
        recognizer = null
        try {
            rec.stopListening()
        } catch (_: Exception) {
        }
        try {
            rec.destroy()
        } catch (_: Exception) {
        }
    }

    private fun finish() {
        val rec = recognizer ?: return
        recognizer = null
        try {
            rec.destroy()
        } catch (_: Exception) {
        }
    }
}
