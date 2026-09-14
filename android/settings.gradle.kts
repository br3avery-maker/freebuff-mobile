// 仓库选择策略:
//   - 本机(中国大陆网络)直连官方 Maven 仓库会长时间阻塞, 因此优先阿里云镜像;
//   - CI(海外 runner)反过来 —— 官方源优先。
//
// 这不只是速度问题。阿里云镜像对个别构件会返回 502 或缺件, 在空缓存的新 runner 上
// 会把插件解析直接打挂(实测 com.google.devtools.ksp 的 plugin marker 就中招了);
// 而 runner 本来就直连得动官方源。两套仓库始终都在列表里, 只是先后顺序不同。
pluginManagement {
    val preferAliyunMirrors = System.getenv("CI").isNullOrBlank()

    repositories {
        if (preferAliyunMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        gradlePluginPortal()
        mavenCentral()
        if (!preferAliyunMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
    }
}

val preferAliyunMirrors = System.getenv("CI").isNullOrBlank()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (preferAliyunMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        if (!preferAliyunMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
    }
}

rootProject.name = "FreebuffMobile"
include(":app")
include(":core:model")
include(":core:ui")
include(":core:data")
include(":feature:auth")
include(":feature:chat")
include(":feature:settings")
