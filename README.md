# 天眼 TTS Mobile

天眼 TTS Mobile 是一个轻量 Android WebView 浏览器壳。它可以打开 Paseo、ZCode 远控页和普通网页，并在可读内容旁提供手机本地语音朗读能力。

项目不依赖 Gradle，使用 Android SDK 命令行工具直接构建 APK。语音播放走 Android 原生 TextToSpeech，不上传文本，不生成音频文件。

## 功能

- Android WebView 浏览器壳，支持 HTTP/HTTPS 链接打开。
- 顶部原生工具栏：后退、刷新、地址栏、收藏、朗读/停止、打开。
- 多标签浏览，最多 6 个网页标签。
- 原生连接页：粘贴链接、打开链接、收藏当前网页、收藏列表、扫码配对。
- 网页正文和 AI 回复下方自动插入“播放语音”按钮。
- 顶部“朗读”从当前屏幕可见内容开始向下朗读。
- 聊天回复按段落、标题、编号列表、类别块、视觉表格等结构拆分朗读。
- 跳过用户消息、输入框、按钮、代码块、隐藏文本和低内容 UI 碎片。
- 长文本按段进入 Android TTS 队列，避免只播放前半段。
- 手机横竖屏切换时保留当前 WebView 页面。

## 已适配的软件和页面

| 软件 / 页面 | 适配内容 |
| --- | --- |
| Paseo Web (`app.paseo.sh`) | 配对链接打开、聊天回复播放、当前可见内容朗读、顶部安全区空白处理、收藏入口 |
| ZCode Web Remote (`zcode.z.ai/remote/v3`) | 远控任务页打开、AI 回复播放、对话页输入框布局保护、文件卡片/隐藏文本跳过 |
| 普通网页 / Markdown 页面 | 段落、列表、引用块朗读；顶部工具栏朗读当前可见正文 |
| Android 系统 TTS | 使用系统中文语音引擎播放，默认简体中文，语速 1.2x |
| 小米系统扫码器 | 可选扫码配对入口；没有对应扫码器时仍可手动粘贴链接 |

本项目与 Paseo、ZCode 不是官方关联项目，只是在 Android WebView 中做辅助朗读和浏览体验适配。

## 构建要求

- Android SDK
  - `platform-tools`
  - `build-tools;36.0.0`
  - `platforms;android-36`
- JDK 17 或更新版本
- Python 3
- Pillow
- Node.js，可选，用于检查注入脚本语法

示例依赖检查：

```bash
node --check assets/paseo_tts.js
python3 - <<'PY'
from PIL import Image
print("Pillow OK")
PY
```

## 构建

```bash
ANDROID_HOME=$HOME/.local/share/android-sdk bash build.sh
```

生成的 APK：

```text
build/paseo-tts-mobile-debug.apk
```

构建脚本默认生成一个内置启动图标。如果你想使用自己的图片作为桌面图标，可以传入：

```bash
APP_ICON_SOURCE=/path/to/icon.jpg ANDROID_HOME=$HOME/.local/share/android-sdk bash build.sh
```

## 安装

```bash
adb install -r build/paseo-tts-mobile-debug.apk
```

启动应用：

```bash
adb shell am start -n com.adam.paseotts/.MainActivity
```

直接打开网页：

```bash
adb shell am start -n com.adam.paseotts/.MainActivity -d "https://example.com"
```

包名：

```text
com.adam.paseotts
```

应用显示名：

```text
天眼
```

## 使用方式

1. 打开天眼。
2. 在连接页粘贴 Paseo、ZCode 或普通网页链接，也可以使用扫码配对。
3. 页面加载后，正文或 AI 回复附近会自动出现“播放语音”按钮。
4. 点击“播放语音”播放对应段落或内容块。
5. 点击顶部“朗读”会从当前屏幕可见正文开始向下连续朗读。
6. 播放中再次点击“停止”或对应播放按钮可以停止朗读。
7. 使用星标按钮收藏当前网页，之后可从连接页快速打开。

## 目录结构

```text
AndroidManifest.xml
assets/paseo_tts.js
build.sh
res/drawable/ic_tianyan_logo.xml
src/com/adam/paseotts/MainActivity.java
```

关键文件：

- `MainActivity.java`：原生 WebView、工具栏、多标签、收藏、Android TTS、扫码入口。
- `assets/paseo_tts.js`：注入网页的按钮、内容识别、文本清洗、朗读分组和页面适配逻辑。
- `build.sh`：无 Gradle 构建脚本。

## 隐私

- 文本朗读走 Android 系统 TextToSpeech。
- 应用本身不提供云端服务，不保存音频文件。
- 收藏链接保存在 Android 本地 SharedPreferences。
- 打开的网页仍遵循对应网站自己的网络行为和隐私政策。

## 当前限制

- 这是 debug APK 构建流程，不包含 Play Store 发布配置。
- WebView 注入逻辑依赖网页 DOM 结构，目标网站大改版后可能需要重新适配。
- 系统 TTS 语音质量取决于手机安装的 TTS 引擎和语言包。
- 扫码入口目前主要按小米系统扫码器做了 intent 适配。

## License

MIT
