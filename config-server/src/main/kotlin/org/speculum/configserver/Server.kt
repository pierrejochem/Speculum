package org.speculum.configserver

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.speculum.config.MirrorConfig
import java.io.File

@Serializable data class LoginRequest(val password: String)
@Serializable data class LoginResponse(val token: String)
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
@Serializable data class Message(val message: String)
@Serializable data class VersionResponse(val version: String)
@Serializable data class StoreActionRequest(val id: String)

/**
 * Running version for the admin's read-only display. Injected as
 * `-Dspeculum.version` (gradle.properties when standalone; the composeApp JVM
 * when embedded in the packaged app). Falls back to "dev" when unset.
 */
private fun runningVersion(): String =
    (System.getProperty("speculum.version")?.takeIf { it.isNotBlank() } ?: "dev").removePrefix("v")

fun main() = startServer(wait = true)

/**
 * Starts the admin server. [wait] = false returns immediately (own threads) so
 * the mirror app can embed it; true blocks (standalone `./gradlew run`).
 */
fun startServer(wait: Boolean) {
    val port = System.getenv("MIRROR_ADMIN_PORT")?.toIntOrNull() ?: 8080
    println("Speculum config server on http://localhost:$port  (config: ${ConfigStore.file.absolutePath})")
    embeddedServer(Netty, port = port) { module() }.start(wait = wait)
}

fun Application.module() {
    install(ContentNegotiation) { json(Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, Message(cause.message ?: "error"))
        }
    }
    // Allow the Vite dev server (different origin) to call the API in development.
    install(CORS) {
        allowHost("localhost:5173"); allowHost("127.0.0.1:5173")
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Put); allowMethod(HttpMethod.Post)
    }

    routing {
        post("/api/login") {
            val req = call.receive<LoginRequest>()
            if (Auth.checkPassword(req.password)) call.respond(LoginResponse(Auth.issueToken()))
            else call.respond(HttpStatusCode.Unauthorized, Message("Invalid password"))
        }

        post("/api/password") {
            if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            val req = call.receive<ChangePasswordRequest>()
            if (req.newPassword.length < Auth.MIN_PASSWORD_LENGTH)
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    Message("New password must be at least ${Auth.MIN_PASSWORD_LENGTH} characters")
                )
            if (Auth.changePassword(req.currentPassword, req.newPassword))
                call.respond(Message("Password updated"))
            else
                call.respond(HttpStatusCode.Unauthorized, Message("Current password is incorrect"))
        }

        // Read-only running version for the header badge — not sensitive, no auth.
        get("/api/version") {
            call.respond(VersionResponse(runningVersion()))
        }

        get("/api/update/status") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            call.respond(UpdateService.status())
        }

        post("/api/update/start") {
            if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            val status = UpdateService.status()
            if (!status.updatable)
                return@post call.respond(HttpStatusCode.Conflict, Message(status.reason ?: "No update available"))
            if (UpdateJob.start()) call.respond(HttpStatusCode.Accepted, UpdateJob.snapshot())
            else call.respond(HttpStatusCode.Conflict, Message("An update is already in progress"))
        }

        get("/api/update/progress") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            call.respond(UpdateJob.snapshot())
        }

        get("/api/modules") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            call.respond(scanAvailableModules())
        }

        get("/api/ips") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            call.respond(localIPv4Addresses())
        }

        // Plugin app store: list catalog entries, download+activate, or remove.
        get("/api/store") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            call.respond(StoreService.list())
        }

        post("/api/store/install") {
            if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            val id = call.receive<StoreActionRequest>().id
            StoreService.install(id)
                .onSuccess { call.respond(StoreService.list()) }
                .onFailure { call.respond(HttpStatusCode.Conflict, Message(it.message ?: "Install failed")) }
        }

        post("/api/store/uninstall") {
            if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            val id = call.receive<StoreActionRequest>().id
            StoreService.uninstall(id)
                .onSuccess { call.respond(StoreService.list()) }
                .onFailure { call.respond(HttpStatusCode.Conflict, Message(it.message ?: "Uninstall failed")) }
        }

        get("/api/config") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            // Mirror the app's rule: an empty module list means "use all plugin
            // defaults", so the admin sees the real out-of-box set to edit from.
            val stored = ConfigStore.load()
            val effective = if (stored.modules.isNotEmpty()) stored
            else stored.copy(modules = scanAvailableModules().mapNotNull { it.defaultConfig })
            call.respond(effective)
        }

        put("/api/config") {
            if (!call.authed()) return@put call.respond(HttpStatusCode.Unauthorized, Message("Unauthorized"))
            val config = call.receive<MirrorConfig>()
            ConfigStore.save(config)
            call.respond(Message("Saved to ${ConfigStore.file.absolutePath}"))
        }

        // Serve the built React app (production). In dev, use the Vite server.
        webDir()?.let { staticFiles("/", it) { default("index.html") } }
    }
}

private fun ApplicationCall.authed(): Boolean =
    Auth.isValid(request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim())

private fun webDir(): File? = listOfNotNull(
    System.getenv("MIRROR_WEB"),
    // Bundled inside the packaged app (jpackage resources dir).
    System.getProperty("compose.application.resources.dir")?.let { "$it/web" },
    "config-server/web/dist",
    "web/dist",
).map(::File).firstOrNull { it.isDirectory }