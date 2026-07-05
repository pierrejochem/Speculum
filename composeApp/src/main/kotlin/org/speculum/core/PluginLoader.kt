package org.speculum.core

import org.speculum.config.ConfigPaths
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/**
 * Loads every `*.jar` in the plugin folders into a child classloader
 * (parent = the app loader, so shared API + Compose classes resolve to the
 * same types), then finds [ModuleFactory] implementations via the JDK
 * [ServiceLoader] (reflection over `META-INF/services`).
 *
 * Scans the union of two locations: the bundled/dev folder (the packaged app's
 * `compose.application.resources.dir/plugins`, else `./plugins` for dev runs)
 * and the writable user folder `~/.speculum/plugins` where the app store
 * installs downloaded modules. This runs once at boot, so a freshly installed
 * plugin only renders after a restart.
 */
fun discoverPluginFactories(): List<ModuleFactory> {
    val jars = pluginDirs()
        .flatMap { it.listFiles { f -> f.isFile && f.extension == "jar" }?.toList().orEmpty() }
    if (jars.isEmpty()) return emptyList()

    val loader = URLClassLoader(
        jars.map { it.toURI().toURL() }.toTypedArray(),
        ModuleFactory::class.java.classLoader
    )
    return runCatching {
        ServiceLoader.load(ModuleFactory::class.java, loader).toList()
    }.onFailure { println("[plugins] failed to load module JARs: $it") }
        .getOrDefault(emptyList())
}

private fun pluginDirs(): List<File> {
    val bundled = System.getProperty("compose.application.resources.dir")
        ?.let { File(it, "plugins") }
        ?.takeIf { it.isDirectory }
        ?: File("plugins").takeIf { it.isDirectory }
    return listOfNotNull(bundled, ConfigPaths.userPluginsDir())
        .filter { it.isDirectory }
        .distinctBy { it.absoluteFile }
}