# 构建与打包 APK

本文档记录 Freebuff Mobile(Android 原生工程)的构建、打包、签名与安装流程。所有命令均在本机验证通过。

## 1. 工具链

工具链已随工程放置在 `android/.toolchain/`(体积大,不入库):

| 组件 | 版本 | 路径 |
|---|---|---|
| JDK | 17.0.20.1+1 | `.toolchain/jdk-17.0.20.1+1` |
| Android SDK | platform-35 / build-tools 35.0.0 | `.toolchain/sdk` |
| Gradle | 8.9 | `.toolchain/gradle-8.9` |

**本机系统 `JAVA_HOME` 是 JDK 8,而 AGP 8.5 要求 JVM 11+** —— 直接用 `gradle` / `./gradlew` 会卡在
`checkDebugAarMetadata` 并报类路径解析失败。因此本机构建一律通过
[`scripts/gradle.sh`](../scripts/gradle.sh) 启动,它会把 Java 固定到 `.toolchain` 里的 JDK 17 再调用 Gradle:

```bash
scripts/gradle.sh :app:assembleDebug
```

> 这个 JDK 路径**不能**写进 `gradle.properties` 的 `org.gradle.java.home`。那样虽然在本机能跑,但绝对路径
> 是机器专属的,入库后会让 GitHub Actions(ubuntu)因找不到目录而直接构建失败。
> 若要用自己的 JDK 17 或 Gradle,设 `FREE_BUFF_JDK` / `FREE_BUFF_GRADLE` 覆盖。

依赖仓库在 `settings.gradle.kts` 中**按环境切换优先级**(两套仓库都在列表里, 只是先后不同):

| 环境 | 顺序 | 理由 |
|---|---|---|
| 本机(`CI` 未设) | 阿里云镜像 → 官方源 | 本机直连 mavenCentral / gradlePluginPortal 会长时间阻塞 |
| CI(`CI=true`) | 官方源 → 阿里云镜像 | 镜像对个别构件会返回 502 / 缺件, 空缓存的新 runner 上会把插件解析打挂; runner 本身直连官方源正常 |

## 2. 打包命令

```bash
cd android

# A. Debug 包 —— 日常调试,含 Compose 预览工具,体积大,启动快可断点
scripts/gradle.sh :app:assembleDebug

# B. Release 包 —— R8 代码压缩 + 资源收缩,发布/体验用
scripts/gradle.sh :app:assembleRelease

# C. 两者一起,并跑单元测试
scripts/gradle.sh :app:assembleDebug :app:assembleRelease \
  :core:model:test :core:data:testDebugUnitTest
```

首次构建需要下载依赖(约 900 MB 缓存),耗时较长;之后增量构建通常在 2~6 分钟。构建日志建议重定向到文件,便于失败后排查:

```bash
scripts/gradle.sh :app:assembleRelease --console=plain > rel-log.txt 2>&1
```

在 CI / 已有 JDK 17 的环境(如 `JAVA_HOME` 已指向 17、或 `actions/setup-java` 装好 JDK)可直接用 wrapper:

```bash
./gradlew :app:assembleRelease
```

## 3. 产物

| 变体 | 路径 | 大小 | 说明 |
|---|---|---|---|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | ~18 MB | `debuggable`,可直接安装体验 |
| Release | `app/build/outputs/apk/release/app-release.apk` | ~1.4 MB | R8 压缩 + 资源收缩,minSdk 26 |

打包完成后可归集到 `android/dist/`(该目录已在 `.gitignore` 中忽略):

```bash
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk   dist/FreebuffMobile-0.2.1-debug.apk
cp app/build/outputs/apk/release/app-release.apk dist/FreebuffMobile-0.2.1-release.apk
```

`dist/` 中的文件名带版本号 —— 发布新版本时记得跟着 `versionName` 一起改。

## 4. 签名:两种模式自动切换

`app/build.gradle.kts` 会根据 `android/keystore.properties` **是否存在**自动选择签名配置:

| `android/keystore.properties` | release 使用的签名 | 能否上架 |
|---|---|---|
| 不存在 | 回退 Android 调试证书 | ❌ 仅本地安装体验 |
| 存在且字段完整 | 你的正式 keystore | ✅ |
| 存在但缺字段 | **构建直接失败并报出缺失字段名** | — |

