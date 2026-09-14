# Freebuff Mobile(Android)

按 `freebuff-android-prototype` 的 HTML 原型一比一实现的 **官方安卓 App**。
Kotlin + Jetpack Compose + Material 3 + Hilt + Room + OkHttp,无图片资源,全部用主题色、圆角与文本拼出原型视觉。

## 运行要求
- Android Studio(建议 Ladybug 2024.2.1 或更新)+ JDK 17
- Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.20(compileSdk 35,minSdk 26)
- 首次同步需联网拉依赖;本机无 Android SDK 时可直接用 Android Studio 打开并自动安装

## 打开方式
1. Android Studio → Open → 选择本目录 `android/`
2. 等待 Gradle Sync
3. 连接设备/模拟器 → Run ▶(包名 `com.freebuff.mobile`)

## 模块结构

```
android/
├─ app/                          入口 + DI 装配(BuildConfig 配置经 @Named 注入)
│  └─ com/freebuff/app/          MainActivity · FreebuffApplication · RootViewModel ·
│                                AppNavigatorImpl · ui/FreebuffRoot · di/AppModule
├─ core/
│  ├─ model/                     纯 Kotlin 领域模型与工具(Models/Seed/Urls/Catalog/Cleaner)
│  ├─ ui/                       设计令牌(Theme)+ 通用组件 + 导航契约 AppNavigator
│  └─ data/                     数据层
│     ├─ db/                    Room(实体/DAO/数据库)
│     ├─ security/              CryptoManager(Android Keystore AES/GCM)
│     ├─ network/               ApiClient · ApiResult(错误分类)· FreebuffApi ·
│     │                         GithubApi(设备流)· ChatRepository(SSE 流式)
│     └─ repository/            Session/Settings/CustomModel/ModelCatalog/GitAuth/Update/Migrator
└─ feature/
   ├─ auth/                     欢迎页
   ├─ chat/                     会话首页 · 对话页 · 模型选择 · 发起任务向导
   └─ settings/                 设置页 · Git 账号 · 自定义模型列表/表单 · 版本更新
```

## 已实现(对应原型功能)
- **双主题**:深色默认、浅色、跟随系统;欢迎页 Logo 点击可循环切换
- **会话**:首页列表 + 新建/删除/清空;对话页**真实 SSE 流式对话**(OpenAI 兼容端点,逐段写回消息)+ 代码块 + 上下文胶囊
- **发起任务向导**:三步(仓库 → 模型 → 描述);仓库支持不关联 / 手动输入(平台快捷粘贴、git clone 解析、
  分支胶囊、协议补全)/ **Git 账号真实仓库**(GitHub 设备流);官方与自定义模型分组展示
- **设置**:外观(主题三档)、模型(当前模型 → 选择模型)、集成(Git 账号 / 仓库地址解析严格↔宽松 / 自定义模型)、
  会话与数据(数据本机说明 + **二次确认**清除全部会话)、关于(说明弹层 / 版本检查 / 官方网站 / 反馈与建议)
- **自定义模型**:添加/编辑表单(Base URL 实时预览完整请求路径、中转站直接粘贴、密钥显示切换、TLS 开关、
  请求头、**真实测试连接** → `/v1/models` 可用列表点选、快照随记录保存、从快照重建);列表行展示完整请求路径与
  「上次可用 N 个模型」快照展开区(模型行状态点:上次连通时间与延迟 + 就地重新测试连接);
  启动自动清洗损坏记录并展示「已自动修复」报告(逐条差异 + 二次确认恢复原始记录,仅会话内)
- **官方模型目录**:启动时从网关 `GET /v1/models` 实时拉取,失败或未配置时回退内置目录(模型面板可手动刷新)
- **数据**:Room 持久化 + 一次性 SharedPreferences 迁移(`freebuff_proto_v1`,带 `migrated_v2` 完成标志,
  **迁移只执行一次**——清空会话后重启不会复活演示会话,旧偏好也不会覆盖后来的修改);API Key 与 Git token 经 Keystore 加密

## 后端接入(网络层与错误处理)

完整接口/错误分类/回退策略见 **[docs/backend-integration.md](docs/backend-integration.md)**。

配置项在 `app/build.gradle.kts` 的 `buildConfigField`(留空即走回退路径,不影响编译与体验):

| 配置 | 作用 | 留空行为 |
|---|---|---|
| `DEFAULT_GATEWAY_BASE_URL` | 官方网关根地址(如 `https://api.example.com/v1`) | 官方目录使用内置列表;官方模型对话提示未配置 |
| `GITHUB_OAUTH_CLIENT_ID` | GitHub OAuth App 的 client_id | Git 账号回退演示实现(阶段协议一致) |
| `UPDATE_URL` | 版本检查 JSON 地址 | 版本检查提示未配置 |

要点:
- 所有网络调用返回统一 `ApiResult<T>`(`Ok` / `Err(ApiError)`),错误分类含超时/DNS/连接/TLS/HTTP(保留状态码)/解析/未配置
- 阻塞式请求统一经 `apiCallIo` 切到 `Dispatchers.IO`;流式对话用异步 `enqueue` + SSE 增量解析
- 失败永不静默:目录失败保留内置并提示原因、仓库读取失败可重试、更新检查区分「未配置」与「网络失败」

## 设计说明
- 设计令牌与 HTML 原型同源(见 `freebuff-android-prototype/css/app.css`),替换品牌色只需改 `core:ui` 的 `Theme.kt`
- Git 授权选择 OAuth 设备流:无需自定义 scheme 回调与后端中转服务,只需一枚 client_id
- 文案沿用原型的中文界面,便于直接进入联调

## 打包 APK

完整流程(工具链、签名、R8 注意事项、排错)见 **[docs/build-and-release.md](docs/build-and-release.md)**。

```bash
cd android

# Debug 包(日常调试, ~18 MB)
scripts/gradle.sh :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release 包(R8 压缩 + 资源收缩, ~1.4 MB)
scripts/gradle.sh :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk

# 单元测试(model + data)
scripts/gradle.sh :core:model:test :core:data:testDebugUnitTest
```

| 变体 | 大小 | 说明 |
|---|---|---|
| Debug | ~18 MB | `debuggable`,含 Compose 预览工具,可直接装机体验 |
| Release | ~1.4 MB | R8 压缩 + 资源收缩,minSdk 26 / targetSdk 35 |

两点注意:

- **签名**:有 `android/keystore.properties` 时 release 用你的正式 keystore 签名,没有时自动回退 debug 证书(仅本地安装,不可上架)。一键生成正式密钥:`scripts/gen-release-keystore.sh`。详见文档第 4 节。
- **工具链**:位于 `android/.toolchain/`(JDK 17 + SDK 35 + Gradle 8.9)。系统默认 `JAVA_HOME` 为 JDK 8,无法运行 AGP 8.5,所以本机构建统一走 `scripts/gradle.sh`(它把 Java 固定到自带 JDK 17)。仓库依赖以阿里云镜像优先,官方源仅作兜底。
