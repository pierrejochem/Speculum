# Developer Guide — Adding Modules (Extensions)

This is a Compose for Desktop (JVM) port of MagicMirror².
Each feature on screen is a **module**. This guide shows how to add one.

The fastest path: clone the standalone reference module at
[`pierrejochem/SpeculumExampleModule`](https://github.com/pierrejochem/SpeculumExampleModule),
rename it, register it, add a config entry. The rest of this doc explains each piece.

---

## Architecture

```
ConfigLoader ──► MirrorConfig ──► MirrorEngine.boot()
                                       │
                 ModuleRegistry.create(moduleConfig)   // name → class
                                       │
                                  MirrorModule  ◄── your code
                                       │
                              MirrorScreen places it by region
```

| Piece | Module / File | Role |
|-------|------|------|
| `MirrorModule` | `:mirror-api` `core/MirrorModule.kt` | Base class every module extends |
| `ModuleConfig` | `:mirror-api` `config/ModuleConfig.kt` | One config entry + typed getters |
| `ModuleFactory` | `:mirror-api` `core/ModuleFactory.kt` | SPI for external JAR modules |
| `Region` / `MirrorColors` | `:mirror-api` | Screen positions / brightness palette |
| `ModuleRegistry` | `:composeApp` `core/ModuleRegistry.kt` | Maps `module` name → class; `register()` for plugins |
| `discoverPluginFactories` | `:composeApp` `core/PluginLoader.kt` | Loads JARs from `plugins/` via ServiceLoader |
| `MirrorEngine` | `:composeApp` `core/MirrorEngine.kt` | Boots modules, drives refresh, notification bus |
| `ConfigLoader` | `:composeApp` `config/ConfigLoader.kt` | The bundled default `config.js` equivalent |
| `MirrorScreen` | `:composeApp` `ui/MirrorScreen.kt` | Lays modules out by region |

**All default modules ship as external JAR plugins** — each is its own Gradle
subproject under `modules/` (`clock-module`, `weather-module`, `calendar-module`,
`compliments-module`, `newsfeed-module`). At build time
each JAR is copied into `plugins/`; at startup the desktop app discovers them
there via reflection. This is the recommended path — start from *External modules*.
A fully-commented reference module lives in its own repo at
[`pierrejochem/SpeculumExampleModule`](https://github.com/pierrejochem/SpeculumExampleModule).

There are **two ways to add a module**:
1. **External JAR plugin** (recommended, desktop) — a standalone Gradle subproject built to a JAR in `plugins/`, discovered at runtime. (See *External modules*.)
2. **Built-in** — a class compiled into `:composeApp` and added to `ModuleRegistry.builtins`. Ships inside the app JAR. `builtins` is empty by default.

The shared API (`MirrorModule`, `ModuleConfig`, `Region`, `MirrorColors`, `ModuleFactory`)
lives in `:mirror-api` so both the app and external plugins compile against it.

---

## The module contract

`MirrorModule` (extend this):

```kotlin
abstract class MirrorModule(val config: ModuleConfig) : NotificationListener {
    val name: String                 // config.module
    val region: Region               // derived from config.position
    open val refreshIntervalMs: Long // 0 disables the refresh loop

    open fun start(scope: CoroutineScope) {}   // once, at boot
    open suspend fun refresh() {}              // every refreshIntervalMs
    open fun stop() {}                         // at shutdown
    override fun onNotification(n: Notification) {}  // bus events

    @Composable abstract fun Content()          // the UI — required
}
```

Only `Content()` is required. Override the rest as needed.

### Lifecycle

- **`start(scope)`** — called once. Launch coroutines in `scope` (auto-cancelled on shutdown). Use for clocks, animations, one-off setup.
- **`refresh()`** — the engine calls this on a loop every `refreshIntervalMs` (first call immediately). Put data fetching here. Override `refreshIntervalMs` to set the cadence; return `0` to skip the loop.
- **`stop()`** — release resources.

### UI state

Hold state in `mutableStateOf(...)`; assigning to it recompose `Content()`:

```kotlin
private var temp by mutableStateOf(0)
override suspend fun refresh() { temp = provider.fetch() }   // UI updates
```

### Reading config

`ModuleConfig` gives typed getters with defaults:

```kotlin
config.string("location", "Hamburg")
config.int("maximumEntries", 10)
config.bool("showSourceTitle", true)
config.refreshIntervalMs            // the entry's refreshInterval
```

---

## Add a module in 4 steps

### 1. Create the class

`composeApp/src/main/kotlin/org/speculum/modules/<name>/<Name>Module.kt`:

```kotlin
package org.speculum.modules.greeting

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.speculum.config.ModuleConfig
import org.speculum.core.MirrorModule

class GreetingModule(config: ModuleConfig) : MirrorModule(config) {
    private val who = config.string("who", "world")
    @Composable
    override fun Content() {
        Text("Hello, $who!", color = Color.White, fontSize = 24.sp)
    }
}
```

### 2. Register it

In [`ModuleRegistry.kt`](composeApp/src/main/kotlin/org/speculum/core/ModuleRegistry.kt),
add to the `factories` map (key = the `module` name used in config):

```kotlin
"greeting" to ::GreetingModule,
```

### 3. Add a config entry

In [`ConfigLoader.kt`](composeApp/src/main/kotlin/org/speculum/config/ConfigLoader.kt),
add to the `modules` array:

```json
{ "module": "greeting", "position": "middle_center", "refreshInterval": 0,
  "config": { "who": "Jochem" } }
```

### 4. Run

```bash
./gradlew :composeApp:run        # desktop
```

`refreshInterval` is in **milliseconds**. `config` is a free-form string map.

---

## Positions (regions)

`position` maps to a `Region` (case-insensitive). Available:

```
top_left      top_center      top_right
upper_third   middle_center   lower_third
bottom_left   bottom_center   bottom_right
bottom_bar    fullscreen_above fullscreen_below
```

Layout ([`MirrorScreen.kt`](composeApp/src/main/kotlin/org/speculum/ui/MirrorScreen.kt)):
top regions anchor to the top, `upper/middle/lower_third` are **centered in the
remaining space** (never overlap the bands on resize), bottom regions + `bottom_bar`
anchor to the bottom. Multiple modules in the same region stack vertically.

---

## Common patterns

### Fetching data (network)

Keep HTTP in a separate **provider** class (the Ktor engine is provided by the
host app at runtime). See
[`WeatherProvider`](modules/weather-module/src/main/kotlin/org/speculum/modules/weather/WeatherProvider.kt),
[`NewsProvider`](modules/newsfeed-module/src/main/kotlin/org/speculum/modules/news/NewsProvider.kt),
[`CalendarProvider`](modules/calendar-module/src/main/kotlin/org/speculum/modules/calendar/CalendarProvider.kt).

```kotlin
class MyProvider {
    private val client = HttpClient()
    suspend fun fetch(): String = client.get("https://…").bodyAsText()
}

class MyModule(config: ModuleConfig) : MirrorModule(config) {
    private val provider = MyProvider()
    private var data by mutableStateOf("")
    override val refreshIntervalMs = config.refreshIntervalMs.coerceAtLeast(60_000)
    override suspend fun refresh() {
        runCatching { provider.fetch() }.onSuccess { data = it }
    }
    @Composable override fun Content() { Text(data, color = Color.White) }
}
```

For JSON use `kotlinx.serialization` — and **apply the serialization plugin in
the module's `build.gradle.kts`** (`alias(libs.plugins.kotlinSerialization)`),
or `@Serializable` classes get no generated serializer and deserialization fails
at runtime (see `weather-module`). For RSS/ICS a regex parser keeps it
dependency-free (see `NewsProvider`/`CalendarProvider`).

### Notifications (inter-module bus)

The engine broadcasts events (e.g. `ALL_MODULES_STARTED`). Receive:

```kotlin
override fun onNotification(n: Notification) {
    if (n.name == "ALL_MODULES_STARTED") { /* … */ }
}
```

Broadcasting from a module requires an engine reference — wire one in if you need
cross-module messaging; today the engine is the broadcaster.

### Icons — keep them monochrome

A two-way mirror shows only bright pixels, so **icons must be pure white**, not
color emoji. Compose Desktop falls back to the color-emoji font for many glyphs
(ignoring text-presentation selectors), so **draw icons as vectors** with `Canvas`.
See [`WeatherGlyph.kt`](modules/weather-module/src/main/kotlin/org/speculum/modules/weather/WeatherGlyph.kt)
and [`CalendarGlyph.kt`](modules/calendar-module/src/main/kotlin/org/speculum/modules/calendar/CalendarGlyph.kt).
Plain geometric text glyphs (`•`, `↑`, `≈`) render monochrome and are fine.

### Fonts & colors

Roboto + bright white are the global default (set in `App.kt` via `LocalTextStyle`),
so a bare `Text("…")` already looks right. Use the three MagicMirror brightness
levels from [`Theme.kt`](composeApp/src/main/kotlin/org/speculum/ui/Theme.kt):

```kotlin
MirrorColors.Bright   // #FFFFFF  headlines, values
MirrorColors.Normal   // #999999  labels
MirrorColors.Dimmed   // #666666  secondary / headers
```

Section headers follow MagicMirror style: `UPPERCASE`, `letterSpacing = 2.sp`,
`MirrorColors.Dimmed` (see `CalendarModule`).

### Bundling assets (fonts/images)

Drop files in `composeApp/src/main/composeResources/` (e.g. `font/`, `drawable/`),
then reference via the generated `Res` (package `org.speculum.resources`, configured
in `composeApp/build.gradle.kts`). See `Theme.kt`'s `robotoFamily()`.

---

## External modules (JAR plugins)

An external module is a separate Gradle subproject built to a JAR and loaded at
runtime — no app rebuild, no edit to `ConfigLoader`. The reference is the
standalone [`pierrejochem/SpeculumExampleModule`](https://github.com/pierrejochem/SpeculumExampleModule)
repo, which compiles against the **published** `mirror-api` (see *Building
outside this repo*). The default modules under `modules/` are built the same way
but use `compileOnly(project(":mirror-api"))` since the API is in-tree.

How it works:

1. The plugin module compiles against `:mirror-api` as **`compileOnly`** (so the
   JAR contains only its own classes — Compose and the API are provided by the
   host at runtime, shared via the parent classloader).
2. It implements [`ModuleFactory`](mirror-api/src/main/kotlin/org/speculum/core/ModuleFactory.kt)
   and declares it in `META-INF/services/org.speculum.core.ModuleFactory`.
3. On startup, [`PluginLoader`](composeApp/src/main/kotlin/org/speculum/core/PluginLoader.kt)
   scans `plugins/*.jar`, loads each in a `URLClassLoader` (parent = app), and
   finds factories via the JDK `ServiceLoader` (reflection).
4. Each factory is `register()`ed and its `defaultConfig()` is appended to the
   config automatically (unless an entry already names that module), ordered by
   `ModuleFactory.order` so stacking within a region is deterministic.

> **Source vs. runtime folders:** subprojects live under `modules/`; their built
> JARs are deployed into `plugins/` (the folder the app scans). They're kept
> separate so Gradle doesn't see build outputs landing inside the source tree.

### Create one

```
modules/<name>-module/
├── build.gradle.kts                       # kotlinJvm + compose, compileOnly(:mirror-api)
└── src/main/
    ├── kotlin/.../FooModule.kt            # extends MirrorModule
    ├── kotlin/.../FooModuleFactory.kt     # implements ModuleFactory
    └── resources/META-INF/services/org.speculum.core.ModuleFactory
                                            # one line: the factory's FQN
```

`build.gradle.kts` (copy from any module under `modules/`, or from the standalone
[`SpeculumExampleModule`](https://github.com/pierrejochem/SpeculumExampleModule);
add `kotlinSerialization` too if you use `@Serializable`):

```kotlin
plugins { alias(libs.plugins.kotlinJvm); alias(libs.plugins.composeMultiplatform); alias(libs.plugins.composeCompiler) }
dependencies {
    compileOnly(project(":mirror-api"))
    compileOnly(compose.runtime); compileOnly(compose.foundation); compileOnly(compose.material3)
    // + compileOnly the Ktor/datetime/serialization libs you use; host provides them at runtime
}
val deployToModules by tasks.registering(Copy::class) {
    from(tasks.named("jar")); into(rootProject.layout.projectDirectory.dir("plugins"))
}
```

The factory provides the name, constructor, and default placement:

```kotlin
class FooModuleFactory : ModuleFactory {
    override val name = "foo"
    override fun create(config: ModuleConfig) = FooModule(config)
    override fun defaultConfig() = ModuleConfig("foo", "top_center", 3000, mapOf("k" to "v"))
}
```

Register the subproject in `settings.gradle.kts`, add it to the root
`deployModules` task, then build + deploy + run:

```bash
./gradlew :modules:foo-module:deployToModules   # builds JAR → plugins/
./gradlew :composeApp:run                        # discovers + loads it (run cwd = repo root)
```

`:composeApp:run` depends on the root `:deployModules` task, which builds and
deploys **all** module JARs into `plugins/` automatically. Add yours to that
task's `dependsOn` list and it ships with every run.

> The app is pure desktop (JVM). External JARs are loaded with the JDK
> `URLClassLoader` + `ServiceLoader`; there is no Android/iOS target.

### Building outside this repo

For a module developed in its own repo (no `project(":mirror-api")` available),
depend on the **published** API instead. `mirror-api` is released to GitHub
Packages as `org.speculum:mirror-api` on every `v*` tag — swap the project
dependency for `compileOnly("org.speculum:mirror-api:<version>")` and add the
GitHub Packages repository. See *Using `mirror-api` as a dependency* in the
[README](README.md) for the repository block and auth.

The standalone [`pierrejochem/SpeculumExampleModule`](https://github.com/pierrejochem/SpeculumExampleModule)
repo is exactly this: a complete, independently buildable example wired up to the
published API. Clone it as the starting point for an out-of-tree module.

### Publishing to the plugin store

The config UI has a **Plugin store** that installs modules with one click: it
downloads the JAR into `~/.speculum/plugins/` (a writable folder scanned
alongside the bundled/dev `plugins/`) and enables the module in `config.json`.
The store reads `store/catalog.json` from this repo. To list a module:

1. Publish your `*-module.jar` as a **release asset** in your module's own repo
   (e.g. `…/releases/latest/download/<name>-module.jar`).
2. Open a PR adding an entry to [`store/catalog.json`](store/catalog.json):
   `id`, `name`, `description`, `author`, `homepage`, `moduleName` (the
   `ModuleFactory.name`), `jarName` (the file written to disk), `downloadUrl`,
   and optionally `sha256` (a lowercase hex digest — the download is verified
   against it and rejected on mismatch).

A newly installed JAR renders on the mirror after the next restart (plugins are
scanned once at boot).

## The reference example

[`SpeculumExampleModule`](https://github.com/pierrejochem/SpeculumExampleModule)
(`ExampleModule.kt` + `ExampleModuleFactory.kt`) demonstrates the whole flow:
config reading, `start()`, `refresh()`, `onNotification()`, state, `Content()`,
and packaging as a discoverable JAR. Build it, drop the JAR into `plugins/`, run
the app, and it appears at `top_center` showing a live refresh counter and the
last notification. The default modules (`clock`, `weather`, `calendar`,
`compliments`, `newsfeed`) are built the same way — browse them under `modules/`.

---

## Checklist (external module)

- [ ] Subproject `modules/<name>-module/` with `build.gradle.kts` (`compileOnly(:mirror-api)`; add `kotlinSerialization` if using `@Serializable`)
- [ ] `<Name>Module` extends `MirrorModule`; `<Name>ModuleFactory` implements `ModuleFactory` (+ `defaultConfig()`)
- [ ] `META-INF/services/org.speculum.core.ModuleFactory` lists the factory FQN
- [ ] Included in `settings.gradle.kts` and the root `deployModules` task
- [ ] Network in a provider; icons are white vectors; text uses `MirrorColors`
- [ ] `./gradlew :deployModules :composeApp:compileKotlin` is green