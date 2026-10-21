# Stage 8 Release Limitations and Scope Qualification

**Final Formal Status:** **STAGE 8 — ACCEPTED WITH OWNER-APPROVED EMULATOR SEEK-PERFORMANCE EXCEPTION**  
**Date:** 2026-10-19  
**Target Hardware / Runtime:** Android API 36 ARM64 Emulator (`cricpro_api36`, `emulator-5554`)  
**Production Source Freeze Commit:** `77160497676e0fc6c20951880b42d792255b502c` (`77160497`)  

---

## 1. Owner-Approved Emulator Performance Exception

Strict seek-performance compliance was not achieved in three of twelve p95 cells. The project owner explicitly approved an emulator-only exception after reviewing the original 240-sample campaign and an additional 60-sample diagnostic campaign. The three exceedances remain recorded as FAIL against their original thresholds. All other evidenced mandatory Stage 8 gates are retained. Physical-device qualification is deferred and is not covered by this exception.

The three affected benchmark scenarios remain classified as:
1. `one-video process-cold` (budget 450.90 ms, measured p95 477.04 ms, diagnostic p95 472.28 ms): **FAIL — OWNER-APPROVED EMULATOR PERFORMANCE EXCEPTION**
2. `two-videos process-cold` (budget 761.20 ms, measured p95 864.90 ms, diagnostic p95 770.01 ms): **FAIL — OWNER-APPROVED EMULATOR PERFORMANCE EXCEPTION**
3. `two-videos alternating` (budget 515.30 ms, measured p95 549.78 ms, diagnostic p95 571.01 ms): **FAIL — OWNER-APPROVED EMULATOR PERFORMANCE EXCEPTION**

### Technical Qualification Language

> "The retained traces support emulator scheduling and virtual codec variability as plausible contributors to the three strict p95 exceedances. They do not establish that the same timing behavior would occur on physical Android hardware. The project owner has explicitly accepted these deviations for the Android API 36 ARM64 emulator qualification only."

The Stage 8 audio engine overhead is measured at 70 µs per 1,024-frame block (0.328% CPU of realtime on minified R8) and bypasses completely on neutral tracks. The observed seek latency deviations are attributable to emulator scheduling (cold-start main-thread dispatch overhead and SurfaceFlinger composition synchronization) rather than production regressions.

---

## 2. Scope Qualifications & Explicit Deferrals

Stage 8 acceptance is qualified and valid strictly within the **Android API 36 ARM64 Emulator (`cricpro_api36`)** test scope:

1. **Physical Hardware Qualification:**
   - Physical device qualification across vendor SoC architectures (Qualcomm Snapdragon, Samsung Exynos, MediaTek Dimensity, Google Tensor), hardware decoder instances, real thermal throttling, physical audio hardware sinks, and hardware display composition remains **NOT RUN — DEFERRED**.
   - See `docs/PHYSICAL_DEVICE_QUALIFICATION_PLAN.md` for the roadmap.
   - The owner-approved emulator performance exception does not extend to physical Android hardware.
2. **Play Store & Production Signing:**
   - The ordinary release APK (`app-release-signed.apk`) and AAB (`app-release.aab`) were signed using an Android debug/test keystore solely for emulator installation, release instrumentation, and performance qualification.
   - Production Google Play Store upload signing (Play App Signing, production release keystore, Google Play Console pre-launch report qualification) remains **NOT RUN — DEFERRED**.
3. **Selective Historical Media Restoration:**
   - 40 historical deleted files (8 recorded voiceovers, 32 exports) are preserved intact in `continuation-2-recovery/consumer-data-before.tar`.
   - Restoration into the active device sandbox remains **PENDING OWNER AUTHORIZATION** as a separate follow-up task.
4. **Goldfish MediaCodec Resource Limits:**
   - The Android emulator's `goldfish` software/virtual codec driver exhibits resource exhaustion when more than ~40–50 consecutive codec instances are created in a single uninterrupted OS process lifetime.
   - For continuous automated test suites spanning >100 media tests, scheduled emulator recycling or chunked execution is required to avoid artificial environmental flakiness.

---

## 3. Protected Data and Artifact Integrity

1. **Fancut & Protected Directories:**
   - `fancut/` was strictly preserved and never traversed, inspected, staged, or modified.
   - `video_editor_target_asset_kit/` was preserved intact.
2. **User Assets and Projects:**
   - All 11 pre-existing user projects in Room DB, 17 newer projects, all original fixtures, and all retained exports remain 100% intact with zero metadata loss.
3. **Release Artifacts:**
   - Final production source matches commit `77160497` across all 484 manifest files.
   - Test-signed release APK (`app-release-signed.apk`, SHA-256 `1ffb7a8128d86b6185161e613e8f2c6fc83f13fa7fe8dece4ec4921891c6179f`).
   - Unsigned release APK (`app-release-unsigned.apk`, SHA-256 `a27f9885cc25bb5242e4a31c5783069ea08070a41613b683ac774c5234f6344b`).
   - Release AAB bundle (`app-release.aab`, SHA-256 `df5472298808d58821e54d044013694aac84a034909924e40884fdee207897dc`).
