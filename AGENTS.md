# AGENTS.md — 给接手留白的 agent

留白（Liubai）是刑君越的安卓自律 App。Kotlin + Jetpack Compose，包名 `com.liubai.guard`。动手前先读完这份。

## 发布流程
- `main` = 正式版。推到 main 会触发 `.github/workflows/build.yml`：单元测试 → 构建 → 模拟器冒烟测试（`tools/smoke.sh` 截图）→ 发布 release `build-N`，应用内更新器会推给用户。
- 新功能一律开分支，用 workflow_dispatch 跑 CI，`preview` job 发 prerelease 预览包给用户试。
- **只有用户明确说"上线 / 直接上 / 测试通过"才合入 main。** 不要自行合并。
- 预览包关闭应用内自动更新，锁定在该版本；正式版更新器保持开启。用户确认预览通过后再合入 main。
  - 做法：`build.yml` 的 build job 在非 main 分支给 gradle 传 `-Ppreview`（env `PV`），`app/build.gradle.kts` 据此设 `BuildConfig.PREVIEW=true`；`Updater.enabled()` = `!PLAY && !PREVIEW`，`latest()`/`download()`/`install()`、启动时的 `UpdatePrompt`、「关于」页检查都先看它，预览版「关于」页显示「预览版，不检查更新」。构建后会 grep `BuildConfig.java` 核对开关，单测 `TourAndUpdaterTest` 也测。
  - preview job 发的 tag 是 `preview-<分支名去掉 -redo>-<run_number>`。
- 只文档改动推 main 时提交信息加 `[skip ci]`，避免多发一个版本。

## 每个版本都要有应用内新手教程
- 首次安装：可跳过的完整引导。
- 每次更新：可跳过的"新功能"指引，按 versionCode 记录已看版本，每个版本在内容列表里加一条。
- 设置里有「新手教程」「这个版本的新功能」可重看。
- 新功能上线必须同时补它的指引条目。
- 代码在 `Tour.kt`：`Tour.FIRST` 是首次引导，`Tour.WHATS_NEW` 是按版本的新功能列表（新版本在最前面加一条 `Release(id, pages)`，id 发出去后不要改）。存储在 SharedPreferences `tour`：`code` = 上次看过的 versionCode，`seen` = 看过的条目 id，`firstPending` = 首次引导没看完。升级（versionCode 变大且有没看过的条目）才弹新功能；新装只弹首次引导、把已有条目都记成看过。
- 文案用中文当键，六种语言补在 `assets/i18n/*.json`；`TourAndUpdaterTest.newStringsTranslated` 会检查。
- 冒烟测试不被挡：`--ez seed true` 和 `--ez notour true` 都会把教程记成看过；`--es tour first|new` 直接弹出来截图。

## 用户的决定（不要推翻）
- 课表节次时间由用户自己编辑，不内置东北大学作息预设；导入只在教务数据返回时间时才写时间。
- 不做 WakeUp 分享口令导入。
- 东大（金智）教务导入已在真实账号验证通过，改其他教务时不要动它的逻辑。
- 用户自己在侧分支修的东西（如 `usage-fix` 抖音时长统计），合并时不改其逻辑。
- 新 UI 文案六种语言都要补。

## 教务导入现状
- 真实登录验证：仅东大金智。其他全部只有构造的样例页面 / JSON 单测（`JwTest`、`JwAdaptersTest`，样例在 `app/src/test/resources/jw/`），没有真实账号测过。对外说明时如实讲，不夸大。
- 专用解析：正方（JSON 两个接口 + 页面网格/列表 + 旧版）、强智、新 URP（接口 + 页面）、旧 URP（选课列表）、树维 eams、青果、超星（sdpkkbList 接口）、南软（tb_kcb）。乘方、优慕课仍标「实验性」，只靠通用表格识别；南软也仍标实验性。金智（东大）的解析和 `Jw.parse` 里 wisedu/neu 的分支保持原样，不要动。
- 解析在 `Jw.kt`（原有）和 `JwAdapters.kt`（jw-adapters 分支移植的），抓取脚本在 `WebImport.kt` 的 `jwScript`。
- 学校搜索：`assets/schools.tsv`（学校\t类型\t网址\t来源\t入口），由 `tools/build_schools.py` 从 shiguang_warehouse（来源 s，社区为该校写过适配）和 baoozak/timetable（来源 b，类型多为猜测，按网址特征修正）生成；`Schools.kt` 搜索，导入页搜索框输入学校名即出结果，点了填网址和类型。手动选类型、输网址照旧；网页页面上方的类型名可点开换类型。列表网址/类型都没验证过，界面上有提示。
- 第三方参考只用 MIT / Apache-2.0 代码并保留署名（THIRD_PARTY_NOTICES.md，以及 `Jw.kt`/`JwAdapters.kt` 文件头）；不要复制 GPL 项目代码（Dawn-Course、sleepy 等）。
- 管控按"本周有课"过滤，依赖开学日期和课程周数；开学日期不从教务读取，导入后需用户确认。

## 时长统计
- `UsageTally` 按「包名/界面类名」跟踪前台界面，应用所有界面都离开才停表（安卓事件顺序是 PAUSED 旧→RESUMED 新→STOPPED 旧，不能在同包 STOPPED 时停表）。有单测 `UsageTallyTest`。

## 工作习惯
- 声称完成前先让 CI 绿、看冒烟截图。
- 没验证过的功能不说已完成。
