# Music Room 1.5 verification

Checked on November 5, 2026.

## Build and APK

- Version 1.5, code 6; app ID `local.music.kotlinplayer`; min API 26, target 36.
- Gradle 8.13, AGP 8.13.2, Kotlin 2.3.0, JDK 17.
- APK: `releases/music-room-v6.apk`, 7,983,287 bytes.
- SHA-256: `278f725873ea014fe797b84abdfb48368c8377524e659ac32c9189864e027433`.
- Original signing certificate matches earlier Kotlin releases. APK v2 signature,
  ZIP integrity and 16 KiB alignment checks passed.
- Lint: 0 errors, 29 warnings, 1 hint; no lint errors suppressed.
- No new runtime dependencies or additional video player/stream.

Command from this folder with SDK/JDK configured:

```sh
./gradlew --no-daemon --console=plain :app:assembleDebug :app:lintDebug :app:testDebugUnitTest -PliveTests=true -PpreviewDir="$PWD/releases/previews"
```

## Tests

| Suite | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| FftTest | 7 | 0 | 0 |
| LiveSearchTest | 1 | 0 | 0 |
| MainSettingsTest | 2 | 0 | 0 |
| MusicScreenTest | 12 | 0 | 0 |
| MusicSearchTest | 8 | 0 | 0 |
| PlaybackServiceTest | 4 | 0 | 0 |
| ReleaseUpdatesTest | 10 | 0 | 0 |
| UpdateInstallerTest | 7 | 0 | 0 |
| VideoBackdropTest | 6 | 0 | 0 |

All 57 tests passed. The opt-in live YouTube search, thumbnails and small stream
range test also passed. Existing updater, APK validation, search cancellation,
FFT, playback lifecycle and result-hiding tests still pass.

New tests check:

- Saving and restoring video-background and visualizer-height preferences.
- Invalid height inputs leave the dialog open and do not save changes.
- Larger/smaller bars, preserved desired height across rotation, screen-fit limits,
  space for results and unchanged height when controls hide.
- Blur on constant colors and high-contrast edges, bounded frame sizes, swapping
  frames, dimming state and restoring the original cover when video is disabled.
- Clearing stale frames on track changes, waiting for a rendered video frame,
  and handling disabled background frames without retaining them.

A Save-button test found asynchronous dialog-listener setup. The listener is now
installed immediately after showing the dialog. Frame-clear tests prompted an
explicit drawable clear when no cover/frame is available.

## Rendering and behavior

The existing decoder renders to one small TextureView behind the screen. It
provides the original video colors for the bars and, when enabled, background
frames. The background uses 96×54 frames at the saved 1–10 samples/second setting.
A reusable two-pass box blur runs twice on these small frames. The displayed
image is desaturated, dimmed and covered by the existing dark screen gradient.
Frames stay in memory; no video, music or thumbnails are saved.

Video background is off by default. Switching it off restores the cover without
restarting audio. The cover remains until a real video frame is available.
Visual updates stop when the activity stops; the playback service continues.

Height accepts 24–320 density-independent pixels. The desired value is saved;
actual drawing height is limited to available space on smaller screens. It
stays unchanged between shown/hidden controller states in the same viewport.
Settings use a scrollable form with field labels and validation.

Native renderer previews with generated artwork/frame data are in
`releases/previews/`, including `music-room-video-background.png` and
`music-room-tall-bars.png`. They were visually inspected. They are not captures
from a real phone or proof of hardware video decoding.

## Limits

Tests simulate API 28. Actual codec output, TextureView frame capture, GPU
composition, keyboard/dialog placement and background lifecycle on physical
Android 8–16 devices still need checking. No new phone test or CPU/RAM/battery
benchmark was performed. Rendering is deliberately sampled, not full-rate video.

Signing keys, APKs and previews remain Git-ignored. The unrelated screenshot in
the project folder is preserved and excluded from the release source commit.
