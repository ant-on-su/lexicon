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