# Music Room 1.4 verification

Checked on November 5, 2026.

## Build

- Version 1.4, version code 5; application ID `local.music.kotlinplayer`.
- Minimum API 26, target API 36; existing signing key preserved.
- APK: `releases/music-room-v5.apk`, 7,978,055 bytes.
- SHA-256: `810b60019cb3502d600c60f42a28ee10230211f71ac77535624ee4996920cfad`.
- Gradle 8.13, AGP 8.13.2, Kotlin 2.3.0, JDK 17.
- Lint: 0 errors, 29 warnings and 1 hint; no lint suppression added.
  Remaining warnings concern localization, dependency/tool updates and backup metadata.
- ZIP integrity, APK v2 signature and 16 KiB ZIP alignment checks passed.
- Certificate matches the previous Kotlin release, allowing an in-place update.
- No new runtime dependencies, bundled fonts, bitmap assets or native libraries.
- Old duplicate local APKs were replaced with one current installer.

Command from this folder with the SDK/JDK configured:

```sh
./gradlew --no-daemon --console=plain :app:assembleDebug :app:lintDebug :app:testDebugUnitTest -PliveTests=true -PpreviewDir="$PWD/releases/previews"
```

## Tests

| Suite | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| FftTest | 7 | 0 | 0 |
| LiveSearchTest | 1 | 0 | 0 |
| MusicScreenTest | 9 | 0 | 0 |
| MusicSearchTest | 8 | 0 | 0 |
| PlaybackServiceTest | 4 | 0 | 0 |
| ReleaseUpdatesTest | 10 | 0 | 0 |
| UpdateInstallerTest | 7 | 0 | 0 |

All 46 tests passed, including actual YouTube search/thumbnail/stream requests.
Playback lifecycle, updater and installer-validation suites still pass.

The nine UI tests check search submission from the button and keyboard,
pause/ten-second actions, accessible timeline adjustment, elapsed/duration
formatting, unknown-duration handling, keeping controls inside the viewport,
keyboard space, unchanged bar height during control hiding, and empty/large-text
states. Native rendering uses simulated Android API 28.

## Rendered previews

Previews in `releases/previews/` use the actual Android Views and Robolectric's
native graphics renderer, with sample tracks and generated test artwork/bar
levels. They are not phone screenshots or evidence of live audio visualization.

- `music-room-393x852.png`: portrait with results and playback.
- `music-room-320x640.png`: a smaller phone viewport.
- `music-room-800x400.png`: landscape with results and player beside each other.
- `music-room-empty.png`: first launch before a search.
- `music-room-large-text.png`: 1.5x text rendering.

These renders were visually inspected. Tests force complete measure/layout
passes because they render the view hierarchy directly rather than running a
real Android window manager. An initial test-only empty-adapter setup and layout
measurement issue were corrected. The previews also exposed the visualizer
being covered by the card; placement now belongs to the screen component and
bars render above the controls. Lint found an API-27 theme attribute in the
API-26 base theme; it was removed, keeping Android 8.0 support.

## Result hiding in 1.4

Selecting a track hides the entire results panel immediately. The playback
area fills the freed space without increasing the bar height. Only submitting
a new search restores the panel. Pause, late result/error callbacks, rotation,
resizing and the keyboard don't restore it. Saved activity state preserves
whether results were hidden, and a fresh window reconnecting to an ongoing
track starts with the results hidden. Queued thumbnail downloads stop on
selection. Search result data isn't deleted just to hide the panel.

Two native UI tests verify hide/show behavior, retained result data, resizing,
keyboard interactions and unchanged bar height. A service/activity test verifies
saved hidden/visible state through window recreation. Native previews named
`music-room-listening*.png` show the hidden-list state using sample artwork.

The release helper now scopes its uncommitted-source check to this Kotlin
project, so unrelated untracked files outside it don't block publishing.

## Interface

Rounded result cards and artwork, consistent sans-serif text, warm dark colors,
mint transport buttons, empty/search states and a compact artwork player replace
the plain system widgets. The player includes a draggable timeline. Landscape
uses two columns; keyboard insets free room for search. Native vector drawing
supplies icons without an icon/font library. Time labels update once per second.
The existing audio service, stream/search backend and GitHub update rules remain.

Source is split into `MusicScreen.kt`, `RoomStyle.kt`, `SongRow.kt` and the
existing activity/backend files. Signing keys, previews and APKs remain ignored
by Git. The unrelated screenshot supplied in the project folder was not changed.

## Limits

ADB found no connected phone. Touch, real keyboard/insets and system-bar
behavior, actual audio/video and FFT rendering still need device testing,
especially on API 26 and Android 14–16. No CPU, RAM or battery benchmark was
performed. Existing simulated background playback tests are not real-device
playback tests. The checked artifact is prepared for the `android-v1.4` release.
