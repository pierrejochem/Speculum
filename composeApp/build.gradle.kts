import org.jetbrains.compose.desktop.application.dsl.TargetFormat
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
    implementation(project(":mirror-api"))
    implementation(project(":config-server")) // embed the web admin in-process
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.contentnegotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.zxing.core) // QR encoding (provided to the qr-module plugin at runtime)
}

// Single source of the app version: the package version, and (via a system
// property) what the update-notifier module compares against the latest release.
// The value lives in gradle.properties so the Prepare-release workflow can bump
// it (and :config-server shares it); falls back to a dev placeholder locally.
val appVersion = providers.gradleProperty("speculum.version").getOrElse("0.0.0")

// Module JARs are bundled into the package here; at runtime the app finds them
// via `compose.application.resources.dir` (see PluginLoader).
val packagedPluginsDir = layout.projectDirectory.dir("app-resources/common/plugins")

compose.desktop {
    application {
        mainClass = "org.speculum.MainKt"

        nativeDistributions {
            // Release .deb/.rpm are built by wrapping `createDistributable` with
            // nfpm (packaging/nfpm/nfpm.yaml) — it adds the /usr/bin symlink,
            // systemd unit and maintainer scripts that jpackage's installer
            // can't. AppImage is enough for the app-image; Deb/Rpm stay listed
            // so `./gradlew packageDeb` still works for ad-hoc local builds
            // (build ON the target arch — jpackage cannot cross-compile).
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "speculum"
            packageVersion = appVersion
            description = "Speculum — modular smart-mirror dashboard"
            vendor = "Speculum"

            // Bundle a full JRE so the Pi needs no separate Java install.
            includeAllModules = true

            // Low-memory tuning for small devices (e.g. Raspberry Pi, 1 GB RAM):
            // cap the heap and use the low-overhead serial GC. Live heap is ~45 MB,
            // so 160 MB is ample and keeps RSS well under the default ¼-of-RAM.
            // `speculum.version` lets the update-notifier module read the running
            // version without any config (the single source is `appVersion`).
            jvmArgs += listOf(
                "-Xmx160m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=96m",
                "-Dspeculum.version=$appVersion",
            )

            // Ship the module plugin JARs inside the package; exposed at runtime
            // under <resources.dir>/plugins.
            appResourcesRootDir.set(layout.projectDirectory.dir("app-resources"))

            linux {
                packageName = "speculum"
                menuGroup = "Speculum"
                appCategory = "Utility"
                iconFile.set(layout.projectDirectory.file("icons/speculum.png"))
            }
        }
    }
}

// Build + deploy external module JARs into the `plugins/` folder before running,
// and run from the project root so the app finds that `plugins/` folder.
tasks.matching { it.name == "run" }.configureEach {
    dependsOn(":deployModules")
    // The update-notifier detects dev runs by the absence of the jpackage
    // resources dir, so no version property is needed here.
    (this as? JavaExec)?.workingDir = rootProject.projectDir
}

// Copy the built module JARs into the package's app-resources so they ship
// inside the .deb. The packaging tasks depend on this.
val bundlePlugins = tasks.register<Copy>("bundlePlugins") {
    dependsOn(":deployModules")
    from(rootProject.layout.projectDirectory.dir("plugins")) { include("*.jar") }
    into(packagedPluginsDir)
}

// Bundle the built web admin UI (run `npm run build` in config-server/web first)
// so the embedded server can serve it from the packaged resources.
val bundleWeb = tasks.register<Copy>("bundleWeb") {
    from(project(":config-server").projectDir.resolve("web/dist"))
    into(layout.projectDirectory.dir("app-resources/common/web"))
}

// The kiosk now runs as a desktop-session autostart (see speculum-kiosk-enable),
// not a bundled systemd unit. The packages still install the in-app updater unit
// (speculum-update.service) directly — nfpm for .deb/.rpm and the PKGBUILD for
// Arch — from packaging/systemd/.

// Bundle the pinned signing public key (single source: repo-root KEYS) so the
// in-app updater can verify release signatures (app-side check). The privileged
// helper uses its own root-owned copy at /opt/speculum/share.
val bundleSigningKey = tasks.register<Copy>("bundleSigningKey") {
    from(rootProject.layout.projectDirectory.file("KEYS"))
    into(layout.projectDirectory.dir("app-resources/common"))
    rename { "speculum-signing-key.asc" }
}

tasks.matching { it.name == "prepareAppResources" }
    .configureEach { dependsOn(bundlePlugins, bundleWeb, bundleSigningKey) }

compose.resources {
    publicResClass = true
    packageOfResClass = "org.speculum.resources"
    generateResClass = always
}