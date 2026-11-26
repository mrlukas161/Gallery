import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Režim Sféra: jadro (geometria, rozloženie cieľov, skladanie, XMP GPano).
// Čistý JVM modul bez Androidu, aby sa skladanie dalo skúšať na PC (testy + príkazový riadok).
// OpenCV je tu len compileOnly (desktopové triedy s rovnakým API); v appke ho dodá org.opencv:opencv.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    compileOnly(libs.opencv.desktop)
    compileOnly("org.json:json:20240303")
    testImplementation(libs.opencv.desktop)
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "4g"
    testLogging { events("failed", "skipped"); showStandardStreams = true }
}

// Zloženie reálnej relácie z mobilu na PC:
//   gradlew :spherecore:stitchCli --args="<priečinok relácie> <výstup.jpg> [šírka]"
tasks.register<JavaExec>("stitchCli") {
    group = "sfera"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.fossify.gallery.spherecore.StitchCliKt")
    maxHeapSize = "6g"
}

// Vygeneruje syntetickú reláciu pre meranie: gradlew :spherecore:genSession --args="<priečinok> [šírka] [výška]"
tasks.register<JavaExec>("genSession") {
    group = "sfera"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.fossify.gallery.spherecore.GenSession")
    maxHeapSize = "6g"
}

// Pomocné: zapíše classpath testov do súboru (na spúšťanie mimo Gradle, napr. pri meraní pamäte)
tasks.register("writeTestClasspath") {
    group = "sfera"
    doLast {
        val f = layout.buildDirectory.file("test-classpath.txt").get().asFile
        f.writeText(sourceSets["test"].runtimeClasspath.files.joinToString(File.pathSeparator) { it.absolutePath })
    }
    dependsOn("testClasses")
}
