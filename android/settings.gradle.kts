pluginManagement {
    repositories {
        // 阿里云镜像优先:本机构建/部分网络环境官方仓库不可达时保证可解析
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google {
            content {
                includeGroupByRegex("com.android.*")
                includeGroupByRegex("com.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // 本机直连 mavenCentral() / gradlePluginPortal() 会挂起, 故放在阿里云之后作为兜底:
        // 镜像命中时不会走到这里, 只有 CI(海外 runner)或镜像缺失的构件才会回退到官方源。
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 同上: 官方源仅作兜底, 镜像命中时不会被访问
        google()
        mavenCentral()
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
