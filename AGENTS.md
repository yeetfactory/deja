# AGENTS.md

Déjà is an Android app that remembers the Bluetooth and Wi-Fi devices around you. It tells you when one comes back, or seems to be following you. Everything stays on the phone. The [README](README.md) explains what it does for the people who use it.

## Setup

- JDK 21 and the Android SDK with API level 37.
- One module, `:app`: Kotlin 2.4, the Android Gradle plugin 9 with its built-in Kotlin, Jetpack Compose, and Room through KSP.
- Versions live in `gradle/libs.versions.toml`. Add dependencies there, not in the build files.

## Commands

| Command | What it does |
| --- | --- |
| `./gradlew check` | Formatting, Android Lint and every unit test. Work isn't done until this passes. |
| `./gradlew spotlessApply` | Fixes formatting. |
| `./gradlew :app:testDebugUnitTest --tests 'top.yeetfactory.deja.core.MatchEngineTest'` | Runs one test class. |
| `./gradlew :app:lintDebug` | Android Lint only. The report is in `app/build/reports/`. |
| `./gradlew :app:assembleDebug` | Builds a debug APK. |

Tests run on the JVM, with Robolectric for Android code. They don't need a phone or an emulator.

## Layout

The code is in `app/src/main/java/top/yeetfactory/deja/`.

- `core/` holds the rules, in plain Kotlin with no Android in them: what an advertisement is (`BleClassifier`), when a sighting becomes an event (`MatchEngine`), whether something follows you (`FollowEngine`), places (`PlaceClusterer`), scan timing (`ScanPolicy`) and scanner health (`Health`). New logic goes here, with a test.
- `data/` holds the Room database and its queries (`Database.kt`), the entities (`Model.kt`), `Repository`, which takes in sightings and applies retention, and `SettingsStore`.
- `scan/` holds the Android side: the foreground `ScanService`, the real radios (`Sources.kt`), notifications, and `ScanController`, the only way scanning starts or stops.
- `ui/` holds the Compose screens. `Theme.kt` is the design system: the palette, type, and shared parts such as `ListRow`, `Pill`, `Banner` and `Group`. Use those rather than raw Material components.
- `MainActivity.kt` holds navigation and permission requests.

## Rules that must not break

- **No internet.** The app never gets the INTERNET permission, directly or through a library. The manifest strips it, and `ManifestTest` fails if it comes back. Don't add analytics, crash reporting, or anything else that sends data.
- **Data stays on the phone.** Backups and device transfer stay off.
- **Private addresses are never linked.** Phones rotate their Bluetooth address on purpose. Each private address is its own short-lived encounter, and nothing may guess that two addresses are one device. The fingerprint is for diagnostics only.
- **Only claim what the evidence supports.** An advertisement is a claim, because any transmitter can broadcast anything. Where the evidence stops, say so ("Model unknown"). Type confidence and identity confidence stay separate.
- **Following needs real evidence.** An alert needs repeated readings at places that are clearly apart, in sequence, over time. Don't loosen `FollowEngine` to make alerts fire more often.
- **The user's pause wins.** Scanning starts and stops only through `ScanController`.

## Database changes

Room exports each schema version to `app/schemas/`. When an entity changes:

1. Bump `version` in `@Database`, in `Database.kt`.
2. Write a migration that keeps every label, flag, sighting and event, and add it in `DejaDatabase.create`. Never use a destructive migration, because people would lose their history.
3. Commit the new schema JSON, and keep the old ones.
4. Add a case to `MigrationTest` that upgrades real rows.

## Testing

- Logic in `core/` gets a JVM test. Database code gets a Robolectric test, using `DatabaseTestSupport.kt`.
- Test names are sentences in backticks, such as `` `a pause survives a restart` ``.
- An emulator has no real radios, so debug builds there use `SimulatedSource`: a fake town that replays two days of walking between three places. Use it to see returns and following end to end. Code for the real radios needs a real phone.

## Code style

- Formatting and lint are enforced: ktlint in its `ktlint_official` style with compose-rules, strict Android Lint, and Kotlin warnings as errors. The settings are in `.editorconfig` and `app/build.gradle.kts`.
- Fix the code. Don't suppress a rule, add a baseline or loosen a setting. The one exception is a platform API that's deprecated with no replacement: suppress that one call, and say why in a comment.
- In Compose, every public composable that draws takes `modifier: Modifier = Modifier` and applies it to its root. Parameters go in this order: required ones, then `modifier`, then optional ones. A required event callback never goes last, so only content slots are trailing lambdas. Callbacks are named in the present tense: `onClick`, not `onClicked`.
- Colours come from `LocalPalette.current`. Don't create other CompositionLocals.
- Comments explain why, in plain sentences, like the existing KDoc.

## Writing copy

- Write short, plain sentences. Say what's known, and what isn't.
- Never overstate what Déjà can detect. It's a helpful signal, not a safety guarantee.
- Use British spelling, as the existing copy does: colour, recognise.

## Commits and pull requests

- Use Conventional Commits, such as `feat:`, `fix:`, `docs:`, `refactor:`, `test:` and `chore:`. Branches use the same prefixes, such as `fix/first-run-notification`.
- The commit body says what changed and why, in plain sentences.
- Never commit `keystore.properties`, `*.jks` or `local.properties`. Release builds read the signing key from `keystore.properties`.
- Releases go through the `deja-release` skill in `.agents/skills/`.