第三种情况故意做成硬失败:否则你会在「以为用的是正式证书」的情况下拿到一个 debug 签名的包。

无需任何手动改代码,也不用改 CI 脚本 —— 想在本地验证正式签名流程时,只需把 `keystore.properties` 放进来。

### 生成正式 keystore(推荐用脚本)

```bash
cd android
scripts/gen-release-keystore.sh          # 已存在 keystore 时会拒绝覆盖,除非加 --force
```

脚本会交互式询问口令(不落 shell history),生成 `android/freebuff-release.jks`,并写好 `android/keystore.properties`。两者都已在 `.gitignore` 中。

也可以手工执行等价步骤:

```bash
.toolchain/jdk-17.0.20.1+1/bin/keytool.exe -genkeypair -v \
  -keystore freebuff-release.jks -keyalg RSA -keysize 2048 -validity 10000 \
  -alias freebuff

cp keystore.properties.example keystore.properties   # 然后填入真实口令
```

`keystore.properties` 格式(`storeFile` 相对 `app/` 模块解析,所以 `../` 指向 `android/`):

```properties
storeFile=../freebuff-release.jks
storePassword=...
keyAlias=freebuff
keyPassword=...
```

> ⚠️ **务必备份 keystore 与口令。** 丢失后无法再对已发布应用做覆盖升级,只能换包名重新发布。同理,keystore 一旦被替换,签名不同的新包无法覆盖安装旧包(设备上需先卸载)。

### 查看当前实际生效的签名

不需要打包就能确认,几十秒即可:

```bash
scripts/gradle.sh :app:signingReport
```

输出中关注 `Variant: release` 的 `Config:` 行 —— 是 `release` 还是 `debug`,以及 `Store:` 指向哪个 keystore。

## 5. 安装与验证

```bash
# 安装到已连接的设备/模拟器
.toolchain/sdk/platform-tools/adb.exe install -r dist/FreebuffMobile-0.2.1-release.apk

# 校验签名与包信息
.toolchain/sdk/build-tools/35.0.0/apksigner.bat verify -v app/build/outputs/apk/release/app-release.apk
.toolchain/sdk/build-tools/35.0.0/aapt2.exe dump badging app/build/outputs/apk/release/app-release.apk
```

预期包信息:`package name='com.freebuff.mobile' versionCode='3' versionName='0.2.1'`,minSdk 26 / targetSdk 35。

## 6. R8 混淆注意事项

`app/proguard-rules.pro` 当前**无需额外 keep 规则**:JSON 解析使用 Android 内置 `org.json`,未引入 Retrofit / Gson / kotlinx-serialization 的反射序列化;Compose 与 Hilt 自带 consumer rules 会被自动合并。

若后续引入反射型序列化(如给数据类加 `@Serializable` 或使用 Gson 的 `@SerializedName`),必须补充对应 keep 规则,否则 release 包会在运行期出现字段丢失或类找不到 —— 这类问题只在 release 变体暴露,debug 包不会复现。

同理,任何依赖反射、依赖类名/方法名字符串的代码都需要 keep 规则。发布前建议对 release 包做一次真机冒烟测试。

## 7. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 卡在 `checkDebugAarMetadata` 后失败 | 未使用 JDK 17。请用 `scripts/gradle.sh` 而不是直接跑 `gradle` / `./gradlew` |
| 构建报 `keystore.properties 缺少必要字段` | 该文件存在但不完整。补齐四个字段,或直接删掉它以回退 debug 签名 |
| 想确认 release 到底用了哪个证书 | 跑 `:app:signingReport`,看 `Variant: release` 的 `Config:` / `Store:` 两行 |
| 依赖下载长时间无进展 | 直连 Maven 仓库被阻塞。确认 `settings.gradle.kts` 使用的是阿里云镜像 |
| release 包安装后崩溃、debug 正常 | R8 裁掉了反射所需成员。按第 6 节补充 keep 规则 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 设备上已装同包名但签名不同的应用,先卸载旧包 |
