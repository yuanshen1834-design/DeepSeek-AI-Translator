package com.deeplab.translator

/**
 * 语言列表：
 * - code 用于提示词，display 用于界面显示
 * - localeTag 用于本地 TTS 发音
 * - edgeVoice 用于在线语音合成的音色
 */
object Languages {

    data class Language(
        val code: String,
        val display: String,
        val localeTag: String,
        val edgeVoice: String = ""
    )

    val list = listOf(
        Language("auto", "自动检测", ""),
        Language("中文", "中文", "zh-CN", "zh-CN-XiaoxiaoNeural"),
        Language("繁體中文", "繁體中文", "zh-TW", "zh-TW-HsiaoChenNeural"),
        Language("English", "英语", "en-US", "en-US-AriaNeural"),
        Language("日本語", "日语", "ja-JP", "ja-JP-NanamiNeural"),
        Language("한국어", "韩语", "ko-KR", "ko-KR-SunHiNeural"),
        Language("조선말", "朝鲜语", "ko-KP", "ko-KR-SunHiNeural"),
        Language("Français", "法语", "fr-FR", "fr-FR-DeniseNeural"),
        Language("Deutsch", "德语", "de-DE", "de-DE-KatjaNeural"),
        Language("Español", "西班牙语", "es-ES", "es-ES-ElviraNeural"),
        Language("Русский", "俄语", "ru-RU", "ru-RU-SvetlanaNeural"),
        Language("Português", "葡萄牙语", "pt-PT", "pt-PT-FernandaNeural"),
        Language("Italiano", "意大利语", "it-IT", "it-IT-ElsaNeural"),
        Language("العربية", "阿拉伯语", "ar-SA", "ar-SA-ZariyahNeural"),
        Language("ไทย", "泰语", "th-TH", "th-TH-PremwadeeNeural"),
        Language("Tiếng Việt", "越南语", "vi-VN", "vi-VN-HoaiMyNeural")
    )

    /** 韩语/朝鲜语类语言（支持汉谚混写） */
    val koreanCodes = setOf("한국어", "조선말")

    fun byDisplay(display: String): Language? = list.firstOrNull { it.display == display }
}
