# Representative ordinary-R8 performance comparison

The final production editor source is `20b089e9`. The unchanged Stage 5 independent verifier accepts **12/12 collected warm seek samples**: three each for one video, two videos, three videos and photo overlay. There are zero failed, partial or unverified collected samples. Cold and alternating modes were not rerun; the verifier reports 36 planned across its full mode grid, 12 completed and 24 unattempted. This is not a new complete Stage 5 gate.

The same retained fixtures, emulator, production trace route and source/pixel oracle are used. Gesture-derived destinations differ slightly and n=3 is too small to establish statistical equivalence. The retained pre-redesign Stage 6 run is shown separately from the accepted Stage 5 baseline. Runs are not pooled.

Values below are request-to-GL-submission milliseconds, rounded to one decimal. Submission is not screen presentation. For n=3, nearest-rank p95 equals the maximum.

| Workload | Accepted Stage 5 warm p50 / p95 (n=20) | Retained pre-redesign Stage 6 p50 / max (n=3) | Current editor p50 / max (n=3) |
|---|---:|---:|---:|
| one-video | 385.4 / 603.2 | 633.7 / 685.0 | 712.3 / 751.9 |
| two-videos | 693.8 / 1098.4 | 1094.4 / 1109.0 | 1093.8 / 1110.1 |
| three-videos | 1319.2 / 1460.9 | 1464.6 / 1550.3 | 1566.8 / 1616.2 |
| photo-overlay | 560.9 / 613.6 | 658.1 / 710.2 | 661.9 / 708.1 |

**Result:** exact-frame correctness passes, but the current small warm sample is above portions of the accepted Stage 5 timing distribution. The pre-redesign Stage 6 measurements were already above that baseline. The new run alone cannot isolate UI effects from the retained media changes or emulator variance. Current performance is therefore **not certified within the accepted Stage 5 budgets**. No threshold or oracle was relaxed; performance qualification remains open alongside the independent Stage 6 media failure.

A separate real-UI workload on the ordinary R8 APK opened/cancelled Speed twice, expanded/compacted its controls and expanded/collapsed timeline height. All eight actions completed with **zero graph rebuilds and zero player creations** in the retained trace. This directly verifies the adaptive producer-buffer invariant, without claiming latency, frame-rate or memory-leak certification.

## Controlled pre/post UI check

The retained pre-redesign ordinary APK (SHA-256 `6f167fb165df7497f48e43a6113f072446cfd592cdff1f4560bc91e274a910fa`) was temporarily installed, then the current ordinary APK was restored. App data and projects were retained. Both ran five identical long ruler scrubs on each of the one-video and three-video fixtures, clamped to exact targets 0 or 6,000,000 us. This removes the destination variation in the earlier short-swipe sample.

The first button-based timing attempt could not supply the scrub request log required by the existing verifier; it is retained as a failed harness attempt. The clamped-scrub run uses the unchanged independent Stage 5 verifier. It accepts **9/10 pre-redesign** and **8/10 current** collected samples. One pre-redesign and two current one-video start samples are **unverified** because the required zero-target dispatch counter is absent in the request window; captured zero frames alone do not satisfy the oracle. They remain excluded, not reclassified as passes.

Only the **eight matching verified labels with identical target and source frame** enter the following comparison. Values are request-to-GL-submission median / maximum ms; sample counts are per APK.

| Workload / exact target | n | Pre-redesign median / max | Current median / max |
|---|---:|---:|---:|
| one-video / 6,000,000 us | 3 | 3123.3 / 3136.8 | 3132.4 / 3152.5 |
| three-videos / 6,000,000 us | 3 | 3938.6 / 3960.6 | 3968.7 / 4027.3 |
| three-videos / 0 us | 2 | 1367.8 / 1828.3 | 1362.7 / 1873.4 |

The verified end-seek timings are close between the APKs; the multi-second end-seek behavior is already present in the pre-redesign artifact. The retained decoder patch is identical. The controller patch additionally contains the original user's later replay-toggle correction in the current working tree; this paused scrub workload does not invoke that toggle. This controlled check does not establish full Stage 5 budget equivalence or explain all latency, but it gives no evidence that the redesign introduced the observed end-seek delay. Full performance qualification remains open; the UI task does not reopen the independent media investigation.

## Evidence

- [Current verified sample summary](</Users/PUBLIC_USER/video-editor-redesign-evidence/20261015/ordinary-r8-stage5-verified-01/summary.json>)
- [All current verified samples](</Users/PUBLIC_USER/video-editor-redesign-evidence/20261015/ordinary-r8-stage5-verified-01/verified-samples.json>)
- [Panel sizing result](</Users/PUBLIC_USER/video-editor-redesign-evidence/20261015/ordinary-r8-panel-graphs-01/result.json>)
- [Controlled matched comparison](</Users/PUBLIC_USER/video-editor-redesign-evidence/20261015/controlled-paired-comparison.json>)
- [Current controlled unverified samples](</Users/PUBLIC_USER/video-editor-redesign-evidence/20261015/controlled-current-verified-02/verification-failures.json>)
- [Pre-redesign controlled unverified samples](</Users/PUBLIC_USER/video-editor-redesign-evidence/20261015/controlled-pre-ui-verified-02/verification-failures.json>)
- [Established Stage 5 phase definitions and acceptance](../STAGE5_PERFORMANCE_RECOVERY.md)

Next qualification should address the shared existing seek latency and the unverified zero-dispatch samples, then use the established full sample plan if needed. It must not infer a Stage 6 fix or acceptance from this redesign.
