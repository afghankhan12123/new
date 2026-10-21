# Android Video Editor

A native Android video editor using Kotlin, Jetpack Compose, Media3, Room,
DataStore and Hilt. Editing uses a non-destructive project model with integer
microsecond timing. The application works offline and includes no AI features.

## Current source

This snapshot includes the Stage 9 working-tree changes after local checkpoint
`a33d8da7`, including preview loading/readiness, bounded seek dispatch, transition
input ownership, playback watchdogs, canvas presentation, durable media import,
and editor/history fixes. Application behavior is preserved for publication.

Build success does not certify playback smoothness or hardware compatibility.
The latest retained device report documents a photo/lifecycle backward-seek
failure and slow startup requiring recovery. Later source fixes must be verified
on hardware; this repository does not claim final device acceptance.

Start with [the source map](docs/SOURCE_MAP.md),
[architecture](docs/ARCHITECTURE.md),
[Stage 9 preview lifecycle](docs/STAGE9_PREVIEW_FRAME_LIFECYCLE.md),
[seek architecture](docs/STAGE9_SEEK_AND_SCRUB_ARCHITECTURE.md), and
[the device test report](docs/STAGE9_NE2211_DEVICE_TEST_REPORT.md).
Historical reports describe their own checkpoints and may precede current code.
Evidence links may refer to private local artifacts intentionally withheld from
public source distribution.

## Build from a clean checkout

Requirements: JDK 17, Android SDK Platform 36, Android SDK Build Tools 34.0.0,
and access to Google Maven, Maven Central, Gradle Plugin Portal and Gradle's
wrapper distribution. Minimum device API is 26; target API is 36.
The project pins Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21 and Media3 1.5.1.

1. Set `JAVA_HOME` to JDK 17.
2. Set `ANDROID_HOME` to your Android SDK, or copy `local.properties.example`
   to `local.properties` and set `sdk.dir`.
3. Run:

```sh
./gradlew :app:assembleDebug --console=plain
./gradlew test --console=plain
```

The debug APK is generated in `app/build/outputs/apk/debug/`.
The Unix wrapper and wrapper JAR are included. On Windows, use WSL or Git Bash
with a compatible JDK and SDK; a Windows batch wrapper is not bundled.
No API key, cloud account or production signing credential is required to build
the debug application. Release signing is configured by the distributor outside
this repository; normal release output is unsigned.

For an attached test emulator/device, build instrumentation APKs with:

```sh
./gradlew :app:assembleDebugAndroidTest :core:media:assembleDebugAndroidTest \
  :feature:editor:assembleDebugAndroidTest --console=plain
```

Connected tests install applications and exercise media operations; use a
suitable test device and read [testing instructions](docs/TESTING.md) first.
Some specialized audio tests require external retained PCM fixtures and skip
when those fixtures are absent. Python analysis scripts may also require
FFmpeg/FFprobe, NumPy, Pillow or OpenCV; inspect each script's imports and help.

## Modules

| Module | Responsibility |
| --- | --- |
| `app` | Application, activity, navigation, manifests and integration tests |
| `core/common` | Shared types, dispatchers and identifiers |
| `core/model` | Projects, clips, render plans, timing, effects and keyframes |
| `core/designsystem` | Compose theme, typography, colors and components |
| `core/database` | Room project index and serialized document persistence |
| `core/datastore` | User preferences |
| `core/media` | Metadata, decode, preview, composition, GPU effects, audio and export |
| `editing` | Edit commands, timeline math and undo/redo |
| `feature/home` | Home screen |
| `feature/projects` | Project management |
| `feature/mediaimport` | Import and project creation |
| `feature/editor` | Preview, timeline, tools, gestures and panels |
| `feature/export` | Export configuration, progress and destinations |
| `feature/settings` | Application settings |
| `buildSrc` | Build conventions and the pinned Media3 AAR transformation |

Existing functionality includes trimming/splitting, multi-track overlays,
transitions, filters/effects, masks/chroma/blending, text/stickers, speed and
speed curves, supported visual keyframes, audio mixing/automation/EQ/ducking,
MP4 export and project persistence. See the
[tool audit](docs/STAGE9_ALL_TOOLS_SOURCE_AUDIT.md) for actual coverage and gaps.
Do not infer support for every tool on every layer: PiP crop/split/speed authoring,
arbitrary text/sticker transform keyframes, custom chroma sampling and persisted
undo history have documented limitations.

## Troubleshooting

- SDK missing: configure your own SDK path and install Platform 36/Build Tools
  34.0.0; accept the Android SDK licenses locally.
- Dependency download failure: verify access to the repositories above. The
  first build needs network access; dependencies are not vendored.
- Preview/export changes after a dependency update: inspect
  [the Media3 patch record](docs/third-party/MEDIA3_PREVIEW_PATCH.md) and
  `buildSrc/src/main/kotlin/Media3CompositorPatch.kt`. The AAR transform and
  source overrides are tied to Media3 1.5.1; upgrade them together.
- Hardware playback/encoder failures: reproduce using synthetic fixtures and
  record app-only diagnostics locally. Never commit private media or device logs.

## Licenses and publication

Preserve the Apache 2.0 license, source headers and Media3 NOTICE in
`app/src/main/assets/licenses/`. Waterfall test fixtures have their own CC BY 4.0
attribution beside the media. See [third-party notices](THIRD_PARTY_NOTICES.md)
and [the publication audit](docs/PUBLICATION_AUDIT.md).
A public repository does not select a new license for original project code;
no new project-wide license has been assigned by this publication task.
