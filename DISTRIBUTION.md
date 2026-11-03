# Before sharing a public release

The source code now has a GPL-3.0-or-later license, and the repository includes
third-party notices. That makes the licensing clearer, but adding these files
does not by itself make every existing download ready for public distribution.

## Source and build instructions

For covered binary releases, provide the matching Corresponding Source in the
way required by the applicable GPL/LGPL licenses. This can include build and
installation scripts, configuration, interface files, dependency source and
patches, and other material required by those licenses. A link to an upstream
homepage or a current source branch is not automatically sufficient.

The earlier build scripts and packaging sources were removed during cleanup.
Restore the matching build setup before publishing a release that requires it.
The current source tree alone should not be presented as a complete verified
Corresponding Source package for the existing desktop and Android downloads.

Official references:

- [GPLv3, including Corresponding Source and object-code distribution](https://www.gnu.org/licenses/gpl-3.0.html)
- [GNU guidance on releasing software](https://www.gnu.org/licenses/gpl-howto.html)
- [FFmpeg's redistribution guidance](https://ffmpeg.org/legal.html)

## Dependency notices

Preserve each included component's license, copyright, attribution, and required
NOTICE files. Keep exact component versions and a release-specific dependency
inventory. Include the project's LICENSE and NOTICE in newly built packages and
make the relevant notices available to recipients.

The desktop notices in licenses/ were recovered from the existing packages.
They need a release-specific review, including the bundled libraries, codecs,
Python runtime, standalone yt-dlp executable, and any proprietary components.
The Android dependency table also needs to be completed for transitive
libraries and desugaring support before a public rebuild.

Existing APK, EXE, and .run files have not been rebuilt as part of this license
update. Their embedded notices and app screens have not changed. An APK cannot
be edited after signing without invalidating its signature.

## Music, video, artwork, and service access

This project's software license does not license songs, video frames, cover
artwork, or another service's trademarks. Review content permissions and the
service's terms separately, including any permissions needed for automated
access or playback. Use content you own or are authorized to use in demo videos,
screenshots, and store listings.

- [YouTube Terms of Service](https://www.youtube.com/t/terms)
- [Google Play intellectual-property policy](https://support.google.com/googleplay/android-developer/answer/9888072)

A software license is not an endorsement by YouTube, NewPipe, Google Play, or the
other projects used here. It does not guarantee store approval or prevent legal
disputes. Public releases still need the appropriate technical, licensing,
privacy, and platform-policy checks.
