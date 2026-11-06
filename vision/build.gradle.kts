plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":chess"))
    // The Java API of OpenCV is provided at runtime by the Android AAR (app module)
    // and by the desktop build in unit tests. Both expose the same org.opencv.* classes.
    compileOnly(libs.opencv.desktop)
    testImplementation(libs.opencv.desktop)
    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "2g"
    // Optional local debugging hooks (see DebugDump).
    listOf("chesslens.debugDir", "chesslens.framesDir").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = System.getProperty("chesslens.debugDir") != null }
}
