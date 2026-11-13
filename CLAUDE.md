# Lexicon

Lightweight and simple exercise tool for learning words in a foreign language.

## Commands

```sh
./gradlew assembleDebug              # build debug APK
./gradlew installDebug               # install on connected device/emulator
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest  # needs a device/emulator
```

After making changes, build and run lint and unit tests before calling a task done. Fix compile errors before moving on.

- `JAVA_HOME` is not set in the shell: prefix with `JAVA_HOME=/var/lib/snapd/snap/android-studio/current/jbr`, or use the IDE's Gradle build tool.
- Use the SDK's `~/Android/Sdk/platform-tools/adb`; a different `/usr/bin/adb` is also installed and can make devices go offline.
- Emulator AVD: `Medium_Phone_API_37.0`. minSdk is 34 (Android 14).

## Code map (`app/src/main/java/com/example/lexicon/`, single flat package)

- `Words.kt`: YAML parsing (snakeyaml-engine) into `Card` / `WordUnit` / `Language`, loading from the user's word folder or else the demo assets (`loadLibrary`), and the ambiguity rule (`meanings()`, `Card.conflictsWith`).
- `Race.kt`: pure race logic (board, refill, card picking). Compose snapshot state, no UI. `PENALTY_MS`, `RaceResult`.
- `Options.kt`: `RaceOptions`, `FeedbackOptions`, `Prefs` (SharedPreferences: options per language, sound/vibration and best times), `bestKey()`.
- `Feedback.kt`: sound and vibration per `Cue` (countdown, go, correct, mistake, finish, new best). Built-in `Tone`s are synthesized in code (no audio files) and played from static `AudioTrack`s; phone sounds via `RingtoneManager`.
- `MainActivity.kt`: screen navigation as a `Screen` sealed interface in `remember` state, plus `BackHandler`. No nav library, no ViewModel.
- `Screens.kt`: Language (+ general settings sheet: word folder), Mode (+ language settings sheet: Units, then a section per mode), Start, Results. `RaceScreen.kt`: countdown, timer, board UI.
- `Components.kt`: `Chunky` / `ChunkyButton` (press-down button), `FitText` (auto-size text), `Confetti`, `formatTime`.
- `ui/theme/`: colours (`Pastels` per language, `Cream`, `Ink`, …) and `LexiconTheme`. Light only, no dynamic colour.
- Tests in `app/src/test/`: `WordsTest`, `RaceTest`. They read the demo files and the full lists via `wordFolders` (`File("demo")`, `File("../Resources")`).

## Word data

- The real word lists live in the user's word folder (SAF tree, persisted permission), with a `<language>/words/*.yaml` layout; a new folder is a new language. The repo keeps a copy in `Resources/` (not bundled). The folder is read at every start, and unusable files are listed in the general settings.
- `app/demo/<language>/words/demo.yaml` (~30 words each, picked from `Resources/`) is bundled as assets (`assets.directories.add("demo")` in `app/build.gradle.kts`). It's used only while no folder is set or the folder has no usable words.
- Fields: `meta.{id,title,language,translation_language}`, `entries[].{id,term,translation[],forms{plural,genitive,perfect,gender},translation_forms{plural},card_per_sense}`. `form_of` and `tags` are ignored.

## Race rules (agreed with the user)

- One continuous board: when at least half the pairs are matched, new pairs fill the empty left slots and the right column reshuffles. A round is every `pairs` matches; a race is `pairs × rounds` matches.
- Never two cards from the same entry, the same term, or with a shared Dutch meaning on the board at once. Meanings: split on `, / ;`, ignore brackets, case, accents and leading `de/het/een`.
- A mistake adds +3 s. The timer only runs while the app is resumed. Least-shown cards are preferred.
- Pairs 5–10 (default 6), rounds 3–10 (default 3). Units are stored as excluded, so new units are on by default. An "All units" tri-state toggle can clear them all; Race! is then disabled until a unit is picked.
- Only the Race! mode exists; Learn and Test are "soon" placeholders.

## Build gotchas

- AGP 9 has built-in Kotlin. Do not add the `org.jetbrains.kotlin.android` plugin.
- Dependency and plugin versions live only in `gradle/libs.versions.toml`. Compose versions come from the BOM.
- Repositories are declared only in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`).
- The Gradle configuration cache is on; keep custom build logic compatible.

## Principles

- Keep it simple: make the smallest change that works. No new layers, abstractions, DI frameworks or modules until there is a concrete need.
- Ask before adding a dependency.
- Use Compose and Material 3. Hoist state, and add a ViewModel only when state needs one.
- Don't refactor, reformat or rename code unrelated to the task.

## Secrets and git

- API keys go in `local.properties` (git-ignored) and are read via `BuildConfig`. Never hardcode or commit them.
- Don't commit unless asked.