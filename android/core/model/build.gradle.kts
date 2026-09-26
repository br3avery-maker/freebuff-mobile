import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 版本号唯一来源与 app 模块相同:android/version.properties。
// 这里把它生成为一个 Kotlin 常量(LATEST_VERSION),所以「改 build.gradle 忘了改常量」
// 这类漂移在构造上不可能发生 —— 发版只改 version.properties。
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    check(f.exists()) { "缺少 android/version.properties(版本号唯一来源)" }
    f.inputStream().use { load(it) }
}
val appVersionName = versionProps.getProperty("versionName").orEmpty().trim()
check(Regex("\\d+\\.\\d+\\.\\d+").matches(appVersionName)) {
    "version.properties 的 versionName 必须是 x.y.z 形态,当前: '$appVersionName'"
}

val generatedVersionDir = layout.buildDirectory.dir("generated/version/kotlin")

val generateVersionSource by tasks.registering {
    val outDir = generatedVersionDir
    val ver = appVersionName
    inputs.property("versionName", ver)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/freebuff/core/model/BuildVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("package com.freebuff.core.model")
                appendLine()
                appendLine("/**")
                appendLine(" * 本包版本号(应用内「检查更新」与设置页版本的基准)。")
                appendLine(" * 由 android/version.properties 在构建时生成 —— 请勿手改,发版只改那个文件。")
                appendLine(" */")
                appendLine("const val LATEST_VERSION = \"$ver\"")
            },
        )
    }
}

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir(generateVersionSource)
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(libs.junit)
}
