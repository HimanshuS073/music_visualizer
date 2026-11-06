# Music Room 1.8 screen-off playback verification

Checked on November 6, 2026.

## Build

- Version 1.8, code 9; `local.music.kotlinplayer`, minimum API 26, target API 36.
- Gradle 8.13, AGP 8.13.2, Kotlin 2.3.0, JDK 17.
- APK: `releases/music-room-v9.apk`, 8,698,059 bytes.
- SHA-256: `475a093b821ccf1dcc52b14a0b23f8736bce38a7991d16fe4960503b2f59cbd8`.
- Signing certificate matches previous Kotlin releases for in-place updates.
- APK signature, ZIP integrity and 16 KiB ZIP alignment checks passed.
- Lint: {'Warning': 30, 'Hint': 1}.

## Changes

The existing foreground MediaSessionService continues to own playback when the
screen turns off. ExoPlayer uses network wake mode to manage CPU and Wi-Fi locks
while playing or buffering. A separate partial CPU wake lock covers stream
resolution before preparation, with a two-minute safety timeout. It is released
after preparation, on loading failure, pause, song replacement, task removal,
and service destruction. The display is never kept lit.

Visualizer/video sampling stays detached when the window stops. Swiping the app
out of Recents still stops playback. No dependencies were added. The golden logo,
thumbnail background, settings and updater are unchanged.

## Tests

```sh
./gradlew --no-daemon --console=plain :app:assembleDebug :app:lintDebug :app:testDebugUnitTest -PliveTests=true -PpreviewDir="$PWD/releases/previews"
```

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| FftTest | 7 | 0 | 0 | 0 |
| LauncherIconTest | 2 | 0 | 0 | 0 |
| LiveSearchTest | 1 | 0 | 0 | 0 |
| MainSettingsTest | 2 | 0 | 0 | 0 |
| MusicScreenTest | 12 | 0 | 0 | 0 |
| MusicSearchTest | 8 | 0 | 0 | 0 |
| PlaybackServiceTest | 5 | 0 | 0 | 0 |
| ReleaseUpdatesTest | 10 | 0 | 0 | 0 |
| StreamPreparationLockTest | 2 | 0 | 0 | 0 |
| UpdateInstallerTest | 7 | 0 | 0 | 0 |

The lifecycle test simulates a non-interactive device and SCREEN_OFF broadcast,
stops and destroys the Activity, and checks that the service retains playback
intent and its queue while video is disabled. Loading-lock tests cover repeated
acquisition, pause/resume, task removal and service destruction. Live search,
thumbnail download and an audio stream range also passed.

## Limits

Robolectric tests run on simulated API 28. They do not prove physical audio
output, Wi-Fi stability, hardware suspend behavior or manufacturer battery
policies. Test on a phone by starting a song, locking the display for several
minutes, unlocking, pausing/resuming from the media notification, and checking
that removing the app from Recents stops playback. Long-duration screen-off
playback still needs that device check.
