import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 读取 android/keystore.properties(若存在)。该文件只存在于本机 / CI 密钥挂载中, 不入库。
// 存在但字段缺失时直接报错, 避免「以为是正式签名、实际用的是 debug 证书」。
val keystorePropsFile = rootProject.file("keystore.properties")
val releaseKeystoreProps: Properties? = if (keystorePropsFile.exists()) {
    Properties().apply { keystorePropsFile.inputStream().use { load(it) } }.also { props ->
        val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .filter { props.getProperty(it).isNullOrBlank() }
        check(missing.isEmpty()) {
            "keystore.properties 缺少必要字段: ${missing.joinToString()} —— 见 docs/build-and-release.md"
        }
    }
} else {
    null
}

android {
    namespace = "com.freebuff.mobile"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.freebuff.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"

        // 真实接入配置：发布时替换为生产值。
        // - DEFAULT_GATEWAY_BASE_URL: 官方模型网关根地址(留空 = 未配置, UI 明确提示)
        // - GITHUB_OAUTH_CLIENT_ID:   GitHub OAuth App 的 client_id(留空 = 演示数据回退)
        // - UPDATE_URL:               版本检查 JSON 地址(留空 = 版本检查跳过)。
        //   指向仓库 dist/update.json(raw.githubusercontent.com 直链,发版工作流自动维护):
        //   {"version":"0.0.1","notes":["..."]} —— checkForUpdate 拉取并与当前版本比较。
        buildConfigField("String", "DEFAULT_GATEWAY_BASE_URL", "\"\"")
        buildConfigField("String", "GITHUB_OAUTH_CLIENT_ID", "\"\"")
        buildConfigField("String", "UPDATE_URL", "\"https://raw.githubusercontent.com/doubao01/freebuff-mobile/main/dist/update.json\"")
    }

    // 正式签名: 若 android/keystore.properties 存在, 用自有 keystore 签 release;
    // 否则回退 debug 签名 —— 保证任何环境都能产出可安装的包, 但该包不可上架。
    // keystore.properties 与 *.jks 都已 gitignore, 口令绝不入库。
    signingConfigs {
        if (releaseKeystoreProps != null) {
            create("release") {
                storeFile = file(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseKeystoreProps != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
