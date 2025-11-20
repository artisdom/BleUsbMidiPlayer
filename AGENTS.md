# Repository Guidelines

## Project Structure & Module Organization
- `app/src/main/java/com/example/bleusbmidiplayer/` holds Kotlin sources, with UI, BLE, USB, and MIDI helpers grouped by package. Keep feature-specific code inside nested packages (e.g., `midi/`, `ble/`).
- Layouts, drawables, and other Android resources live under `app/src/main/res/`. Keep asset names lowercase with underscores (e.g., `ic_usb_status`).
- Unit tests sit in `app/src/test/`, while device/emulator tests live in `app/src/androidTest/`. Mirror the production package tree so related code is easy to locate.

## Build, Test, and Development Commands
- `./gradlew assembleDebug` – compile the `app` module and produce a debug APK.
- `./gradlew installDebug` – build and deploy the debug APK to a connected device/emulator.
- `./gradlew testDebugUnitTest` – run JVM unit tests under `app/src/test`.
- `./gradlew connectedDebugAndroidTest` – execute instrumentation tests on an attached device/emulator.
- `./gradlew lint` – run Android Lint; fix or suppress only with justification.

## Coding Style & Naming Conventions
- Kotlin code follows the Android/Kotlin style guide: 4-space indentation, expressive function names (`onBleDeviceSelected()`), and PascalCase classes. Avoid magic numbers; extract constants to `companion object` members.
- XML resources use 2-space indentation; attribute ordering should follow Android Studio’s “Reformat File”.
- Prefer Jetpack Compose or ViewBinding patterns already present; keep business logic in `ViewModel`s under `midi`/`viewmodel` packages and UI glue inside `Activity` or composable files.

## Testing Guidelines
- New features require at least one unit test covering MIDI/BLE edge cases plus UI state transitions when feasible.
- Instrumentation tests should stub USB/BLE dependencies to avoid hardware assumptions; use clear method naming such as `playback_stops_when_usb_removed()`.
- Keep code testable by exposing interfaces for hardware abstractions.

## Commit & Pull Request Guidelines
- Match existing history: imperative, descriptive subject lines (e.g., “Add BLE reconnect timeout”) with optional trailing context sentences in the body.
- Keep commits scoped to a single concern; include migrations or resource updates in the same change when they are tightly coupled.
- Pull requests must describe the feature or fix, list test commands (copy the ones above), link related issues, and include screenshots or screen recordings for UI updates.

## Security & Configuration Tips
- Never commit `local.properties`, keystores, or device-specific MIDI assets. Use `.gitignore` and environment variables for secrets.
- When touching BLE/USB permissions, verify `AndroidManifest.xml` and runtime permission prompts stay in sync to avoid app-store rejections.
