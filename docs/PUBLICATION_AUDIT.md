# Public source publication audit — 22 October 2026

Target: `https://github.com/afghankhan12123/new` (public, visibility unchanged).
GitHub reported an empty repository (no refs; commits API returned HTTP 409).
Write permission was confirmed for the repository owner's account before upload.

## Source selection

The source project has 14 Android modules and the `buildSrc` convention build.
The local branch is `codex/post-release-audit`, based on `a33d8da7`, with substantial
subsequent uncommitted Stage 9 implementation and test changes. The publication
snapshot includes those current files, including preview loading/readiness,
seek dispatch/reset/period mapping, transition compositor routing, playback
watchdogs, source timing, UI/tool/history repairs and durable media imports.
No application functionality or dependency versions were changed for publication.
The production source, tests, build configuration and required resources were
copied byte-for-byte. Documentation and one diagnostic helper were sanitized for
portable public use. The original working tree and its local history are retained.

The public repository starts with a separate audited source snapshot. It does
not inherit local historical refs, competitor inputs, raw diagnostic evidence,
private metadata or old personal commit identity. This creates initial history
in an empty repository; it does not rewrite public history or use a force push.

## Security review

A pattern-based byte scan examined the original 670 candidate files and all
reachable local Git objects: 225 commits, 29,957 blobs and 5,380 trees, totaling
958,585,954 uncompressed object bytes. Patterns covered private keys, common
provider tokens, credential assignments, authenticated/signed URLs, personal
home paths, known physical-device identifiers and private network addresses.
Candidate URL domains and email-like matches were also reviewed.

The initial source candidates contained personal filesystem paths and a physical
device serial in historical documentation. Public copies replace home identities
and redact the serial. The diagnostic shell runner discovers its project root
and accepts SDK/ADB configuration instead of embedding a personal project path.
No detected credential pattern remained in the final candidate source scan.
This is a scoped scan and manual review, not a guarantee against every possible
secret encoding or undiscovered issue.

Local historical snapshot refs contain extracted `fancut/` binaries/resources,
including a Google API-key pattern, and additional credential/URL-like strings
that were not validated or used. Old evidence also contains private-network
and personal path/device metadata. These objects are excluded entirely from
publication. Ownership, validity and any prior external disclosure of the
historical credential-like material are not established. The target repository
was empty, so no existing exposure was found there. If any real credentials in
this local history were shared elsewhere, their owners should revoke/rotate
them and plan safe history remediation. No local or public history was rewritten.

Signing files/passwords, local SDK configuration, service-account credentials,
cloud configuration, IDE/cache data, APK/AAB output and private evidence are
excluded. `local.properties.example` contains only a placeholder SDK path.

## Licensing and assets

Media3-adapted source retains its Apache 2.0 headers, full license, NOTICE and
existing modification record. The upstream license was checked at:
https://github.com/androidx/media/blob/1.5.1/LICENSE .

The waterfall fixture attribution was checked against the original publisher:
https://commons.wikimedia.org/wiki/File:Belong_Waterfall_video_MRD.webm .
Both bundled copies retain author credit, CC BY 4.0 link and modification notices.
Other existing fixture documentation identifies generated tones, silence and
synthetic test patterns. Device font families do not redistribute font files.

The owner stated that the supplied asset kit and premium artwork were generated
with ChatGPT, resolving the initial missing provenance question. Required
application copies are retained and inventoried in `ASSET_PROVENANCE.json`.
The complete reference kit, extracted competitor application, unrelated reference
screenshots, native binaries and proprietary SDKs are excluded. Dependencies
resolve through declared official Maven coordinates. See `THIRD_PARTY_NOTICES.md`.
No new project-wide license was selected without an explicit owner choice.

## Clean source verification

Built in a separate source-only directory with JDK 17, Android SDK 36 and the
existing dependency cache; no original project build outputs were copied.
Gradle used offline dependency resolution and normal build-cache reuse.

```sh
./gradlew :app:assembleDebug test :app:assembleDebugAndroidTest \
  :core:media:assembleDebugAndroidTest \
  :feature:editor:assembleDebugAndroidTest --offline --console=plain
```

Result: **BUILD SUCCESSFUL**, 788 actionable tasks (473 executed, 315 from cache).
The test tasks executed for both variants; their XML reports contain:

| Suite | Debug tests | Release tests | Failures/errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| core/model | 95 | 95 | 0 | 0 |
| core/media | 52 | 52 | 0 | 2 total |
| editing | 171 | 171 | 0 | 0 |
| feature/export | 1 | 1 | 0 | 0 |
| feature/home | 3 | 3 | 0 | 0 |

Total: **642 passed, two skipped, zero failures/errors**, across debug and
release variants (322 cases per variant, not 644 unique test methods).
The skipped retained-native-PCM check requires external evidence fixtures.
App, media and editor instrumentation APKs compile. No device tests were run
for publication. This does not certify runtime smoothness, physical encoders,
preview/export parity or complete device acceptance. The retained device report
records a photo/lifecycle backward-seek failure; later changes require hardware
verification. The build used cached dependencies and is not proof of a fresh
network dependency download.

## Exclusions and reproducibility

The public snapshot omits generated build/cache/native output, machine-specific
configuration, signing material, raw logs, screenshots/recordings/evidence,
exports, release artifacts, duplicate backups and reference-only directories.
Required test fixtures, shaders embedded in source, resources, manifests,
ProGuard rules, version catalog, Gradle conventions and wrapper are retained.
The largest required file is approximately 1.1 MB; Git LFS is unnecessary.
The Gradle wrapper JAR is the intentional bootstrap-binary exception.

Historical evidence links in retained documents may point to local material
excluded from public source. They are historical context, not public artifacts.
The README and source map identify current entry points and known limitations.
