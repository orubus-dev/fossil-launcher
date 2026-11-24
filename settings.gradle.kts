pluginManagement {
    repositories {
        google()
        maven { setUrl("https://repo.huaweicloud.com/repository/maven") }
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { setUrl("https://repo.huaweicloud.com/repository/maven") }
        maven { setUrl("https://www.jitpack.io") }
        mavenCentral()
        mavenLocal()
    }
}
include(":app")
