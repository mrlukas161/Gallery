pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
}

rootProject.name = "Gallery"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":app")
// jadro režimu Sféra; leží pod app/, lebo lokálne klony sú riedke (sparse) len na app, gradle, .github
include(":spherecore")
project(":spherecore").projectDir = file("app/spherecore")
