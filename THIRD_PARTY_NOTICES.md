# Third-party notices and asset provenance

- AndroidX Media3 1.5.1 and adapted source under
  `core/media/src/main/java/androidx/media3/` use Apache License 2.0.
  Preserve source copyright/license headers, the full license in
  `app/src/main/assets/licenses/Apache-2.0.txt`, the adjacent Media3 NOTICE and
  the modification record in `docs/third-party/MEDIA3_PREVIEW_PATCH.md`.
  Upstream: https://github.com/androidx/media/tree/1.5.1 .
- The Gradle wrapper is included to bootstrap the pinned build distribution;
  its launcher retains its Apache 2.0 header. Other libraries are resolved from
  their official Maven coordinates in `gradle/libs.versions.toml`; no proprietary
  competitor SDK or extracted application is bundled.
- Waterfall test media: Mike Dickison (Giantflightlessbirds), *Belong Waterfall
  video MRD*, 11 May 2025, CC BY 4.0. The source, license link and transcoding
  changes are recorded beside each copy in `WATERFALL_LICENSE.md` and
  `PREMIUM_WATERFALL_LICENSE.md`.
  Source: https://commons.wikimedia.org/wiki/File:Belong_Waterfall_video_MRD.webm .
- Other fixture documentation identifies locally generated test patterns,
  synthesized tones and silence; generation scripts are retained in `scripts/`.
- Text uses device font families; no commercial font files are bundled.

## Owner-supplied artwork

On 22 October 2026 the project owner stated that the supplied asset kit and
bundled premium artwork were generated with ChatGPT, and requested their public
publication. This provenance statement covers the required `editor_kit_*`
rasters, `ic_editor_*.xml` vectors and `editor_sticker_premium_*` artwork.
The kit's effect thumbnails are derived from its reference scene; only resources
already required by the application are included. The unused reference kit,
reference screenshots and extracted competitor application remain excluded.
No third-party stock-art license or broader project license is invented here.
Per-file SHA-256 values are recorded in `docs/ASSET_PROVENANCE.json`.

No new license is assigned to original project code by this notice.
