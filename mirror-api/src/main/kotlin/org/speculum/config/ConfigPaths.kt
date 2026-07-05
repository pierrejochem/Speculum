package org.speculum.config

import java.io.File

/**
 * Resolves the single config file shared by the mirror app and the config
 * server. Order: `-Dmirror.config=…` JVM property, then `MIRROR_CONFIG` env,
 * then `<user.home>/.magicmirror/config.json` (writable even when the app is
 * installed read-only under /opt).
 */
object ConfigPaths {
    fun configFile(): File {
        (System.getProperty("mirror.config") ?: System.getenv("MIRROR_CONFIG"))
            ?.let { return File(it) }
        val dir = File(System.getProperty("user.home"), ".speculum")
        runCatching { dir.mkdirs() }
        return File(dir, "config.json")
    }

    /**
     * Writable folder for plugins installed at runtime (the app store), next to
     * [configFile] — `<config dir>/plugins`. Kept separate from the bundled
     * plugins that ship inside a packaged install (which live read-only under the
     * app image), so downloads always land somewhere the mirror user can write.
     * Both the mirror's loader and the config server scan this dir in addition to
     * the bundled/dev one. Created on demand.
     */
    fun userPluginsDir(): File {
        val dir = File(configFile().absoluteFile.parentFile, "plugins")
        runCatching { dir.mkdirs() }
        return dir
    }
}