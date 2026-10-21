# Stage 9 — CapCut engine identification

21 October 2026. Scope: owner-authorized, read-only package metadata, SDK class/type identifiers and standard ELF dependency tables. No disassembly, method bodies, shader/asset extraction, library loading, execution, decryption, network upload or proprietary integration. `fancut/` and the target asset kit were not modified. Evidence: `deliverables/stage9-seek-preview/evidence/reference-engine-metadata.json` and the original metadata reader beside it. The reader's output was reduced to representative engine identifiers; it is not an APK copy.

## What the supplied extraction establishes

| Evidence | Classification | What it establishes / limits |
| --- | --- | --- |
| `assets/ve.version.lock`: `21.2.0.0_alpha_20260330144010_5a1aa791-lv` | Confirmed metadata | A VE build marker, not a public SDK release/version guarantee. |
| DEX type descriptors `com.ss.android.vesdk.VESDK`, `VEListener$VEEditorSeekListener`, `VEListener$VEEditorCompileListener` | Confirmed packaged identifiers | VESDK-family editor seek/compile interfaces are referenced. Descriptors alone do not prove which methods execute in the editor. |
| `com.ss.android.ttve.mediacodec.TEMediaCodecDecoder`, its `OESTextureRender` nested type, `TEMediaCodecResourceManager` | Confirmed packaged identifiers | MediaCodec/texture/resource-management components. Active codec choice and persistent pool sizes are not established. |
| `libttdemux.so` DT_NEEDED includes `libttffmpeg.so`; `FFMpegInvoker` descriptor | Confirmed package dependency and identifier | FFmpeg-related demux infrastructure; exact upstream configuration/license/build and whether it performs timeline decoding are not established. |
| `libtexturerender_native.so` DT_NEEDED includes `libEGL.so`, `libGLESv2.so`; same graphics dependencies in `libAGFX.so` | Confirmed ELF dependency | Native EGL/GLES-capable components; not proof that every editing operation uses GLES. |
| `libttmplayer.so` links demux/FFmpeg/EGL/GLES, audio-effect and ByteVC decoder libraries | Confirmed ELF dependency | A native media stack is packaged. Video playback infrastructure is not necessarily the edit timeline engine. |
| `libnative-filters.so` links C++ runtime/JNI graphics | Confirmed ELF dependency | Native processing component; exact filter implementation is outside scope. |
| `libcapcut.so` is not a standard ELF64 file | Inspection stopped | No attempt to unwrap, decrypt or bypass protections. |
| Exact NLEModel/NLEEditor SDK identifiers/version | Not established | Narrow descriptor search did not establish these exact types. Absence from searched tables does not prove absence from the application. |
| Vulkan editing, exact cache size, predictive decoding, custom hardware decoder, separate native preview/export graphs | Not established | No runtime trace or implementation inspection. Filenames and smooth UI are insufficient evidence. |

Package identity/version history is in [FANCUT_REFERENCE_AUDIT.md](FANCUT_REFERENCE_AUDIT.md). Its previously identified `com.lemon.lvoverseas` 19.7.0 describes that extraction, not the current public application. This delivery rechecks engine metadata, not public app version claims.

## Related public SDK, distinct from CapCut internals

The owner-provided URL used the older `video-editor-sdk` path, which failed to fetch. The official [Android UI integration guide](https://docs.byteplus.com/en/docs/byteplus-video-editor-sdk/docs-integration-guide-with-ui-android-v.4.0.2) is accessible under `byteplus-video-editor-sdk`. Its dependency setup separates NLE and VE versions. The [VE sample structure](https://docs.byteplus.com/en/docs/byteplus-video-editor-sdk/docs-sample-file-structure-android-ve-v.4.0.2) describes the editing SDK package; it is related architectural background, not a specification of this APK.

The official [NLE overview](https://docs.byteplus.com/en/docs/byteplus-video-editor-sdk/docs-nlesdk-overview) describes a data-driven model, an editing/history component and a media session exposing preview/export controls. Our analogous boundaries are Project/ProjectRenderPlan, EditHistory/commands and the independent preview/export sessions. The public design does not disclose CapCut's cache eviction, decoder count, prefetch or request-cancellation implementation. A plausible inference is that separating edit state from execution allows resource reuse; the exact CapCut mechanism remains unknown.

## Principles usable with our legal dependencies

Keep the canonical model independent of clocks and decoders. Keep player/GL resources across ordinary seeks; serialize texture retirement, and admit each input only after its reset resolves. Give decoding useful work between obsolete-target cancellations. Store only the newest pending pointer target. Keep timeline gestures independent of frame delivery. Require composed output before marking a seek successful. Keep preview and export mutable resources isolated while sharing timing/composition math.

Random access still requires a sync sample and dependency decoding. Approximate keyframe seeks can reduce work for an isolated source, but independently snapping transition lanes can produce different project instants. A precise composed settle is mandatory. Warm adjacent decoders, predictive decoding and an owned texture LRU are possible later optimizations, not features established from this APK or implemented here.

## Engine replacement decision

Choose existing Media3 preview repair (Option A); see [decision](STAGE9_PREVIEW_ENGINE_DECISION.md). No evidence establishes that a new commercial engine is necessary. BytePlus's [commercial editor offering](https://www.byteplus.com/id/product/video-editor) is worth a separately licensed evaluation only if the repaired preview still misses owner requirements. Require device tests, custom model/shader/audio portability, offline behavior, binary size, API access, redistribution terms and support costs before adoption. This is not a license to reuse anything in `fancut/`. No new dependency or extracted resource was incorporated.
