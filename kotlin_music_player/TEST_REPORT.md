# Music Room 1.2 verification

Checked on November 5, 2026.

## Build and APK

- Gradle wrapper 8.13, Android Gradle plugin 8.13.2, Kotlin 2.3.0, JDK 17.
- `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest -PliveTests=true` passed.
- Lint: 0 errors, 25 warnings, 1 hint. These concern localization,
  available dependency/tool updates, backup metadata and optional Kotlin extensions.
- APK: `releases/music-room-v3.apk`; 8,024,385 bytes.
- App ID `local.music.kotlinplayer`; version 1.2, code 3, minimum API 26, target 36.
- Original private debug signing key retained for in-place updates.
- SHA-256: `5c92ce0037d6b352e7a202ed6a7cef0bcee8f68660639338481f1216dacc1ca1`.
- Generated APKs, signing keys and machine settings are excluded from Git.

## Automated checks

| Suite | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| FftTest | 7 | 0 | 0 |
| LiveSearchTest | 1 | 0 | 0 |
| MusicSearchTest | 8 | 0 | 0 |
| PlaybackServiceTest | 3 | 0 | 0 |
| ReleaseUpdatesTest | 10 | 0 | 0 |
| UpdateInstallerTest | 7 | 0 | 0 |

All 36 tests passed. The live search test fetched actual YouTube results,
thumbnail bytes and a small range from a resolved stream into memory.

Updater tests cover highest newer version selection, unchanged/older versions,
ignoring desktop/draft/prerelease entries, invalid metadata, pinned download
addresses, digest requirements, cached-file reuse, corrupted cache replacement,
truncated/oversized downloads, HTTP errors and partial-file cleanup. Downloader
tests use an in-process OkHttp interceptor; no test APK is installed.

Installer tests cover matching app IDs and signing certificates, downgrade and
version mismatches, minimum Android versions, the per-app install-permission
intent and FileProvider content URIs with read-only access restricted to the
update cache. Robolectric simulates API 28 for these Android-dependent checks.

Existing FFT, HTTP cancellation/size-limit and playback lifecycle checks pass.
Stopping/destroying the window preserves the service player's media item/play
intention; removing the task clears playback and stops the service. These tests
do not decode real audio or enforce Android foreground-service policy.

## Update behavior

Checks run separately from playback. The user accepts an update before its APK
is downloaded. Downloaded bytes must match GitHub's size and SHA-256 digest.
The APK's package, higher version code, signing certificate and Android minimum
are checked before Android's installer is opened. Pending install permission
state is saved across normal activity recreation. Android still requires user
installation approval. Cancelling a download or closing the window cancels its
update request without stopping background music.

The original Java Android app and desktop binaries were not changed. Kotlin
source is now public; only the APK is attached as a release asset. No private
signing material or credentials are included in Git.

## Public release verification

The release helper published `android-v1.2` after comparing GitHub's uploaded
asset size and digest with the local APK. It also verified the signing
certificate and ZIP alignment. A draft-tag lookup issue was fixed by retrieving
the release by ID; interrupted draft uploads can now be resumed.

A temporary JVM harness then ran the app's actual compiled `ReleaseUpdates`
code against the unauthenticated public GitHub API. It verified that installed
code 2 discovers code 3, downloaded the published APK and checked its SHA-256,
and verified that installed code 3 receives no update. This checks the live
feed and downloader, not Android's installer UI. The temporary harness and its
downloads were removed after verification.

Release: https://github.com/HimanshuS073/music_visualizer/releases/tag/android-v1.2

## Device limits

ADB found no connected phone. No emulator was used. Real-device installation,
Android's unknown-source permission screen, update data preservation, background
and screen-lock audio, rendering/FFT and Android 14–16 service enforcement still
need phone testing. No battery, CPU or RAM benchmark was performed. The build
and automated checks do not establish those device behaviors.
