package org.speculum.configserver

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.speculum.update.GitHubReleaseProvider

/** One installable plugin listed in the app store catalog. */
@Serializable
data class StorePlugin(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
    /** The `ModuleFactory.name` this JAR provides — used to detect installed/enabled. */
    val moduleName: String,
    /** Filename written into the user plugins dir (e.g. `example-module.jar`). */
    val jarName: String,
    /** `browser_download_url` of the release asset to fetch. */
    val downloadUrl: String,
    /** Optional lowercase hex SHA-256; when present the download is verified against it. */
    val sha256: String? = null,
)

@Serializable
data class StoreCatalog(
    val version: Int = 1,
    val plugins: List<StorePlugin> = emptyList(),
)

/**
 * Fetches the plugin catalog — the `store/catalog.json` list committed in the
 * repo, served from GitHub raw. The URL is overridable via the `mirror.store.catalog`
 * JVM property or `MIRROR_STORE_CATALOG` env (used to point at a mirror or, in
 * tests, a local server). Body is read as text and parsed explicitly: raw GitHub
 * serves `text/plain`, so Ktor content negotiation on `application/json` wouldn't
 * kick in.
 */
object CatalogFetcher {
    private const val DEFAULT_URL =
        "https://raw.githubusercontent.com/pierrejochem/SpeculumSmartMirror/main/store/catalog.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun catalogUrl(): String =
        (System.getProperty("mirror.store.catalog") ?: System.getenv("MIRROR_STORE_CATALOG"))
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_URL

    /** Loads and parses the catalog. Failures (offline, 404, bad JSON) surface as a failed Result. */
    suspend fun fetch(client: HttpClient = GitHubReleaseProvider.defaultClient()): Result<StoreCatalog> =
        runCatching {
            val response = client.get(catalogUrl()) {
                header("User-Agent", "Speculum-Store")
                header("Accept", "application/json")
            }
            // Check the status before parsing: a 404 (catalog not published yet)
            // returns a plain "404: Not Found" body that would otherwise fail JSON
            // parsing with a confusing message.
            if (!response.status.isSuccess())
                throw IllegalStateException("catalog request failed (${response.status})")
            json.decodeFromString<StoreCatalog>(response.bodyAsText())
        }
}