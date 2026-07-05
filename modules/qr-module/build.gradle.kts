import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Provided by the host app at runtime (shared via the parent classloader).
    compileOnly(project(":mirror-api"))
    compileOnly(libs.compose.runtime)
    compileOnly(libs.compose.foundation)
    compileOnly(libs.compose.material3)
    compileOnly(libs.compose.ui)
    compileOnly(libs.zxing.core)
}

val deployToModules = tasks.register<Copy>("deployToModules") {
    from(tasks.named("jar"))
    into(rootProject.layout.projectDirectory.dir("plugins"))
}