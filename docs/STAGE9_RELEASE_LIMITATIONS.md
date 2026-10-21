# STAGE 9 — RELEASE LIMITATIONS AND SCOPE QUALIFICATION

**Final Formal Status:** **STAGE 9 — FULLY QUALIFIED AND ACCEPTED (EMULATOR-SCOPED)**  
**Date:** October 20, 2026  
**Target Hardware / Runtime:** Android API 36 ARM64 Emulator (`cricpro_api36`, `emulator-5554`)  
**Evaluation Role:** Principal Android Architect, Media3 Specialist, Release Acceptance Lead  

---

## 1. Scope Qualifications & Explicit Deferrals

Stage 9 acceptance is certified and qualified within the **Android API 36 ARM64 Emulator (`cricpro_api36`)** test scope. The following operational boundaries and deferrals apply:

### 1.1 Physical Hardware Qualification
- **Status:** **NOT RUN — DEFERRED**
- **Scope:** Qualification across physical consumer hardware architectures (Qualcomm Snapdragon, Samsung Exynos, MediaTek Dimensity, Google Tensor), hardware OpenGL ES 3.2 drivers, physical camera inputs, real thermal dissipation curves, physical audio sinks, and hardware SurfaceView composition is explicitly deferred pending available test hardware.
- **Protocol:** Refer to `docs/PHYSICAL_DEVICE_QUALIFICATION_PLAN.md` for physical qualification execution procedures prior to Google Play release.

### 1.2 Google Play Store Production Signing
- **Status:** **NOT RUN — DEFERRED**
- **Scope:** Release artifacts (`app-release-signed.apk` and `app-release.aab`) are signed with the Android development debug/test keystore solely for emulator installation, minified-release instrumentation, and benchmark verification.
- **Next Step:** Official production signing with the release Google Play Console upload key remains deferred to release deployment.

### 1.3 Selective Historical Media Restoration
- **Status:** **PENDING OWNER AUTHORIZATION**
- **Scope:** 40 historical deleted media files (8 voiceovers, 32 exports) from prior sessions remain safely archived in `continuation-2-recovery/consumer-data-before.tar`.
- **Policy:** In compliance with strict safety invariants, these files are preserved in external storage and have not been restored to the device without explicit owner authorization.

### 1.4 Virtual Codec Environmental Limits
- **Scope:** The Android emulator's virtual `goldfish` MediaCodec driver exhibits handle exhaustion if more than ~40–50 consecutive codec instances are created within a single long-running OS process lifetime.
- **Mitigation:** When running large automated connected media test suites (>100 tests), isolated class execution or scheduled process restarts should be utilized to avoid virtual codec driver exhaustion.

---

## 2. Performance & Benchmark Characterization

### 2.1 Export Performance
- **720p Cold Export:** Median **3,433.09 ms** (Budget: $\le 5,000\text{ ms}$) — **PASS** (31.3% headroom)
- **1080p Cold Export:** Median **3,605.24 ms** (Budget: $\le 6,000\text{ ms}$) — **PASS** (39.9% headroom)
- **Neutral Shader Bypass:** When visual effects and masks are inactive, the shader pipeline bypasses completely with zero measurable overhead.

### 2.2 Seek Performance
- **Sample Integrity:** 60 / 60 verified samples across all 12 cells with zero unverified samples and 100% frame accuracy.
- **Multi-Video Compositing:** `two-videos` warm (741.82 ms), process-cold (750.96 ms), and alternating (477.26 ms) satisfy strict Stage 5 p95 submitted budgets.
- **Composition Ready Timing:** In all 12 cells, `request_to_output_ready_ms` meets the internal media engine budget.

---

## 3. Protected Directories and Safety Verification

1. **`fancut/` Protection:**
   - `fancut/` was strictly preserved and never inspected, traversed, staged, or modified.
2. **`video_editor_target_asset_kit/` Preservation:**
   - Target asset kit preserved untouched.
3. **Consumer Sandbox Safety:**
   - All 11 original projects in Room DB, 17 newer projects, all original fixtures, and all user projects remain intact without data corruption.
4. **Git Safety:**
   - No unrestricted `git add -A` was executed. All additions and modifications are staged explicitly by path.

---

## 4. Release Artifact Identities

- **Signed Release APK:**
  - Path: `app/build/outputs/apk/release/app-release-signed.apk`
  - Size: 4,185,434 bytes
  - SHA-256: `e0a4a214bed0b0ac4bdb8623caee348ad05fbc7b6350b38a57d7f07899274a86`
  - Non-debuggable: Verified
- **Unsigned Release APK:**
  - Path: `app/build/outputs/apk/release/app-release-unsigned.apk`
  - Size: 4,133,935 bytes
  - SHA-256: `71f18abcf904b96fc293efb6d72f3f0a26c70eff4a9d754018ee1d2c0eac3b40`
- **Release App Bundle (AAB):**
  - Path: `app/build/outputs/bundle/release/app-release.aab`
  - Size: 8,182,679 bytes
  - SHA-256: `4437ce5b1075005256a2e163bfb6aab061a66fda172aed35d563ba844e023be8`
