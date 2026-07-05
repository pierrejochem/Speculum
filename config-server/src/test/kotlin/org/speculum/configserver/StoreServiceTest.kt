package org.speculum.configserver

import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.speculum.config.ConfigPaths
import java.io.File
import java.nio.file.Files

class StoreServiceTest {

    private lateinit var dir: File
    private val server = embeddedServer(Netty, port = 0) {
        routing {
            get("/catalog.json") { call.respondText(catalogJson, ContentType.Application.Json) }
            get("/example-module.jar") { call.respondBytes(JAR_BYTES) }
        }
    }
    private var port = 0

    @Volatile private var catalogJson = "{}"

    // Isolate config + user plugins dir to a temp dir (ConfigPaths derives both
    // from `mirror.config`), and point the catalog fetch at our local server.
    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("speculum-store-test").toFile()
        System.setProperty("mirror.config", File(dir, "config.json").absolutePath)
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterEach
    fun tearDown() {
        server.stop(0, 0)
        System.clearProperty("mirror.config")
        System.clearProperty("mirror.store.catalog")
        dir.deleteRecursively()
    }

    private fun useCatalog(sha256: String? = null) {
        val shaField = sha256?.let { ""","sha256":"$it"""" } ?: ""
        catalogJson = """
          { "version": 1, "plugins": [
            { "id": "example", "name": "Example", "moduleName": "example",
              "jarName": "example-module.jar",
              "downloadUrl": "http://localhost:$port/example-module.jar"$shaField } ] }
        """.trimIndent()
        System.setProperty("mirror.store.catalog", "http://localhost:$port/catalog.json")
    }

    @Test
    fun catalogParseRoundTrips() {
        val cat = StoreCatalog(plugins = listOf(
            StorePlugin("example", "Example", moduleName = "example", jarName = "example-module.jar", downloadUrl = "u")
        ))
        val text = Json.encodeToString(StoreCatalog.serializer(), cat)
        assertEquals(cat, Json.decodeFromString(StoreCatalog.serializer(), text))
    }

    @Test
    fun installDownloadsJarEnablesModuleAndListReflectsIt() = runBlocking {
        useCatalog()

        // Before install: catalog visible, nothing installed or enabled.
        val before = StoreService.list()
        assertEquals(1, before.plugins.size)
        assertFalse(before.plugins[0].installed)
        assertFalse(before.plugins[0].enabled)

        assertTrue(StoreService.install("example").isSuccess)

        // JAR landed in the user plugins dir and the module was added to config.
        assertTrue(File(ConfigPaths.userPluginsDir(), "example-module.jar").isFile)
        assertTrue(ConfigStore.load().modules.any { it.module == "example" })

        val after = StoreService.list()
        assertTrue(after.plugins[0].installed)
        assertTrue(after.plugins[0].enabled)
    }

    @Test
    fun installRejectsChecksumMismatch() = runBlocking {
        useCatalog(sha256 = "deadbeef") // wrong digest for JAR_BYTES

        val result = StoreService.install("example")
        assertTrue(result.isFailure)
        // Nothing persisted: no JAR, no config entry.
        assertFalse(File(ConfigPaths.userPluginsDir(), "example-module.jar").isFile)
        assertFalse(ConfigStore.load().modules.any { it.module == "example" })
    }

    @Test
    fun uninstallRemovesJarAndModule() = runBlocking {
        useCatalog()
        StoreService.install("example")

        assertTrue(StoreService.uninstall("example").isSuccess)
        assertFalse(File(ConfigPaths.userPluginsDir(), "example-module.jar").isFile)
        assertFalse(ConfigStore.load().modules.any { it.module == "example" })
    }

    companion object {
        private val JAR_BYTES = "PK-fake-jar-content".toByteArray()
    }
}