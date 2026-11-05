# Music Room — Kotlin

Music Room is the Android version of this music player, written in Kotlin.
Open this folder in Android Studio, rather than the main Lua/Python folder.
The source is public. Signing keys, machine settings, build caches and APK files
stay out of Git; installers are attached to GitHub releases.

## Using the app

Search for a song and tap a result to play it. Small thumbnails load beside the
results, and the selected song's cover becomes a muted background. Searching
again keeps the current track playing. Selecting a result hides the list and
leaves the listening area clear. The list returns when you submit another search,
even if the same song is still playing. Selecting another result stops the old
track and resolves a fresh stream. Hiding results survives rotation and does not
clear the result data. Queued preview downloads stop once a song is selected.

The controls pause/resume playback and seek ten seconds in either direction.
While music plays, they hide after three seconds. Tap the visualizer area to
show them again. The bars slide to the bottom without changing height. Controls
stay visible while paused or when screen-reader touch exploration is enabled.

Settings change the visualizer height, bar count, color-change speed and video
sampling rate. Height accepts 24–320 density-independent pixels and is limited
to the available screen space; the saved preference survives rotation and
relaunching. The height stays the same when the controls hide or reappear.

FFT samples come from the player's audio, and bar colors come from decoded video
frames. No microphone permission is needed. Songs and images aren't saved to
disk. Thumbnail memory is capped at 2 MiB; image downloads at 4 MiB each.

Music keeps playing when you switch apps or lock the screen. A media playback
service owns the player, with pause/resume and seek controls in Android's media
notification. Returning to Music Room reconnects to the same track and position.
Swiping Music Room away from Recents or force-stopping it stops playback; it
doesn't restart itself. A track also stops when it finishes, and Android may
pause it for calls or another app's audio. Visualizer updates stop while the
window is hidden. Android 12+ blurs the cover; older versions show the muted image.

## Interface

The interface uses native Android views with a warm dark background, mint
controls and a consistent sans-serif font. Search results have rounded artwork,
a selected-track highlight and duration labels. The compact player includes
cover art, pause/resume, ten-second controls and a draggable timeline.

The layout places search and playback beside each other in landscape. While
the keyboard is open, the player panel moves out of the way so results have
more space; audio continues. Controls still hide smoothly during playback.
No UI framework, font pack or image assets were added.

The UI source is split into small files:

- `MusicScreen.kt` creates the screen and adapts it to the window/keyboard.
- `RoomStyle.kt` defines colors, text, rounded surfaces and vector icons.
- `SongRow.kt` draws a search-result card.
- `MainActivity.kt` connects the views to searches and the playback service.

To render local previews with test artwork and sample tracks:

```sh
./gradlew :app:testDebugUnitTest --tests '*MusicScreenTest' -PpreviewDir="$PWD/releases/previews"
```

The previews use Robolectric's native graphics renderer. They help inspect
layout and text, but aren't screenshots from an Android device.

## Build

Requirements: Android SDK platform 36, build tools 36.0.0, and a full JDK 17 or
21. Android Studio can provide the SDK and JDK. A Java runtime alone isn't enough.

In Android Studio, open this folder and choose **Run** with a connected phone or
emulator. It supports Android 8.0 and newer.

For the terminal, set `JAVA_HOME` to your JDK. Put your own SDK path in the
ignored `local.properties` file:

```properties
sdk.dir=/path/to/Android/Sdk
```

Then run:

```bash
./gradlew :app:assembleDebug
```

The result is `app/build/outputs/apk/debug/app-debug.apk`. Local checked copies
are kept in `releases/`; GitHub installers use names such as `music-room-v7.apk`.
The number in the filename is Android's version code, not its display version.

The application ID is `local.music.kotlinplayer`, so it can be installed beside
the earlier Java app. This is a debug-signed personal build. Keep the local
`.signing/debug.keystore` if you want future builds to update the same install.
It is private and must never be committed. A fresh clone without that key builds
with your own Android debug key; it can be used for development but won't update
an installation signed with the original key.

## App updates

