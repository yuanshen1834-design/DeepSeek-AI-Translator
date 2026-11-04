# 基于 DeepSeek API 的人工智能翻译软件（DeepSeek AI Translator）

一款基于 DeepSeek API 的多语言人工智能翻译软件（安卓版）。

支持 16 种语言互译、9 种界面语言、语音输入与在线语音朗读、翻译记录管理，并对韩语/朝鲜语提供汉谚混写输出与朝鲜文化语（문화어）翻译。

## 功能特性

- 🌐 **16 种语言互译**：中文（简体/繁体）、英语、日语、韩语、朝鲜语、法语、德语、西班牙语、俄语、葡萄牙语、意大利语、阿拉伯语、泰语、越南语
- 🔤 **9 种界面语言**：简体中文、繁体中文、English、日本語、한국어、العربية、Français、Русский、Español（跟随系统语言，阿拉伯语支持 RTL 布局）
- 🎤 **语音输入**：调用系统语音识别服务（小米→小爱、vivo→蓝心/讯飞），识别语言自动跟随源语言
- 🔊 **语音朗读**：微软 Edge 在线语音引擎（免费、音色自然、全语言覆盖），自动分块连续朗读
- 🇰🇵 **朝鲜语翻译**：按朝鲜文化语（문화어）规范输出
- 🀄 **汉谚混写**：目标为韩语/朝鲜语时可选「汉谚混写（국한문혼용）」——汉字词用汉字、固有词用谚文
- 📜 **翻译记录**：SQLite 自动保存，支持单条删除/一键清空/再次朗读
- 🔑 API Key 仅保存在本机；401/402/429 等 API 错误友好提示

## 技术栈

| 组件 | 说明 |
|------|------|
| 语言 | Kotlin |
| 构建 | AGP 7.4.2 · Gradle 7.6.4 · JDK 11 |
| SDK | compileSdk 33 / targetSdk 33 / minSdk 24 |
| 网络 | OkHttp 4.11 |
| 存储 | SQLite（翻译记录） |
| 语音 | Edge 在线 TTS（WebSocket + SSML）+ 系统语音识别 |

## 构建

```powershell
# 1. 生成签名密钥（release.keystore 未纳入仓库）
keytool -genkeypair -keystore release.keystore -alias deepseek -keyalg RSA -keysize 2048 -validity 10000

# 2. local.properties 指向本机 Android SDK
# sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk

# 3. 构建
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

## 使用说明

1. 在 <https://platform.deepseek.com> 注册并创建 API Key（sk- 开头）
2. App 右上角 ⚙️ → 设置 API Key（仅保存在本机）
3. 选择源/目标语言 → 输入或语音输入 → 翻译 → 可朗读/复制

## 许可证

本软件为自由及开源软件（Free and Open Source Software, FOSS），依据 Apache License, Version 2.0 与 GNU General Public License v3.0（GPL-3.0）双重许可协议发布（SPDX: `Apache-2.0 OR GPL-3.0`），源代码可自由使用、修改与再分发。

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- GPL-3.0: https://www.gnu.org/licenses/gpl-3.0.html

开发者：绪山真寻（华夏服饰历史博物馆年卡会员）

Copyleft (ɔ) 2001-2026 绪山真寻
