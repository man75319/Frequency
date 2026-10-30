# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在本仓库中工作时提供指导。

## 交流语言

**始终使用中文与用户交流**：所有回复、解释、说明、提交信息摘要都用中文。代码、标识符、
注释风格保持与现有代码库一致（现有代码注释为英文，新增代码请沿用英文注释以保持统一）。

## 项目概况

Frequency —— 一个 Android 音乐播放器（Jetpack Compose，单模块 `:app`），内置一个单声道
波形发生器 / 合成器，称为 **Signal Lab**。许可证 GPL-3.0。

`minSdk 33`、`compileSdk` / `targetSdk 37`、Java/Kotlin 目标版本 17，使用 AGP 9 自带的
Kotlin 支持（app 模块**特意不**应用 `org.jetbrains.kotlin.android`）。

## 构建与运行

```powershell
.\gradlew assembleDebug          # debug APK
.\gradlew assembleRelease        # release 包（minify + 资源压缩，R8）
.\gradlew installDebug           # 安装到已连接设备
.\gradlew lint                   # Android Lint
.\gradlew clean
```

本项目**没有任何测试源码** —— `app/src/test` 和 `app/src/androidTest` 目录都不存在，所以尽管
`app/build.gradle` 里声明了 JUnit / Espresso / compose-ui-test，`test` 和
`connectedAndroidTest` 实际上什么都不会执行。要写第一个测试，必须先创建对应的 source set。

### release 包在本地无法启动

`MainActivity.onCreate` 会调用 `TamperDetection.isValid(this)`，校验失败时执行
`finishAndRemoveTask()`，只有 `BuildConfig.DEBUG` 为真时才跳过这个检查。`TamperDetection`
把 APK **当前签名者**的证书摘要（不是 `signingCertificateHistory`）与异或掩码存储的期望摘要
比对，并按 SDK 版本选择用哪一个：API ≥ 37 只接受 ML-DSA-87 后量子密钥，低于 37 只接受原始
密钥。因此本地重新签名的 release APK 启动时会直接静默退出。在设备上调试请用 `assembleDebug`。

正式 release APK 采用 APK Signature Scheme v3.2 混合签名（原始密钥 + EC P-384 + ML-DSA-87），
证书摘要和校验和见 README。

## 命名历史

项目早于 Frequency 这次品牌更名，所以有三套名字并存，且**不能互换**：

- Gradle 根项目名 —— `Tunes`（`settings.gradle`）
- applicationId / namespace —— `com.jegly.frequency`
- 必须保留原名的历史标识符 —— `TunesApp`（`Application` 子类）、`TunesTheme`（Compose 主题）、
  `Theme.Tunes`（XML 主题）、`TunesSettings`（SharedPreferences 文件名），以及 `TMS`
  （音乐播放用的 `MediaBrowserService`）

`app/build.gradle` 把 `**/*.java` 从 main source set 中排除；代码库是纯 Kotlin 的，仅剩的一个
XML 布局是桌面小组件（`widget_frequency.xml`），其余界面全部是 Compose。

## 架构

### 两套互不相干的音频引擎，两个 Service

音乐播放器和波形发生器除了共用 UI 外壳之外没有任何共享，**不要**把其中一套接到另一套上。

**音乐播放** —— `TMS`（一个 `MediaBrowserService`，配合 `Playback` / `LocalPlayback` /
`PlaybackManager` 和 `MediaSession`）。`LocalPlayback` 封装 `MediaPlayer`，并额外保留第二个
`nextMediaPlayer` 用于无缝衔接（gapless）和淡入淡出（crossfade），同时为每个 audio session
挂一个 `LoudnessEnhancer` 实现音量增益。它以静态字段 `LocalPlayback.audioSessionId` 暴露会话
id，供 `SettingsScreen` 唤起系统均衡器面板。`TMS.onGetRoot` 在"生物识别锁"开启时对进程外的
客户端返回 null，这会有意切断 Android Auto / 蓝牙 / 语音助手的媒体浏览。

**Signal Lab** —— `ToneService`（一个普通前台 `Service`），持有唯一的静态 `ToneGenerator`。
`ToneGenerator` 自己起一个 `Thread`，把浮点 PCM 写入 `AudioTrack`（44.1 kHz 立体声、
`ENCODING_PCM_FLOAT`、1024 采样一块），所有合成都是手写的：各种波形、binaural / mix / sweep
模式、isochronic 门控、白噪声 / 粉噪声（Paul Kellet 算法）/ 棕噪声、一个状态变量低通滤波器，
以及 ADSR 包络。每个实时参数都是 `@Volatile` 字段，在每块音频开头重新读取；音频线程内不做
任何内存分配，也不加锁。

### Signal Lab 的控制流

`ToneGeneratorViewModel` 以 `StateFlow` 持有 UI 状态，并且**只通过 `Intent`** 与 `ToneService`
通信，不用 binder，也不持有 service 的直接引用：

```
FrequencyScreen → ToneGeneratorViewModel → Intent(ACTION_SET_*) → ToneService
                                                                → ToneGenerator setter (@Volatile)
```

因此新增一个合成参数，需要在五处同步改动：

1. `ToneGenerator` 中的一个 `@Volatile` 字段、一个 `start()` 参数和一个 setter
2. `ToneService` 中成对的 `ACTION_*` / `EXTRA_*` 常量，以及 `onStartCommand` 里的分支
3. `ToneGeneratorViewModel` 中的 `StateFlow` + setter，以及
   `startServiceWithCurrentParams()` 里对应的 `putExtra`
