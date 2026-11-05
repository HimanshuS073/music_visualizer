# Music Room 1.7 logo verification

Checked on November 5, 2026.

## Build

- Version 1.7, code 8; `local.music.kotlinplayer`, minimum API 26, target API 36.
- Gradle 8.13, AGP 8.13.2, Kotlin 2.3.0, JDK 17.
- APK: `releases/music-room-v8.apk`, 8,697,375 bytes.
- SHA-256: `2085081c483a8ded125ffecf8bc61ba814f53ffb01236aa40710f59220b5888f`.
- APK v2 signature, ZIP integrity and 16 KiB alignment checks passed.
- Signing certificate matches the previous Kotlin releases for in-place updates.
- Lint: 0 errors, 30 warnings and 1 hint.

## Logo

The supplied `Golden Music Room Studio Logo.png` was copied unchanged to
`app/src/main/res/drawable-nodpi/music_room_logo.png`. Original and bundled source
bytes match. The original file in the project root was preserved.

The manifest launcher and round icon reference `mipmap/ic_launcher`. An adaptive
icon combines a black background with fractional foreground insets, keeping the
landscape image's aspect ratio and complete lettering. The notification keeps
its separate small symbol; it does not use the full-color raster logo.

## Tests

Command with SDK/JDK configured:

```sh
./gradlew --no-daemon --console=plain :app:assembleDebug :app:lintDebug :app:testDebugUnitTest -PliveTests=true -PpreviewDir="$PWD/releases/previews"
```

| Suite | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| FftTest | 7 | 0 | 0 |
| LauncherIconTest | 2 | 0 | 0 |
| LiveSearchTest | 1 | 0 | 0 |
| MainSettingsTest | 2 | 0 | 0 |
| MusicScreenTest | 12 | 0 | 0 |
| MusicSearchTest | 8 | 0 | 0 |
| PlaybackServiceTest | 4 | 0 | 0 |
| ReleaseUpdatesTest | 10 | 0 | 0 |
| UpdateInstallerTest | 7 | 0 | 0 |

All 53 tests passed, including the live YouTube check. New native renderer tests
load the actual icon through ApplicationInfo, verify the 1536×1024 supplied
bitmap, and check that circular and rounded-square masks preserve the artwork.
The visible-pixel count matches the unmasked version within a small tolerance;
48-pixel icon renders are also checked for visible content.

Previews in `releases/previews/music-room-launcher-circle.png` and
`music-room-launcher-rounded-square.png` were inspected. Independent PNG reads
confirmed equal visible-artwork counts in the exported previews. These are
native resource renders, not screenshots from a physical Android launcher.

Existing playback, search, thumbnail backgrounds, settings, result hiding and
update validation tests still pass. The removed video-background feature was
not restored. No runtime dependency was added.

## Limits

Tests simulate API 28. Physical launcher appearance, vendor icon packs, launcher
caching and Android system splash presentation still need checking on a phone.
No optional monochrome icon was supplied; the intended artwork is full color.
Lint reports that recommendation and the redundant v26 folder qualifier, along
with existing localization, dependency-update and backup warnings.

The private signing key and generated APKs/previews remain excluded from Git.
Only the copied app resource is included in source; the root original is not
added as a duplicate repository asset.
