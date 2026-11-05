# Music Room 1.6 verification

Checked on November 5, 2026.

## Build

- Version 1.6, code 7; `local.music.kotlinplayer`, minimum API 26, target API 36.
- Gradle 8.13, AGP 8.13.2, Kotlin 2.3.0, JDK 17.
- APK: `releases/music-room-v7.apk`; 7,979,831 bytes.
- SHA-256: `85682b4ddc8f641e4cd3f8a142019182a9ffda89a47176bbc8b132e789ac65f5`.
- APK signature matches previous Kotlin installs. v2 signature, ZIP integrity
  and 16 KiB ZIP alignment checks passed.
- Lint: 0 errors, 28 warnings and 1 hint.

## Removal

The video background setting, preference reads/writes, frame presentation,
CPU blur helper, feature-specific tests and generated feature preview were
removed. The APK DEX files were checked for the removed renderer class and
preference identifier; neither remains. The old preference, if present in an
existing install, is never read or used.

Only the thumbnail is used for the background. The small video surface still
supplies colors for the visualizer, as it did before the removed feature.
Visualizer height, other settings, automatic result hiding, background music
and GitHub updates remain available.

## Tests

Command:

```sh
./gradlew --no-daemon --console=plain :app:assembleDebug :app:lintDebug :app:testDebugUnitTest -PliveTests=true -PpreviewDir="$PWD/releases/previews"
```

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

All 51 tests passed, including live YouTube search/thumbnail/stream checks.
Settings tests verify that the removed switch is absent while height still
saves/restores and invalid input remains rejected. A native renderer test
verifies that transport, height and result-panel changes do not replace the
thumbnail background, and clearing the cover clears the background.

The deleted feature's tests were removed, not disabled. Existing FFT, layout,
result-hiding, lifecycle, network cancellation and update-validation checks pass.

## Limits

Robolectric tests simulate API 28. Actual audio/video-color sampling, system UI,
installer and lifecycle behavior on physical Android devices still need phone
testing. No new CPU/RAM/battery benchmark was performed. Preview artwork is
sample data, not a phone capture.

Signing keys, APKs and previews remain Git-ignored. Historical release tags are
unchanged; the current source and new APK contain the removal.