4. `ToneSessionParams` 中的字段（这是预设保存和导出所用的完整会话快照）
5. `ToneExporter` 中对应的合成分支

`ToneExporter` 是对 `ToneGenerator` 合成数学逻辑的**有意重复**，用于离线渲染 WAV，这样导出就
绝不会影响实时播放线程 —— 代价是改合成逻辑时必须两边一起改，否则导出的 WAV 会和用户实际听到
的声音不一致。导出先写临时文件，再通过 `MediaStore` 发布到 `Music/Frequency/`
（`ToneExportManager`）。

界面上的示波器通过 `ToneService.generator.fillScopeSnapshot(...)` 读取数据，那是一个无锁环形
缓冲区，保存最近 512 个单声道采样 —— 直接从音频线程读取，不保证严格一致（best-effort）。

该 ViewModel 的作用域挂在 Activity 上（`viewModel(LocalContext.current as ComponentActivity)`），
所以 Signal Lab 的状态能在页面切换后保留；`init` 中若发现 service 已在运行，会从
`ToneService.generator` 重新同步状态。

### 媒体库、状态与持久化

`MainViewModel`（每个 Activity 一个）是播放器 UI 的唯一数据来源。它以 `MediaBrowser` /
`MediaController` 客户端的身份连接 `TMS`，并转发两个进程级单例：`TrackManager.instance`
（播放队列、随机、循环、当前曲目）和 `PlaylistManager.getInstance()`（歌单）。队列真正的持有者
是这两个单例，而不是 ViewModel —— 界面读取的 `viewModel.currentTrack` / `activeQueue` 实际上
是委托给 `TrackManager` 的。

媒体库内容来自 `MediaStore`，由 `loaders/` 下的 `TrackFetcherFromStorage`、`AlbumFetcher`、
`ArtistFetcher` 读取。歌单、收藏、导入的曲目、播放历史和自定义音调预设都是手写 JSON，存放在
`context.filesDir`（`PlaylistStorageManager`、`CustomPresetStorageManager`）—— 没有用
Room / DataStore，也没有用任何序列化库，所以 ProGuard 规则里保留了 `model/**`。

所有标量类型的偏好设置统一走 `AppSettings`，它是单个 `object`，底层只用一个
SharedPreferences 文件。

### 导航

路由定义在 `MainActivity.MainContainer` 的 `NavHost` 中（`library`、`playlists`、`settings`、
`frequency`、`now_playing`、`playlist/{playlistId}/{playlistName}`），底部用的是自定义悬浮导航栏
而非 `NavigationBar`。`MainActivity` 还会在合成 `MainContainer` 之前，先对
`READ_MEDIA_AUDIO` 权限、以及（若开启）生物识别认证做门禁。`MainActivity` 是 `singleTask`，
并在 `onCreate` 和 `onNewIntent` 两处都处理 `ACTION_VIEW` 音频意图。

### 主题

`TunesTheme` 在运行时根据 `themeMode`（`system` | `light` | `catppuccin` | `dracula` | `ptyxis`）
构建完整的 Material 3 `ColorScheme` —— 只有 `system` 这一个分支使用动态取色。各套配色都是代码
生成的，不走资源文件：

- `CatppuccinPalette` / `buildCatppuccinColorScheme` —— 4 种 flavor × 14 种强调色，secondary
  和 tertiary 按色相邻近度推导（开启 `monochromeAccents` 时三者收敛为同一色）
- `DraculaPalette` / `buildDraculaColorScheme` —— 明暗两套，7 种强调色
- `Ptyxis.kt`（11 套精选）+ `PtyxisPalettesExtended.kt`（另外 33 套，ANSI 色槽逐字照搬，映射
  关系为 blue→primary、magenta→secondary、cyan→tertiary、red→error）—— 全部是深色方案。
  `PtyxisUnified.kt` 把两个枚举摊平成单一的选择列表，并跨两者解析 key，解析失败时回退到 Nord。

字体同理：`Type.kt` 中内置 13 套字族，按 key 选择后通过 `buildAppTypography(...)` 注入。XML 的
`Theme.Tunes` 和 `values/colors.xml` 只服务于启动画面和小组件；应用内的配色全部来自 Compose。

`ThemeManager` 基本是已废弃的历史遗留（按时段自动深色）；真正生效的主题状态是从 `AppSettings`
经 `MainViewModel` 流转的。

## 依赖管理约定

Dependabot（`.github/dependabot.yml`）把 Kotlin/KSP、AndroidX、AGP 的更新分组。根 `build.gradle`
里 `buildscript.dependencies.constraints` 块固定的是被 Dependabot 标记的**构建工具**传递依赖
（netty、bouncycastle、jdom2、jose4j）；它们都不会打进 APK，所以不要把它们挪到 app 模块的
`dependencies` 里。

Glide 用 KSP（而非 kapt）跑注解处理器 —— 正是这个选择让 app 模块可以只依赖 AGP 自带的 Kotlin
支持。`appcompat` 必须保持 ≥ 1.7.1：navigation-compose 2.9+ 需要 `AppCompatActivity` 提供的
`NavigationEventDispatcherOwner`，否则 `NavHost` 会在启动时崩溃。
