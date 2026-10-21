# fancut offline reference audit — October 13, 2026

> Superseded inventory/approval status: see [current reference audit](FANCUT_REFERENCE_AUDIT.md)
> and [license inventory](THIRD_PARTY_RESOURCE_LICENSES.md). Candidate font notices
> do not establish exact binary provenance; no extracted resource is approved for reuse.


The preserved `fancut/` directory is an extracted Android application, approximately
894 MB, with 1,170 asset files. It contains compiled DEX code, native libraries,
local assets, analytics and cloud/AI configuration. It is not an Android source
project that can be merged into this editor.

Useful offline candidates inspected:

| Resource | Potential use | Current disposition |
| --- | --- | --- |
| assets/blendColors.txt, assets/newBlendColors.txt | Reference for a manual color-picker palette | Plain hex colors inspected; no new UI added before PiP passes |
| assets/fonts/euler/jlm_eufb10.ttf, jlm_eufm10.ttf and assets/fonts/maths/jlm_msam10.ttf, jlm_msbm10.ttf | Optional mathematical text glyphs | Matching AMS SIL OFL notice exists at assets/fonts/licences/OFL.txt; not yet integrated or tested in editor |
| assets/fonts/licences/License_for_dsrom.txt, Knuth_License.txt | Provenance for other bundled math fonts | Retained; per-font mapping still needed |
| assets/manual_zoom_tips.json | Reference for gesture instruction animation | Inspected filename; no dependency on this animation added |
| assets/zip/MattingBlendV2.zip, mattingBlend.zip, DarkMask.zip | Reference candidates for later mask/blend investigation | Not unpacked or integrated; milestone gate remains closed |
| lib/*/libnative-filters.so | Compiled native filter implementation | Not linked; no Kotlin/API/source contract established |

Cloud-upload, automatic-cutting, smart-crop and AI-writer assets are outside the
requested offline scope. No AI, cloud SDK, analytics SDK, proprietary DEX or
native engine was added. No reusable resource is claimed to implement a complete
editing feature. The offline feature roadmap remains in
PROFESSIONAL_EDITOR_FEATURE_MATRIX.md and cannot advance past its PiP gate yet.
