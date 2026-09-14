# Freebuff Mobile

为 [CodebuffAI/freebuff](https://github.com/CodebuffAI/freebuff) 规划的**安卓手机客户端**,包含两部分交付物:

| 目录 | 交付物 | 说明 |
|---|---|---|
| `freebuff-android-prototype/` | **HTML 概念原型** | 零依赖静态页(`index.html` + 原生 CSS/JS),用来快速对齐信息架构、交互与视觉,是全套设计的基准 |
| `android/` | **原生 Android App** | Kotlin + Jetpack Compose 多模块工程,已接入真实后端接口,可打包装机 |

> 本项目是**非官方**的移动端实现,设计语言取自官方品牌(深色 zinc 底 + lime 强调 + 终端白标)。与官方仓库无隶属关系。

## 快速开始

### 概念原型(HTML)

需要 Node.js(仅用于起一个静态服务,页面本身零依赖):

```bash
cd freebuff-android-prototype
node dev-server.js 4187
# 打开 http://127.0.0.1:4187/index.html
```

桌面宽度下会自动以 393px 手机视口居中展示。原型的屏幕与交互清单见
[`freebuff-android-prototype/README.md`](freebuff-android-prototype/README.md)。

### 原生 Android App

```bash
cd android

# Debug 包(可直接装机体验)
scripts/gradle.sh :app:assembleDebug

# Release 包(R8 压缩 + 资源收缩, ~1.4 MB)
scripts/gradle.sh :app:assembleRelease

# 单元测试(纯 JVM + MockWebServer)
scripts/gradle.sh :core:model:test :core:data:testDebugUnitTest
```

产物在 `app/build/outputs/apk/<变体>/`。也可以用 `./gradlew`(需自备 JDK 17 并设好 `JAVA_HOME`)。

工具链、签名、R8 注意事项与排错见 **[`android/docs/build-and-release.md`](android/docs/build-and-release.md)**;
后端接口契约与失败回退矩阵见 **[`android/docs/backend-integration.md`](android/docs/backend-integration.md)**。

## 工程结构

```
freebuff-android-prototype/   HTML 原型(设计基准)+ 给官方的提案与自定义模型规范
android/
  app/                        应用壳:Activity、导航、DI 装配、BuildConfig 开关
  core/model/                 纯 Kotlin 领域模型:URL 规范化、git 地址解析、数据清洗
  core/ui/                    设计令牌与主题、通用组件
  core/data/                  网络层(ApiResult/ApiError)、Room 持久化、各仓库实现
  feature/auth|chat|settings/ 欢迎页、会话与对话、设置(集成/模型/外观/关于)
  docs/                       构建发布 + 后端接入文档
  scripts/                    gradle.sh(固定 JDK 17)、gen-release-keystore.sh(生成正式签名)
```

## 功能现状

**已接真实接口**:流式对话(SSE 增量解析)、官方模型目录(`GET {网关}/v1/models`,失败回退内置目录)、
自定义模型端点探测、GitHub OAuth 设备流授权与仓库读取、版本检查更新。

**原型阶段仍在模拟**:Git 授权在未配置 client_id 时回退演示实现;版本检查在未配置 `UPDATE_URL` 时提示未配置。

三个开关在 `android/app/build.gradle.kts` 的 `buildConfigField` 中,默认留空——留空时行为与纯演示版一致,
因此开箱即可编译运行:

| 开关 | 作用 | 留空时 |
|---|---|---|
| `DEFAULT_GATEWAY_BASE_URL` | 官方模型网关根地址 | 使用内置模型目录,UI 明确提示未配置 |
| `GITHUB_OAUTH_CLIENT_ID` | GitHub OAuth App 的 client_id | Git 账号回退演示实现 |
| `UPDATE_URL` | 版本检查 JSON 地址 | 版本检查提示未配置 |

## 签名

`release` 变体会根据 `android/keystore.properties` **是否存在**自动选择签名:存在则用你的正式 keystore,
不存在则回退 debug 证书(可安装,但不可上架)。生成正式密钥:

```bash
cd android
scripts/gen-release-keystore.sh
```

## CI

[`.github/workflows/android.yml`](.github/workflows/android.yml) 在 push / PR 时跑单测、lint 与 release 打包,
并把 APK 作为构建产物上传(使用 runner 自带的 JDK 17 与 Android SDK,不依赖本机 `.toolchain/`)。
