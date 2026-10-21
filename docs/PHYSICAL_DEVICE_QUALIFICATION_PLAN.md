# PHYSICAL DEVICE QUALIFICATION PLAN (FUTURE MILESTONE)

> [!IMPORTANT]
> **Status:** **NOT RUN — DEFERRED (FUTURE WORK)**  
> This document defines the engineering plan for future on-device qualification across physical Android hardware.  
> **It is NOT a record of completed qualification.**  
> Stage 8 acceptance is strictly scoped to the Android API 36 ARM64 emulator (`cricpro_api36` / `emulator-5554`) under an explicit owner-approved performance exception.  
> **No physical device acceptance, universal Android performance, or Play Store distribution readiness is claimed.**

---

## 1. Objectives & Scope

The purpose of physical device qualification is to validate the Professional Android Video Editor against real-world hardware constraints that cannot be accurately modeled on virtualized goldfish emulators:
- Hardware MediaCodec implementations (Qualcomm Snapdragon, Samsung Exynos, MediaTek Dimensity, Google Tensor).
- Concurrent hardware decoder instance limits (typically 4–8 hardware instances on mobile silicon vs software-backed virtualization).
- Real thermal throttling curves and DVFS (Dynamic Voltage and Frequency Scaling).
- Direct audio hardware sink routing via AAudio / Oboe / AudioTrack, hardware latency, and DAC/headphone stereo separation.
- Hardware SurfaceFlinger and vendor GPU (Adreno, Mali, Immortalis, Xclipse) compositing queues.

---

## 2. Target Device Matrix

Future testing must cover representative tiers of physical Android hardware:

| Tier | Representative Chipset | Representative Devices | Android OS Versions |
|---|---|---|---|
| **Tier 1 (Flagship ARM64)** | Qualcomm Snapdragon 8 Gen 2 / 8 Gen 3 | Samsung Galaxy S23 / S24, OnePlus 12 | Android 14 (API 34), Android 15 (API 35) |
| **Tier 2 (Tensor Architecture)** | Google Tensor G3 / G4 | Google Pixel 8 / 9 Pro | Android 14 (API 34), Android 15 (API 35) |
| **Tier 3 (Exynos Architecture)** | Samsung Exynos 2400 / 1480 | Samsung Galaxy S24 (Global), A55 | Android 14 (API 34) |
| **Tier 4 (MediaTek Dimensity)** | MediaTek Dimensity 9200+ / 8300 | Xiaomi 13T Pro, Redmi K70E | Android 13 (API 33), Android 14 (API 34) |
| **Tier 5 (Mid-Range Baseline)** | Qualcomm Snapdragon 7 Gen 1 / 695 | Moto G84, Samsung Galaxy A35 | Android 13 (API 33), Android 14 (API 34) |

---

## 3. Recommended Qualification Focus Areas

### 3.1 Seek Latency (p50 / p95 Distributions)
- Re-measure the 12-cell matrix using real hardware MediaCodec decoders.
- Establish whether the emulator-observed ART cold-dispatch delays (50–83 ms) persist or diminish on physical Kryo/Cortex-X cores.
- Measure physical GPU driver command submission timing (`request_to_output_submitted_ms`).
- Compare hardware decoder keyframe seeking against the frozen Stage 5 budgets. Any budget adjustments must be approved in advance by the project owner.

### 3.2 Real Hardware Decoder Startup Latency
- Measure hardware codec initialization times across concurrent instances (e.g. 1 primary video + 1 PiP video + 1 audio track).
- Verify driver behavior when concurrent instances approach vendor hardware limits (e.g. fallback to OMX/C2 software decoders vs hardware failure).

### 3.3 Audio/Video Synchronization & Drift
- Measure hardware A/V sync over sustained playback (30+ seconds) across 44.1 kHz and 48 kHz source content.
- Verify zero drift across loop replay and transport pauses.
- Validate preview-to-export audio parity on physical audio output sinks.

### 3.4 Multi-Track Playback
- Validate smooth timeline playback with concurrent primary video, picture-in-picture overlay, title overlay, and background audio.
- Profile Choreographer frame drops and SurfaceView buffer release timing.

### 3.5 Stereo Audio Fidelity & Routing
- Verify left/right stereo channel preservation on physical speakers and Bluetooth LE Audio / A2DP sinks.
- Verify <1% cross-channel leakage and no channel-inversion defects.

### 3.6 Export Performance
- Measure Transformer export performance utilizing hardware MediaCodec H.264/HEVC encoders.
- Benchmark 720p and 1080p process-cold export durations against emulator software encoding baselines.

### 3.7 Thermal Behavior & Sustained Load
- Profile device temperature and frame rate stability during repeated exports and 10-minute continuous timeline scrubbing.
- Confirm graceful degradation without process crashes during active SoC thermal throttling.

### 3.8 Sustained Memory Consumption
- Track resident memory (PSS/RSS) over 50 consecutive seek and edit operations.
- Verify no native GraphicBuffer or MediaCodec buffer leaks.

### 3.9 Android Lifecycle & Background Handling
- Validate app backgrounding (`onPause` / `onStop`) during active preview and export.
- Verify proper surface release and recreation on `onResume` without black-screen regressions.

### 3.10 GPU Composition & Overlay Performance
- Profile custom OpenGL ES shaders (blending, transform, color filtering) on real Adreno and Mali GPUs.
- Verify pixel parity between GL preview surface and Transformer export shaders.

### 3.11 Release APK Installation & Production Signing
- Install official release APKs on unrooted, production Android builds (user builds) with Google Play Protect enabled.
- Verify ProGuard/R8 obfuscation integrity and ensure no missing runtime reflection rules on physical OEM ROMs.

### 3.12 Device-Specific Codec Interoperability
- Test across various container formats, color spaces (BT.709, BT.601, HDR10), and variable frame rate (VFR) recordings from standard smartphone cameras.

---

## 4. Governance & Approval Protocol

1. Physical device testing must be executed against frozen, source-matched release builds.
2. Baselines must be derived empirically from physical hardware observations rather than emulator measurements.
3. Any performance threshold modifications or waivers must be documented in a dedicated Physical Device Acceptance Matrix and formally approved by the project owner prior to release closure.
