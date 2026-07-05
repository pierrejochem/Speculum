import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Provided by the host app at runtime (shared via the parent classloader);
    // compile-only keeps the JAR thin.
    compileOnly(project(":mirror-api"))
    compileOnly(libs.compose.runtime)
    compileOnly(libs.compose.foundation)
    compileOnly(libs.compose.material3)
    compileOnly(libs.compose.ui)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.datetime)
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.ktor.client.core)
    compileOnly(libs.ktor.client.contentnegotiation)
    compileOnly(libs.ktor.serialization.json)
}

val deployToModules = tasks.register<Copy>("deployToModules") {
    from(tasks.named("jar"))
    into(rootProject.layout.projectDirectory.dir("plugins"))
}
