# Read-only extracted-reference audit — October 13, 2026

Starting application SHA: `8ff418dc86df0660a75bb419d3d7630a31e0401f`.
Reference: `/Users/PUBLIC_USER/video editor/fancut`, preserved untracked.
No file from this directory was copied into application sources or assets.

## Inventory and identification

Static `aapt2` metadata identifies `com.lemon.lvoverseas`, version 19.7.0,
versionCode 19700100, minSdk 23, targetSdk 36. This describes the supplied
extraction, not the latest installed/public competitor version.

27,969 files total, 851,938,323 logical bytes (disk allocation about 894 MB):
38 root DEX files; binary manifest/resources table; 25,264 `res/` files;
1,170 assets; 253 Compose resources; 345 native libraries; dependency metadata
and third-party notices. Native ABIs: 173 arm64-v8a and 172 armeabi-v7a libraries.
There is no editable Android source project here.

The resource table contains 11,315 drawable, 5,058 layout, 13,956 string,
203 anim, 28 animator, 8 menu and 8 navigation entries, plus other types.
These counts are resource identifiers, not independently usable assets or
feature counts. Obfuscated directories obscure normal resource-path categories.
Locale metadata includes Arabic, Persian, Hebrew and many other configurations;
translation presence is not proof of correct shaping or product support.

Assets include 769 JSON, 115 ZIP, 168 PNG and 47 TTF files. Sample Lottie
metadata: `manual_zoom_tips.json` uses a 500×400 canvas, 60 fps and six layers;
`music_playing.json` uses 48×48, 40 fps and four layers. These are reference
animation metadata, not assets approved for reuse. ZIP directory listings of
`MattingBlendV2.zip` and `mattingBlend.zip` expose Lua/JS metadata, scene/config,
mesh and vertex shader paths. No contained program was executed or adopted.
Native names include `libnative-filters.so`, `libnativeaudio.so`,
`libaudioeffect.so` and audio fingerprint/upload components; names do not
establish their algorithms, licenses or suitability.

## Functional discovery (Category B)

Resource identifiers such as `btnKeyframe`, `keyFrameGraphsView`, `cbChroma`,
`image_chroma_scroll_layout` and `freeze_material_group` suggest keyframe graph,
chroma selection and freeze-material controls. Subtitle and transition identifiers
also exist, alongside AI-specific controls excluded from this project phase.
Identifiers are evidence of control categories; an extraction cannot verify
live usability, source timing, preview/export correctness or offline availability.

Original product opportunities, in required order: join-based transition picker
with explicit duration/trim-handle validation; shared transform keyframe
navigation/easing; speed curves with explicit source-time mapping; masks/chroma
and blend controls; subtitle timing/style tools; imported transparent graphics;
audio envelopes/ducking; backgrounds and device-qualified export; editable local
templates. Keep the existing charcoal/blue/cyan editor and contextual tools.
Do not trace competitor artwork, layouts, effects or presets.

## Licensing disposition

**Category A, approved extracted resources: none.** Local notices identify some
separately licensed components, but this audit has not matched extracted binaries
or fonts to exact upstream distributions/hashes and complete applicable terms.
No extracted item is approved for incorporation at this checkpoint.

Candidate provenance includes AMS OFL 1.1 notices naming eufb10/eufm10/msam10/msbm10,
Knuth/dsrom notices, a Room Apache-2.0 license and publicsuffix MPL-2.0 notices.
The [upstream JLaTeXMath repository](https://github.com/opencollab/jlatexmath)
is a candidate provenance source; its repository-level licensing cannot be
substituted for per-font terms or assumed to cover the whole APK. Prefer new
upstream acquisition and explicit per-file matching if these are ever needed.
Math fonts do not address Pashto/Arabic text needs. Existing Android system font
families require no font-file extraction or packaging.

**Category C:** all unverified artwork, icons, stickers, music, font binaries,
Lottie animations, templates, effects, ZIP shaders/configs, DEX code and native
libraries. Do not reuse. A neighboring permissive notice does not license the
application or every resource. See [license inventory](THIRD_PARTY_RESOURCE_LICENSES.md).

## Original implementation and security

Retain pinned Media3 and the Apache-noticed preview extension. Implement small
original GPU effects with a shared evaluator and decoded preview/export tests.
Use Android shaping/system fonts first; acquire any additional Arabic-script
font directly from a verified upstream source with exact license/hash records.
Use user-owned music/PNG imports and original geometry; avoid new cloud/analytics
SDKs or unknown native dependencies. No dependency was added in this audit.

Only filesystem reads, JSON metadata, ZIP member listings, hashing and Android
resource-tool static decoding were used. The metadata ZIP was created outside
the reference using copies of manifest/resources only. It intentionally omits
referenced drawable/layout files; `aapt2` warnings about those missing paths are
a tooling-snapshot limitation, not proof the original extraction is corrupt.
No DRM bypass, account access, network calls to reference services, DEX execution,
native-library loading or shader execution occurred.

Evidence root: `/Users/PUBLIC_USER/video-editor-compatibility-evidence/20261013/`:
`reference-inventory.json`, `reference-hashes.json`, `reference-badging.txt`,
`reference-resources.txt`, `reference-metadata.zip`, `reference-metadata-warnings.txt`.
Application Gradle source-set inspection finds no `fancut/` packaging path.
Final comparison of all 27,969 SHA256 hashes is unchanged (`reference-integrity.json`).
Debug/release APK and AAB scans contain no fancut paths or sampled proprietary
native/font/effect names (`packaging-check.json`); no new application resources
or dependencies were added.
