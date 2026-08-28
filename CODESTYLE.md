# Holowbark code style

The rules a patch to this repository must follow. They exist to keep the code
readable without comments, so that the next person — or the next module — has one
obvious place to look and one obvious way to write.

## 1. Vocabulary

One term per concept. Never introduce a synonym for something that already has a
name here.

| Term | Means |
|---|---|
| `ygg` | the Yggdrasil overlay network, its manager, its packets |
| `awg` | the WireGuard / AmneziaWG layer, whichever config is loaded |
| `tunnel` | both layers together, as the user sees them |
| `peer` | a Yggdrasil public peer |
| `server` | the WireGuard endpoint behind the overlay — never called a peer |

`Yggdrasil` is spelled in full only in `YggdrasilManager`, where it names the
native library being wrapped. Everywhere else it is `ygg`.

The UI shows `AmneziaWG` or `WireGuard` depending on the loaded config. That string
comes from `AwgConfig.protocolName` and is never rebuilt inline.

## 2. Naming

- `PascalCase` types, `camelCase` members and locals, `SCREAMING_SNAKE_CASE`
  constants.
- Booleans read as predicates: `isRunning`, `hasConfig`, `isAwg`.
- The `Manager` suffix is reserved for a class that owns the lifecycle of a native
  backend. `AwgManager` and `YggdrasilManager` are the only two, and there should
  not be a third without a native library behind it.
- A file is named after the primary type it declares. A file of free functions is
  named after what they do (`RouteSplitter.kt`, `PacketCodec.kt`), never `*Utils`.
- Never give the same string literal two meanings. If an intent extra and a
  preference key would collide, rename one.

## 3. Comments

Comments explain **why**, never **what**. Assume the reader can read Kotlin.

Write a comment when the code encodes a decision the reader cannot recover:
a platform quirk, an RFC requirement, a deadline imposed by the framework, a
workaround with a source link. Those comments are load-bearing — do not delete
them to reduce comment count.

Do not write:

- KDoc that restates the signature
- banner dividers (`// ─── Section ───`); a file that needs them should be split
- a description of the next line
- the name of any tool, plugin, editor, or AI assistant

Comments are English only. A comment that contradicts the code is a bug: fix or
delete it in the same patch that finds it.

## 4. Constants

No magic numbers at a call site. A timeout, a port, an MTU, a poll interval, and a
buffer size each get a named constant in the `companion object` of the class that
owns the behaviour — and only there. If two classes need the same number, one of
them is the owner and the other reads it.

The SharedPreferences file name and every key live in `vpn/Prefs.kt`. No other file
may spell them.

## 5. Logging

`AppLogger` only. Never `android.util.Log` — it bypasses the in-app Logs screen,
which is the primary field-debugging tool for a VPN that fails on someone else's
network.

One `TAG` per file, declared in the `companion object`, equal to the class name.
The tag is a constant: never build it from runtime state, or the Logs screen
cannot be filtered.

Levels: `e` for a failure the user will notice, `w` for a degraded path that still
works, `i` for lifecycle transitions, `d` for per-operation detail. Nothing on a
per-packet path above `d`.

## 6. Errors

- `runCatching` only where failure is expected **and** handled. A bare
  `.getOrNull()` that discards the cause needs a log line next to it.
- Never catch and continue silently.
- A loop reading from a native backend must terminate on exception. A loop that
  retries a failing blocking call with no delay spins a CPU core and drains the
  battery — this is the most likely bug shape in this codebase.
- No error handling for conditions the code makes impossible.

## 7. Coroutines

Each long-lived component owns exactly one `CoroutineScope`, built with
`SupervisorJob()`, and cancels it in its own `stop()`. A component never cancels a
scope it does not own. Blocking native reads run on `Dispatchers.IO`.

## 8. Compose

- Screens take a ViewModel; every other composable is private, stateless, and
  takes plain values plus callbacks.
- Never duplicate a layout per orientation. Vary the container, share the content.
- User-visible strings live in `res/values/strings.xml`.
- Colours come from `MaterialTheme.colorScheme`, not from hex literals.

## 9. Tests

Pure logic lives in `app/src/test`, JUnit4, no Android dependency, no Robolectric.
The packet codec, the route splitter, and the config parser are the code that is
painful to debug on a phone, so they are the code that gets tested.

- One behaviour per test.
- Named `subject_condition_expectedResult`.
- Do not test constructors, getters, data-class equality, or the framework.

Run with `./gradlew test`.

## 10. Adding to the app

| You are adding | It goes in | And you must wire |
|---|---|---|
| a packet handler | `vpn/` | a branch in `PacketRouter.dispatch` or `YggdrasilManager.readLoop` |
| a screen | `ui/screens/` | a route in `ui/Navigation.kt` |
| a persisted setting | `vpn/Prefs.kt` | the reader in `TunnelViewModel`, and `TunnelService` if the service needs it |
| a native call | `AwgManager` or `YggdrasilManager` | nothing else — those two files are the only Kotlin↔Go boundary, and keeping it that way is what makes the rest testable |

New state shared between the service and the UI goes through `TunnelStatus` — one
snapshot type, broadcast as intent extras. Do not add a second channel.
