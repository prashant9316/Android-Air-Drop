// Pure Kotlin wake-word engine. ONNX Runtime is compileOnly here: the Android app
// supplies onnxruntime-android, and the JVM tests use the desktop build.
plugins { id("org.jetbrains.kotlin.jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The test reads the same model files the app ships.
    systemProperty("models.dir", rootProject.file("app/src/main/assets/wakeword").absolutePath)
}
