package com.deeplab.translator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "deepseek_translator"
        private const val KEY_API_KEY = "api_key"
        private const val API_URL = "https://api.deepseek.com/chat/completions"
        private const val MODEL = "deepseek-chat"
    }

    private val languages = Languages.list

    private lateinit var prefs: SharedPreferences
    private lateinit var dbHelper: TranslationDbHelper
    private lateinit var ttsManager: TtsManager
    private lateinit var speechInput: SpeechInput
    private lateinit var etInput: EditText
    private lateinit var tvResult: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnTranslate: MaterialButton
    private lateinit var btnCopy: ImageButton
    private lateinit var btnSpeak: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var btnSwap: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnHistory: ImageButton
    private lateinit var spSource: Spinner
    private lateinit var spTarget: Spinner
    private lateinit var progressBar: ProgressBar
    private lateinit var cbHanja: CheckBox
    private lateinit var rowHanja: LinearLayout

    /** 最近一次翻译的信息，用于朗读与记录 */
    private var lastTarget: Languages.Language? = null
    private var lastSrcDisplay = ""
    private var lastTgtDisplay = ""
    private var lastSrcText = ""

    /** 语音输入前输入框里是否为空（识别取消时恢复） */
    private var preVoiceInput = ""

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                onMicClicked()
            } else {
                Toast.makeText(this, getString(R.string.mic_permission_needed), Toast.LENGTH_SHORT).show()
            }
        }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        dbHelper = TranslationDbHelper(this)

        // 朗读管理器：仅在线语音（Edge 引擎）
        ttsManager = TtsManager(this)

        speechInput = SpeechInput(this)

        etInput = findViewById(R.id.et_input)
        tvResult = findViewById(R.id.tv_result)
        tvStatus = findViewById(R.id.tv_status)
        btnTranslate = findViewById(R.id.btn_translate)
        btnCopy = findViewById(R.id.btn_copy)
        btnSpeak = findViewById(R.id.btn_speak)
        btnMic = findViewById(R.id.btn_mic)
        btnSwap = findViewById(R.id.btn_swap)
        btnSettings = findViewById(R.id.btn_settings)
        btnHistory = findViewById(R.id.btn_history)
        spSource = findViewById(R.id.sp_source)
        spTarget = findViewById(R.id.sp_target)
        progressBar = findViewById(R.id.progress_bar)
        cbHanja = findViewById(R.id.cb_hanja)
        rowHanja = findViewById(R.id.row_hanja)

        // 下拉列表：首项「自动检测」本地化，其余语言用各自母语名（便于各语言用户查找）
        val displayNames = listOf(getString(R.string.auto_detect)) + languages.drop(1).map { it.display }
        spSource.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spTarget.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // 目标语言为韩语/朝鲜语时显示「汉谚混写」选项
        spTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isKorean = Languages.koreanCodes.contains(languages[position].code)
                rowHanja.visibility = if (isKorean) View.VISIBLE else View.GONE
                if (!isKorean) cbHanja.isChecked = false
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        spSource.setSelection(0) // 自动检测
        spTarget.setSelection(maxOf(0, languages.indexOfFirst { it.code == "中文" }))

        btnTranslate.setOnClickListener { onTranslate() }
        btnSwap.setOnClickListener { onSwap() }
        btnCopy.setOnClickListener { onCopy() }
        btnSpeak.setOnClickListener { speakResult() }
        btnMic.setOnClickListener { onMicClicked() }
        btnSettings.setOnClickListener { showSettingsMenu() }
        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    override fun onDestroy() {
        speechInput.stop()
        ttsManager.release()
        super.onDestroy()
    }

    // ---------- 语音输入（系统识别服务：小爱/蓝心/谷歌） ----------

    private fun onMicClicked() {
        if (speechInput.isListening()) {
            speechInput.stop()
            setMicUi(false)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!speechInput.isAvailable()) {
            Toast.makeText(this, getString(R.string.no_asr_service), Toast.LENGTH_LONG).show()
            return
        }

        val src = languages[spSource.selectedItemPosition]
        var tag = if (src.code == "auto") Locale.getDefault().toLanguageTag() else src.localeTag
        // 识别服务普遍不支持 ko-KP，朝鲜语识别映射到 ko-KR
        if (tag.startsWith("ko-")) tag = "ko-KR"

        preVoiceInput = etInput.text.toString()
        ttsManager.stop()
        setSpeakUi(false)

        speechInput.start(tag, object : SpeechInput.Listener {
            override fun onListeningStarted() {
                runOnUiThread {
                    setMicUi(true)
                    tvStatus.visibility = View.GONE
                    Toast.makeText(this@MainActivity, getString(R.string.listening_speak), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onPartial(text: String) {
                runOnUiThread { etInput.setText(text) }
            }

            override fun onResult(text: String) {
                runOnUiThread {
                    setMicUi(false)
                    etInput.setText(text)
                    etInput.setSelection(text.length)
                    Toast.makeText(this@MainActivity, getString(R.string.recognition_done), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(messageResId: Int, needKeyboardHint: Boolean) {
                runOnUiThread {
                    setMicUi(false)
                    if (etInput.text.isBlank()) etInput.setText(preVoiceInput)
                    val hint = if (needKeyboardHint) getString(R.string.keyboard_hint) else ""
                    tvStatus.text = getString(R.string.voice_input_prefix) + getString(messageResId) + hint
                    tvStatus.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun setMicUi(listening: Boolean) {
        btnMic.imageTintList = ColorStateList.valueOf(
            if (listening) Color.parseColor("#E53935") else Color.parseColor("#4D6BFE")
        )
    }

    // ---------- 翻译 ----------

    private fun onTranslate() {
        val key = prefs.getString(KEY_API_KEY, "").orEmpty().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, getString(R.string.set_api_key_first), Toast.LENGTH_SHORT).show()
            showApiKeyDialog()
            return
        }
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_text), Toast.LENGTH_SHORT).show()
            return
        }
        val src = languages[spSource.selectedItemPosition]
        val tgt = languages[spTarget.selectedItemPosition]
        if (tgt.code == "auto") {
            Toast.makeText(
                this,
                getString(R.string.target_no_auto, getString(R.string.auto_detect)),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (src.code != "auto" && src.code == tgt.code) {
            Toast.makeText(this, getString(R.string.same_language), Toast.LENGTH_SHORT).show()
            return
        }

        lastTarget = tgt
        lastSrcDisplay = src.display
        lastTgtDisplay = tgt.display
        lastSrcText = text

        setLoading(true)
        tvStatus.visibility = View.GONE
        tvResult.text = ""
        val hanja = cbHanja.isChecked && Languages.koreanCodes.contains(tgt.code)
        Thread { requestTranslation(key, src, tgt, text, hanja) }.start()
    }

    private fun requestTranslation(key: String, src: Languages.Language, tgt: Languages.Language, text: String, hanja: Boolean) {
        try {
            val systemPrompt = buildString {
                append("你是一名专业翻译专家，精通世界各国语言。请把用户输入的文本翻译成目标语言。\n")
                append("要求：\n")
                append("1. 只输出翻译结果本身，不要任何解释、注释、引号或前后缀；\n")
                append("2. 保持原文的语气、风格和段落换行结构；\n")
                append("3. 人名、品牌名等专有名词可采用通用译法或保留原文；\n")
                append("4. 如果文本本身已经是目标语言，请原样输出。\n")
                if (tgt.code == "조선말") {
                    append("5. 目标语言是朝鲜语：请使用朝鲜民主主义人民共和国标准语（문화어，文化语）的规范拼写与词汇习惯，" +
                        "不要使用大韩民国（한국）式的外来语和拼写习惯。\n")
                }
                if (hanja) {
                    append("6. 目标语言是韩语/朝鲜语：请采用汉谚混写（국한문혼용）风格输出——" +
                        "汉字词写成汉字（例如「大韓民國」「學生」「山」），固有词、助词和语法词尾用谚文书写。\n")
                }
            }
            val userPrompt = buildString {
                if (src.code == "auto") {
                    append("请自动识别下面文本的语言，然后翻译成${tgt.display}（${tgt.code}）：\n\n")
                } else {
                    append("请把下面的${src.display}（${src.code}）文本翻译成${tgt.display}（${tgt.code}）：\n\n")
                }
                append(text)
            }
            val jsonBody = JSONObject()
                .put("model", MODEL)
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPrompt))
                )
                .put("temperature", 0.3)
                .put("stream", false)
                .toString()

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val content = JSONObject(respBody)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content", "")
                        .trim()
                    if (content.isEmpty()) {
                        postError(getString(R.string.err_empty_api))
                    } else {
                        runOnUiThread { showResult(content) }
                    }
                } else {
                    postError(parseError(response.code, respBody))
                }
            }
        } catch (e: Exception) {
            postError(getString(R.string.err_request_failed, e.message ?: getString(R.string.err_unknown)))
        }
    }

    private fun parseError(code: Int, body: String): String {
        val apiMsg = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
        val base = when (code) {
            401 -> getString(R.string.err_401)
            402 -> getString(R.string.err_402)
            403 -> getString(R.string.err_403)
            404 -> getString(R.string.err_404)
            429 -> getString(R.string.err_429)
            in 500..599 -> getString(R.string.err_5xx, code)
            else -> getString(R.string.err_http, code)
        }
        return if (apiMsg.isEmpty()) base else "$base：$apiMsg"
    }

    private fun postError(msg: String) {
        runOnUiThread {
            setLoading(false)
            tvStatus.text = msg
            tvStatus.visibility = View.VISIBLE
        }
    }

    private fun showResult(content: String) {
        setLoading(false)
        tvResult.text = content
        // 写入翻译记录
        try {
            dbHelper.insert(System.currentTimeMillis(), lastSrcDisplay, lastTgtDisplay, lastSrcText, content)
        } catch (_: Exception) {
            // 记录失败不影响翻译结果展示
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnTranslate.isEnabled = !loading
        btnTranslate.text = if (loading) getString(R.string.translating) else getString(R.string.translate)
    }

    private fun onSwap() {
        val si = spSource.selectedItemPosition
        val ti = spTarget.selectedItemPosition
        if (si == 0) {
            // 源语言是「自动检测」：把目标语言换到源，目标设为中文
            spSource.setSelection(ti)
            spTarget.setSelection(maxOf(0, languages.indexOfFirst { it.code == "中文" }))
        } else {
            spSource.setSelection(ti)
            spTarget.setSelection(si)
        }
    }

    private fun onCopy() {
        val result = tvResult.text.toString()
        val placeholder = getString(R.string.placeholder_result)
        if (result.isEmpty() || result == placeholder) {
            Toast.makeText(this, getString(R.string.no_result), Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("translation", result))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    // ---------- 朗读（在线 Edge 引擎） ----------

    private fun speakResult() {
        val text = tvResult.text.toString()
        val placeholder = getString(R.string.placeholder_result)
        if (text.isEmpty() || text == placeholder) {
            Toast.makeText(this, getString(R.string.no_result), Toast.LENGTH_SHORT).show()
            return
        }
        // 再点一次 = 停止朗读
        if (ttsManager.isBusy) {
            ttsManager.stop()
            setSpeakUi(false)
            return
        }
        val tgt = lastTarget
        if (tgt == null) {
            Toast.makeText(this, getString(R.string.complete_translation_first), Toast.LENGTH_SHORT).show()
            return
        }
        ttsManager.speak(text, tgt, object : TtsManager.Listener {
            override fun onStart() {
                runOnUiThread {
                    setSpeakUi(true)
                    Toast.makeText(this@MainActivity, getString(R.string.synthesizing), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onSuccess(modeUsed: String) {
                runOnUiThread { setSpeakUi(false) }
            }

            override fun onError(messageResId: Int) {
                runOnUiThread {
                    setSpeakUi(false)
                    tvStatus.text = getString(R.string.speak_prefix) + getString(messageResId)
                    tvStatus.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun setSpeakUi(speaking: Boolean) {
        btnSpeak.imageTintList = ColorStateList.valueOf(
            if (speaking) Color.parseColor("#E53935") else Color.parseColor("#4D6BFE")
        )
    }

    // ---------- 设置 ----------

    private fun showSettingsMenu() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setItems(
                arrayOf(
                    getString(R.string.api_key_settings),
                    getString(R.string.about_voice)
                )
            ) { _, which ->
                if (which == 0) showApiKeyDialog() else showVoiceInfo()
            }
            .show()
    }

    private fun showVoiceInfo() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.voice_info_title))
            .setMessage(getString(R.string.voice_info_msg))
            .setPositiveButton(getString(R.string.got_it), null)
            .show()
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "sk-…"
            setText(prefs.getString(KEY_API_KEY, ""))
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.api_key_dialog_title))
            .setMessage(getString(R.string.api_key_dialog_msg))
            .setView(container)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val key = input.text.toString().trim()
                prefs.edit().putString(KEY_API_KEY, key).apply()
                val msg = if (key.isEmpty()) getString(R.string.api_key_cleared) else getString(R.string.api_key_saved)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.api_key_clear)) { _, _ ->
                prefs.edit().putString(KEY_API_KEY, "").apply()
                Toast.makeText(this, getString(R.string.api_key_cleared), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
