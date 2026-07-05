package org.speculum.configserver

import kotlinx.serialization.Serializable
import org.speculum.config.ConfigPaths
import org.speculum.config.ModuleConfig
import org.speculum.core.ModuleFactory
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/** A module the UI can add, with its suggested default placement/options. */
@Serializable
data class AvailableModule(
    val name: String,
    val order: Int,
    val defaultConfig: ModuleConfig?,
)

/**
 * Discovers installable modules by scanning the `plugins/` folder for JARs and
 * loading their [ModuleFactory] services — same mechanism the app uses. Lets
 * the admin UI offer the real set of modules with sensible defaults.
 */
fun scanAvailableModules(): List<AvailableModule> {
    val jars = pluginScanDirs()
        .flatMap { it.listFiles { f -> f.isFile && f.extension == "jar" }?.toList().orEmpty() }
    if (jars.isEmpty()) return emptyList()

    val loader = URLClassLoader(
        jars.map { it.toURI().toURL() }.toTypedArray(),
        ModuleFactory::class.java.classLoader
    )
    return runCatching {
        ServiceLoader.load(ModuleFactory::class.java, loader)
            .map { AvailableModule(it.name, it.order, it.defaultConfig()) }
            .distinctBy { it.name }
            .sortedBy { it.order }
    }.getOrDefault(emptyList())
}

/**
 * All folders scanned for module JARs: the bundled/dev dir (`MIRROR_PLUGINS`
 * env, else the packaged resources dir, else `./plugins`) plus the writable
 * user dir `~/.speculum/plugins` where the app store installs downloads. Kept in
 * sync with the mirror's own loader so an installed JAR shows up here at once.
 */
private fun pluginScanDirs(): List<File> {
    val bundled = System.getenv("MIRROR_PLUGINS")?.let { File(it).takeIf { d -> d.isDirectory } }
        ?: System.getProperty("compose.application.resources.dir")
            ?.let { File(it, "plugins") }?.takeIf { it.isDirectory }
        ?: File("plugins").takeIf { it.isDirectory }
    return listOfNotNull(bundled, ConfigPaths.userPluginsDir())
        .filter { it.isDirectory }
        .distinctBy { it.absoluteFile }
}