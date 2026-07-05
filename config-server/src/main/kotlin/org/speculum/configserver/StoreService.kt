package org.speculum.configserver

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.speculum.config.ConfigPaths
import org.speculum.config.ModuleConfig
import org.speculum.update.Downloader
import org.speculum.update.GitHubReleaseProvider
import org.speculum.update.SignatureVerifier
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A catalog entry annotated with its local state for the store UI. */
@Serializable
data class StorePluginView(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val homepage: String,
    val moduleName: String,
    val installed: Boolean,
    val enabled: Boolean,
)

/** The store listing: entries plus a reason string when the catalog couldn't be loaded. */
@Serializable
data class StoreView(
    val plugins: List<StorePluginView> = emptyList(),
    val reason: String? = null,
)

/**
 * Backs the plugin app store. Downloads module JARs from the catalog into the
 * writable user plugins dir and activates them by adding the module to the
 * shared config. Installs are serialized by a [Mutex]; a new JAR only renders on
 * the mirror after a restart (the loader scans once at boot).
 */
object StoreService {

    private val installMutex = Mutex()

    private fun userDir(): File = ConfigPaths.userPluginsDir()

    /** Fetches the catalog and annotates each entry with installed/enabled state. */
    suspend fun list(): StoreView {
        val client = GitHubReleaseProvider.defaultClient()
        val catalog = try {
            CatalogFetcher.fetch(client)
        } finally {
            client.close()
        }
        val cat = catalog.getOrElse {
            return StoreView(reason = "Couldn't load the plugin catalog (${it.message ?: "offline"}).")
        }
        val enabledNames = ConfigStore.load().modules.map { it.module }.toSet()
        val plugins = cat.plugins.map { p ->
            StorePluginView(
                id = p.id,
                name = p.name,
                description = p.description,
                author = p.author,
                homepage = p.homepage,
                moduleName = p.moduleName,
                installed = File(userDir(), p.jarName).isFile,
                enabled = p.moduleName in enabledNames,
            )
        }
        return StoreView(plugins = plugins)
    }

    /**
     * Downloads the JAR for catalog entry [id], verifies its SHA-256 when the
     * entry declares one, installs it into the user plugins dir, and enables the
     * module in config. Returns a failed Result with a user-facing message on any
     * problem.
     */
    suspend fun install(id: String): Result<Unit> = installMutex.withLock {
        val catClient = GitHubReleaseProvider.defaultClient()
        val entry = try {
            CatalogFetcher.fetch(catClient).getOrElse {
                return@withLock Result.failure(IllegalStateException("Couldn't load the plugin catalog."))
            }.plugins.firstOrNull { it.id == id }
                ?: return@withLock Result.failure(IllegalStateException("No plugin '$id' in the catalog."))
        } finally {
            catClient.close()
        }

        val dir = userDir().apply { mkdirs() }
        val tmp = File(dir, "${entry.jarName}.download")
        val dest = File(dir, entry.jarName)
        val dlClient = Downloader.downloadClient()
        try {
            Downloader(dlClient).download(entry.downloadUrl, tmp)

            // Guard against a non-JAR download (e.g. an error/HTML page returned
            // with a 200). A valid JAR is a ZIP, so it starts with "PK".
            if (!looksLikeZip(tmp)) {
                tmp.delete()
                return@withLock Result.failure(
                    IllegalStateException("Downloaded file for ${entry.jarName} is not a valid JAR.")
                )
            }

            entry.sha256?.let { expected ->
                val actual = SignatureVerifier.sha256(tmp)
                if (!actual.equals(expected, ignoreCase = true)) {
                    tmp.delete()
                    return@withLock Result.failure(
                        IllegalStateException("Checksum mismatch for ${entry.jarName} — refusing to install.")
                    )
                }
            }

            runCatching {
                Files.move(
                    tmp.toPath(), dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            }.onFailure { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        } catch (e: Throwable) {
            tmp.delete()
            return@withLock Result.failure(IllegalStateException("Download failed: ${e.message ?: "unknown error"}"))
        } finally {
            dlClient.close()
        }

        activate(entry)
        Result.success(Unit)
    }

    /** Deletes the installed JAR and removes the module from config. */
    suspend fun uninstall(id: String): Result<Unit> = installMutex.withLock {
        val client = GitHubReleaseProvider.defaultClient()
        val entry = try {
            CatalogFetcher.fetch(client).getOrNull()?.plugins?.firstOrNull { it.id == id }
        } finally {
            client.close()
        } ?: return@withLock Result.failure(IllegalStateException("No plugin '$id' in the catalog."))

        File(userDir(), entry.jarName).delete()
        val config = ConfigStore.load()
        val kept = config.modules.filterNot { it.module == entry.moduleName }
        if (kept.size != config.modules.size) ConfigStore.save(config.copy(modules = kept))
        Result.success(Unit)
    }

    /** True if [file] starts with the ZIP local-file-header magic ("PK"). */
    private fun looksLikeZip(file: File): Boolean = runCatching {
        file.inputStream().use { it.read() == 0x50 && it.read() == 0x4B }
    }.getOrDefault(false)

    /** Adds the module to config using its scanned defaultConfig, if not already present. */
    private fun activate(entry: StorePlugin) {
        val config = ConfigStore.load()
        if (config.modules.any { it.module == entry.moduleName }) return
        val default = scanAvailableModules().firstOrNull { it.name == entry.moduleName }?.defaultConfig
            ?: ModuleConfig(module = entry.moduleName)
        ConfigStore.save(config.copy(modules = config.modules + default))
    }
}