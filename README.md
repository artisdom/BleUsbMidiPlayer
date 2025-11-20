# BleUsbMidiPlayer

BleUsbMidiPlayer is an Android app built with Kotlin and Jetpack Compose that discovers USB/BLE MIDI keyboards, indexes bundled or user-selected MIDI files, and streams them to connected instruments. It includes playlist management, favorites, random mixes, playback queues with pause/resume, and GitHub Actions automation to produce downloadable APKs.

## Features

- **USB & BLE MIDI**: Detects USB host devices and BLE instruments (Auto filters names that contain “MIDI” or advertise the MIDI service). Supports connect/disconnect, device refresh, BLE permissions, and maintains active sessions with graceful cleanup.
- **MIDI Playback Engine**: Parses Standard MIDI Files, caches sequences, exposes play/pause/resume/stop, and publishes playback states (Playing/Paused/Completed/Error) alongside queue progress.
- **Assets & User Library**:
  - Bundled MIDI files stored under `app/src/main/assets/midi`.
  - User folder picker via the Storage Access Framework with lazy directory traversal/caching.
  - Favorites toggle per track and folder-level add-to-favorites.
- **Playlists**:
  - Create/rename/delete playlists, shuffle playback, add individual tracks or entire folders, manage contents via dialogs, and generate random 50-track mixes.
- **UI**:
  - Compose-based tabbed layout (Devices, Playback, Favorites, Playlists, Bundled, User Folder).
  - Collapsible cards for sections, playback controls (prev/pause/resume/stop/next), progress indicators, BLE scan UI with progress feedback.
- **Data Persistence**:
  - DataStore keeps selected folders, favorites, playlists.
  - Folder listings cached to avoid re-indexing unchanged directories.
- **CI/CD**:
  - `.github/workflows/android-ci.yml` builds release APKs for PRs/pushes and attaches artifacts to tagged releases.

## Project Structure

- `app/src/main/java/com/example/bleusbmidiplayer/` — Kotlin sources organized into packages (e.g., `midi/`, data stores, UI).
- `app/src/main/res/` — Compose theming and resources.
- `app/src/main/assets/midi/` — Sample MIDI files bundled with the app.
- `.github/workflows/` — GitHub Actions workflow used to build/upload APK artifacts and publish releases.
- `AGENTS.md` — Repository guidelines describing style, testing, and contribution practices.

## Build & Run

Ensure the Android SDK/NDK is installed and Java 21 is available.

```bash
# Build a debug APK
./gradlew assembleDebug

# Install debug build to connected device/emulator
./gradlew installDebug

# Build release APK (unsigned unless signing configured)
./gradlew assembleRelease
```

## Testing

```bash
# JVM unit tests
./gradlew testDebugUnitTest

# Instrumentation tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# Lint checks
./gradlew lint
```

Refer to `AGENTS.md` for additional conventions and expectations around testing and commits.

## GitHub Actions Release Workflow

The automated workflow runs on pushes, PRs, and tags prefixed with `v`. For tagged builds it:

1. Checks out the repo on `ubuntu-latest`.
2. Sets up Temurin Java 21 with Gradle cache.
3. Executes `./gradlew assembleRelease`.
4. Uploads `app/build/outputs/apk/release/*.apk` as an artifact.
5. Creates a GitHub Release and attaches the APK when triggered by a tag.

Signed builds can be produced by adding secure signing configs/secrets, but by default the APK is unsigned for manual installation/testing.

## Permissions

The app requests:

- USB host feature + BLE hardware.
- Bluetooth permissions (`BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`).
- Legacy `ACCESS_FINE_LOCATION` for BLE scans on Android 6–11.
- Storage access via SAF for user-selected folders.

Ensure runtime permission prompts are granted on devices pre-Android 12 for BLE scanning to function.

## Contributing

- Follow the coding style and testing guidance in `AGENTS.md`.
- Keep commits focused and descriptive; include screenshots for UI changes.
- Never commit secrets (keystores, `local.properties`, etc.).
- File issues/PRs for enhancements, bug fixes, or documentation updates.
