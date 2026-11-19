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
- 不做 WakeUp 分享口令导入；界面上也不要出现「WakeUp」字样（CSV 导入照旧，叫「Excel / CSV 导入」）。`TourAndUpdaterTest.noWakeUpWording` 会查。
- 教务导入页不默认任何学校（包括东北大学）：打开时搜索框为空并聚焦，东大只在搜到时出现；用户选过一次后才在「上次用的」显示（SharedPreferences `jwimport` 的 `last_*`）。
- 东大（金智）教务导入已在真实账号验证通过，改其他教务时不要动它的逻辑。
- 用户自己在侧分支修的东西（如 `usage-fix` 抖音时长统计），合并时不改其逻辑。
- 新 UI 文案六种语言都要补。

## 教务导入现状
- 真实登录验证：仅东大金智。其他全部只有构造的样例页面 / JSON 单测（`JwTest`、`JwAdaptersTest`，样例在 `app/src/test/resources/jw/`），没有真实账号测过。对外说明时如实讲，不夸大。
- 专用解析：正方（JSON 两个接口 + 页面网格/列表 + 旧版）、强智、新 URP（接口 + 页面）、旧 URP（选课列表）、树维 eams、青果、超星（sdpkkbList 接口）、南软（tb_kcb）。乘方、优慕课仍标「实验性」，只靠通用表格识别；南软也仍标实验性。金智（东大）的解析和 `Jw.parse` 里 wisedu/neu 的分支保持原样，不要动。
- 解析在 `Jw.kt`（原有）和 `JwAdapters.kt`（jw-adapters 分支移植的），抓取脚本在 `JwScript.kt` 的 `jwScript`（导入界面在 `WebImport.kt`）。
- 树维 eams：「导入课表」不要求用户打开课表页（WebView 里旧版首页常是没菜单的空架子），脚本自己 GET `courseTableForStd.action` 取 ids / 学期 id，再 POST `courseTableForStd!courseTable.action`（ignoreHead=1、setting.kind=std、project.id=1、semester.id、ids）；电子科大读不到学期 id 时按日期推（483 起每学期 +20）。`EamsFetchTest` 用 node 模拟页面跑真正的脚本（样例在 `test/resources/jw/eams_uestc/`），冒烟测试用 `tools/fake_eams.py` 在模拟器上端到端跑一遍。都是手写样例，电子科大真实账号没验证过。
- 所有教务类型都是「登录后直接点导入」：取数脚本在 `JwScript.kt`（`jwScript`），按类型直接请求课表接口（正方 cxXsKb → 新正方 V9 cxXsgrkb?gnmkdm=N2151 带 kzlx=ck/xsdm → cxXsgrkb?gnmkdm=N253508；不在课表页时先取 xskbcx_cxXskbcxIndex.html 读默认学年学期；强智 xskb_list.do；新 URP thisSemesterCurriculum callback；旧 URP xkAction.do?actionType=6；超星 queryKbForXsd → sdpkkbList；青果新版 wsxk 接口 / 旧版 Pri_StuSel_rpt；树维见下），请求不到再读当前页面和里面的 iframe/frame。金智（东大）的请求和参数没改，只加了步骤上报和超时。
- 导入的可靠性：每个请求 20 秒超时；整次导入 90 秒看门狗；页面还在跳转（onPageStarted 到 onPageFinished，比如 WebVPN 重定向）时点导入会等加载完再跑；每次导入带编号，过期结果丢掉；连点只算一次。失败分「登录未完成 / 没找到课表 / 解析为空 / 请求失败 / 超时」，有「重试」和「复制诊断信息」（`JwOutcome.diagText`：版本、类型、主机名、步骤、HTTP 状态、接口路径，不含参数、Cookie、学号）。东大以前「卡住」最可能的原因：在页面跳转时注入脚本被丢掉，按钮一直转圈——现在有等待和超时，但没有在东大真实账号上复现确认。
- `JwFetchTest`（node 跑真脚本 + `test/resources/jw/fetch/harness.js` 假接口）覆盖各类型取数路径、会话过期、接口卡死超时、frame 里的课表。全是手写样例。
- 学校列表来源 m = 留白手动核对（`tools/build_schools.py` 的 MANUAL）：中国矿业大学 http://jwxt.cumt.edu.cn/jwglxt/（新正方，只能校园网或学校 aTrust VPN，用教务系统密码；WebVPN 已停用）。http 网址以前因为没开 cleartext 在内置浏览器里根本打不开。
- 内置浏览器设置在 `JwWebView.kt`：UA 去掉「; wv」「Version/4.0」（像普通手机 Chrome），网页上方「电脑版」开关换桌面 UA；window.open/新窗口放回同一页；alert/confirm/prompt 弹真对话框；第三方 Cookie、DOM Storage 开。清单里开了 `usesCleartextTraffic`（不少学校教务是 http）。
- 已知问题：电子科大统一认证（idas，金智 authserver）二次认证页 `reAuthCheck/reAuthLoginView.do` 在内置浏览器里空白（用户朋友反馈，手机浏览器正常）。已按上面做了通用修复，但没有真实账号，没法确认修好；如果仍空白，先让用户试「电脑版」。
- 学校搜索：`assets/schools.tsv`（学校\t类型\t网址\t来源\t入口），由 `tools/build_schools.py` 从 shiguang_warehouse（来源 s，社区为该校写过适配）和 baoozak/timetable（来源 b，类型多为猜测，按网址特征修正）生成；`Schools.kt` 搜索，导入页搜索框输入学校名即出结果，点了填网址和类型。手动选类型、输网址照旧；网页页面上方的类型名可点开换类型。列表网址/类型都没验证过，界面上有提示。
- 第三方参考只用 MIT / Apache-2.0 代码并保留署名（THIRD_PARTY_NOTICES.md，以及 `Jw.kt`/`JwAdapters.kt` 文件头）；不要复制 GPL 项目代码（Dawn-Course、sleepy 等）。
- 管控按"本周有课"过滤，依赖开学日期和课程周数；开学日期不从教务读取，导入后需用户确认。

## 时长统计
- `UsageTally` 按「包名/界面类名」跟踪前台界面，应用所有界面都离开才停表（安卓事件顺序是 PAUSED 旧→RESUMED 新→STOPPED 旧，不能在同包 STOPPED 时停表）。有单测 `UsageTallyTest`。

## 工作习惯
- 声称完成前先让 CI 绿、看冒烟截图。
- 没验证过的功能不说已完成。
