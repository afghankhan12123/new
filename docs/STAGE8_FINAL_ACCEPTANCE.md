# STAGE 8 — ACCEPTED WITH OWNER-APPROVED EMULATOR SEEK-PERFORMANCE EXCEPTION

**Date:** 2026-10-19  
**Platform / Scope:** Android API 36 ARM64 Emulator (`cricpro_api36`, `emulator-5554`)  
**Production Commit:** `77160497676e0fc6c20951880b42d792255b502c` (`77160497`)  
**Test / Harness Commit:** `97d4e1b55bb2d86eb5ebdbb5bb46ef5be3b31380` (`97d4e1b5`)  
**Branch:** `codex/post-release-audit`  
**Final Formal Status:** **STAGE 8 — ACCEPTED WITH OWNER-APPROVED EMULATOR SEEK-PERFORMANCE EXCEPTION**  

---

## 1. Owner's Explicit Acceptance Decision

Strict seek-performance compliance was not achieved in three of twelve p95 cells. The project owner explicitly approved an emulator-only exception after reviewing the original 240-sample campaign and an additional 60-sample diagnostic campaign. The three exceedances remain recorded as FAIL against their original thresholds. All other evidenced mandatory Stage 8 gates are retained. Physical-device qualification is deferred and is not covered by this exception.

The three affected cells remain formally recorded as:
1. `one-video process-cold` (budget 450.90 ms, measured p95 477.04 ms, diagnostic p95 472.28 ms): **FAIL — OWNER-APPROVED EMULATOR PERFORMANCE EXCEPTION**
2. `two-videos process-cold` (budget 761.20 ms, measured p95 864.90 ms, diagnostic p95 770.01 ms): **FAIL — OWNER-APPROVED EMULATOR PERFORMANCE EXCEPTION**
3. `two-videos alternating` (budget 515.30 ms, measured p95 549.78 ms, diagnostic p95 571.01 ms): **FAIL — OWNER-APPROVED EMULATOR PERFORMANCE EXCEPTION**

---

## 2. Executive Acceptance Summary

All mandatory Stage 8 recovery, safety, correctness, minified-release instrumentation, media regression reconciliation, audio overhead, and export benchmark gates have been successfully qualified and retained:

1. **Safety & Data Preservation:** Verified 100% preservation of all 40 historical deleted files (8 voiceovers, 32 exports) in external backup archive `consumer-data-before.tar` (115 MB, SHA-256 `a9d36d...`). All 11 original user projects in Room DB and 17 newer projects remain intact in the consumer app sandbox. Report: `DATA_PRESERVATION_AUDIT.md`.
2. **Production Source Manifest:** 484/484 files verified against `FINAL_SOURCE_MANIFEST_77160497.json` (SHA-256 `b25d7806ceccd3c55717f5f772ce4fae8fb0b2dee8ef102ae644c438a526b51b`). Zero production code changes (`**/src/main/**`) exist between freeze commit `77160497` and HEAD.
3. **Host Unit Tests:** 530/530 PASS (265 debug + 265 release, 0 failures, 0 errors, 0 skipped) via `./gradlew test --rerun-tasks` with `AAC_CAUSAL_MATRIX`. XML retained in `host-final-freeze-xml/` (62 files).
4. **Editor Timeline & Panels:** 70/70 PASS (0 failures, 0 errors, 0 skipped). XML retained in `editor-full-continuation2-xml/`.
5. **Release Instrumented Tests (Minified R8):** 22/22 PASS (0 failures, 0 errors, 0 skipped) executing on actual non-debuggable minified release APK (`1ffb7a81...`). Report: `FINAL_RELEASE_INSTRUMENTATION.json`.
6. **Reconciled Media Regression:** 177/177 accounted for under the documented emulator decoder-budget protocol (165 uninterrupted single-process passes, 12 environmental goldfish codec-exhaustion failures fully pass in 59/59 isolated re-runs and 34/34 overlay compositing re-runs). Report: `FINAL_MEDIA_CHUNKED_RECONCILIATION.json`.
7. **Audio Correctness & Overhead:** 144 independent FFmpeg checks PASS (max error 0.1282 dB <= 0.15 dB budget). R8 DSP overhead: Combined 70 µs per 1,024-frame block (0.328% CPU of realtime). Reports: `FULLPATH_FFMPEG_VERIFICATION_FINAL_SOURCE.json`, `FINAL_AUDIO_OVERHEAD.json`.
8. **Seek Performance Benchmark:** 240/240 samples independently verified with zero unverified samples. 9 of 12 cells met strict frozen budgets. The 3 exceeded cells are accepted under the owner's explicit emulator-only performance exception. Reports: `FINAL_SEEK_240_VERIFICATION.json`, `STAGE8_SEEK_DIAGNOSTIC_60.json`, `STAGE8_OWNER_EXCEPTION_APPROVAL.md`.
9. **Export Performance Benchmark:** 20/20 process-cold exports on frozen fixture project `b83c7aa4-ba78-4a75-93cf-3ca9476d39fb` on minified R8 APK. 720p median: 3744.239 ms (budget <= 5000 ms). 1080p median: 3863.952 ms (budget <= 6000 ms). Report: `FINAL_EXPORT_20_VERIFICATION.json`.
10. **Artifact & Identity Verification:** Ordinary R8 APK (`app-release-signed.apk`, SHA-256 `1ffb7a81...`) and AAB (`app-release.aab`, SHA-256 `df547229...`) verified non-debuggable and matched to source commit `77160497`. Report: `FINAL_ARTIFACT_IDENTITIES.json`.

---

## 3. Explicit Deferrals & Follow-Up Items

1. **Physical Device Qualification:** **NOT RUN — DEFERRED**. Testing on physical hardware across chipset vendors remains open. See `docs/PHYSICAL_DEVICE_QUALIFICATION_PLAN.md`.
2. **Production Store Signing:** **NOT RUN — DEFERRED**. Production signing with official release keys is deferred to release engineering.
3. **Historical Media Restoration:** **PENDING OWNER AUTHORIZATION**. Follow-up item for selective restoration of the 40 archived files from backup archive `consumer-data-before.tar`.

---

## 4. Evidence References

External evidence directory:  
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage8-continuation-20261019-codex/`

Key retained reports:
- `STAGE8_OWNER_EXCEPTION_APPROVAL.md`
- `STAGE8_ACCEPTANCE_EXCEPTION_REGISTER.json`
- `STAGE8_FINAL_ACCEPTANCE_CLOSURE.md`
- `FINAL_ACCEPTANCE_GATES.json`
- `FINAL_TEST_SUMMARY.json`
- `FINAL_SEEK_240_VERIFICATION.json`
- `STAGE8_SEEK_DIAGNOSTIC_60.json`
- `STAGE8_SEEK_VARIANCE_INVESTIGATION.json`
- `STAGE8_SEEK_FINAL_RECONCILIATION.json`
- `FINAL_EXPORT_20_VERIFICATION.json`
- `FINAL_MEDIA_CHUNKED_RECONCILIATION.json`
- `FINAL_RELEASE_INSTRUMENTATION.json`
- `FINAL_AUDIO_OVERHEAD.json`
- `FULLPATH_FFMPEG_VERIFICATION_FINAL_SOURCE.json`
- `FINAL_ARTIFACT_IDENTITIES.json`
- `FINAL_SOURCE_MANIFEST_77160497.json`
- `DATA_PRESERVATION_AUDIT.md`
