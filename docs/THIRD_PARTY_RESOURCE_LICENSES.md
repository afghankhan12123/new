# Resource licensing inventory — October 13, 2026

No extracted competitor resource has been included in the application.
Unknown provenance remains excluded; this inventory records evidence and
engineering disposition, not a blanket legal certification of the reference.

| Resource/component | Evidence | Classification / allowed action | Included? |
|---|---|---|---|
| Existing Media3 1.5.1 and preview adaptations | `docs/third-party/MEDIA3_PREVIEW_PATCH.md`, preserved source headers, packaged Apache-2.0 and Media3 NOTICE | Existing independently sourced Apache-2.0 dependency; preserve attribution/patch records | Yes, unchanged |
| Android system font families | `core/model/.../TextFonts.kt` chooses Typeface family names | Uses device fonts; no extracted font files redistributed | System use only |
| Reference AMS math fonts (eufb/eufm/msam/msbm) | `fancut/assets/fonts/licences/OFL.txt`, named reserved fonts and OFL 1.1 terms | Candidate independently licensed resource; extracted identity/hash/upstream match still unverified, so no approval to copy | No |
| Reference Computer Modern/other math fonts | `Knuth_License.txt`, `License_for_dsrom.txt` | Per-file identity and license mapping incomplete; obtain verified upstream files if needed | No |
| Reference Room component | `fancut/META-INF/androidx/room/room-common/LICENSE.txt`, Apache-2.0 | Acquire through established official dependency coordinates; no reference binary reuse | No reference copy |
| Public suffix data | Both `okhttp3/.../NOTICE` files identify publicsuffix data and MPL-2.0 | Candidate data source, exact version/modification and obligations unverified; unnecessary to editor | No |
| Reference shaders/native libraries/DEX/presets | No complete established redistribution provenance | Category C, prohibited from reuse | No |
| Reference stickers/music/icons/fonts/animations/templates | APK containment and filenames only, except candidate notices above | Category C until exact identity and rights are established | No |
| Broad workflow concepts | Static control labels and public official product descriptions | Category B; independently design/implement, without copying assets/code/presets/branding | Concepts only |

New bundled resources require upstream URL, exact version, per-file SHA256,
license text, attribution, modification record and packaging review before use.
No new dependency or bundled creative asset was added at this checkpoint.
The existing [Media3 attribution](third-party/MEDIA3_PREVIEW_PATCH.md) remains authoritative.