Install the latest Music Room APK from the
[Android release](https://github.com/HimanshuS073/music_visualizer/releases/tag/android-v1.6).
Earlier APKs don't contain the updater.

On launch, Music Room checks public GitHub releases in the background. When a
newer Android version exists, it shows **Update available**. Choose **Yes, update**
to download it, then confirm installation in Android's installer. The first time,
Android may ask you to enable **Allow from this source** for Music Room. Return
to the app after enabling it. Updates replace the same installed app and preserve
its settings. Android handles the installation location; no folder selection is
needed. Music stops while Android replaces the app.

You can also use **Settings → Check updates**. Startup failures stay quiet so
offline use doesn't interrupt playback; manual checks display errors. Automatic
checks are limited to once every five minutes in the running process, avoiding
repeated checks during screen rotation. No GitHub token is stored in the app.

Downloads go into the private app cache. The updater verifies the expected size,
GitHub's SHA-256 digest, application ID, higher version code and matching signing
certificate before handing the APK to Android. Old installed-version APKs and
stale partial files are removed during a later check. Cancelling a download or
destroying the window cancels that request. The separate music service continues
playing. Pending installer permissions survive normal activity recreation.

The updater looks through the 100 most recent releases, ignores drafts and
prereleases, and only accepts Android tags like `android-v1.3` with assets named
`music-room-v7.apk`. Desktop releases don't affect it. The asset must include
GitHub's SHA-256 digest. It selects the highest newer version code.

## Publishing the next Android update

Keep the original signing key backed up privately. Increase both `versionCode`
and `versionName` in `app/build.gradle.kts` for every release. Keep the application
ID unchanged. Set up the SDK/JDK as described above and authenticate the GitHub
CLI (`gh auth login`).

Build, test and prepare the local APK:

```sh
python3 tools/release_android.py
```

Review and commit the source, then publish:

```sh
python3 tools/release_android.py --publish
```

The helper checks the original signing certificate, builds and runs tests/lint,
uploads a draft release, compares GitHub's asset digest, then publishes it.
Tags follow `android-v<versionName>` and APK names follow
`music-room-v<versionCode>.apk`. Existing desktop releases aren't replaced.
The source must be committed first so the release points to the matching code.
Keep unique version names and codes; the helper won't overwrite a published
release. Its source check covers this Kotlin folder, so unrelated untracked
files elsewhere in the repository do not block publication. A draft left by an interrupted upload can be resumed.
Nothing has to be added to GitHub Secrets, and the signing key never leaves
your machine. A GitHub APK update is user-approved, not a silent install.

## Tests

Run the offline checks:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

To also check live YouTube search, thumbnails and a small stream range in memory:

```bash
./gradlew :app:testDebugUnitTest -PliveTests=true --rerun-tasks
```

The tests cover FFT signals, invalid data, HTTP cancellation and image size
limits. Robolectric checks service ownership, backgrounding the window and
removing the task on simulated API 28. Robolectric is a test dependency and is
not included in the APK. Real-device audio, notifications and screen-lock behavior
still need phone testing. See `TEST_REPORT.md` for this build's results.

## Files

- `MainActivity.kt` handles the window, searches, selection and settings.
- `PlaybackService.kt` owns playback, stream resolution, the media session and notification.
- `MusicSearch.kt` handles NewPipe searches, stream resolution and image requests.
- `Fft.kt` contains the FFT math, without Android dependencies.
- `SpectrumView.kt` reads PCM buffers and draws the bars.
- `ReleaseUpdates.kt` checks GitHub releases and downloads verified bytes.
- `UpdateManager.kt` handles update prompts, progress and cancellation.
- `UpdateInstaller.kt` checks the APK and opens Android's installer.
- `tools/release_android.py` builds and publishes Android releases.
- `app/src/test/` contains JVM tests.

The app uses standard Android views, Media3 ExoPlayer and Session 1.8.0, NewPipe Extractor v0.26.5,
OkHttp 4.12.0, AndroidX Core 1.16.0 and Kotlin's standard library. It doesn't require Python, mpv or
Termux. Gradle 8.13, Android Gradle plugin 8.13.2 and Kotlin 2.3.0 are pinned in
the build files. It targets API 36.

The app's code uses GPL-3.0-or-later. Dependencies retain their own licenses;
copies are bundled under `app/src/main/assets/licenses/`.

Background playback follows [Android’s MediaSessionService guide](https://developer.android.com/media/media3/session/background-playback).
