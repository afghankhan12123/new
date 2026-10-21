# Stage 5 performance recovery — October 14, 2026

Recovery began at `cfc15a2b7cc875d00e29da2a942f936767e0666f` on
`codex/post-release-audit`. Only original untracked `fancut/` was present; the
tracked diff and diff check were clean. No reset/cleanup, project migration,
media-engine change or reference-directory change was made.

## Interrupted collection, not an application failure

Evidence root (external, retained):
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage5-recovery-20261013/normal-production/`.

The host seek driver was no longer running. The existing API-36 ARM64
`cricpro_api36` / `emulator-5554` remained connected. Its exact retained Perfetto
PID **14514** matched `three-videos-trace-start.txt` and `/proc/14514/cmdline`:
`/data/misc/perfetto-traces/stage5-three-videos-1791980448561151000.perfetto-trace`.
Only that owned tracer was terminated with TERM and allowed to flush/exit. The
flushed trace is in `recovery-audit-20261014-01/three-videos.perfetto-trace`;
original collection files were not replaced. Before/after session inventories,
recovered logcat, device/package metadata and original file SHA-256 inventory
are in the same recovery directory.

| Workload | Warm complete | Process-cold complete | Alternating complete |
|---|---:|---:|---:|
| One video | 20 | 20 | 20 |
| Two videos | 20 | 20 | 20 |
| Three videos | 13 | 0 | 0 |
| Photo overlay | 0 | 0 | 0 |

Total **133 completed capture records / 240 planned**. Case
`three-videos-warm-013` has only its first capture, without request/capture timing
metadata: partial, not a completed measurement. Another 106 cases were not
attempted. There are no recorded application failures/timeouts in that original
collection; absence of a failure record does not make the partial case pass.

## Original verifier executed and audited

The retained verifier compiled. The manifest-resolved fixture directory's eight
hashes match. `/tmp/premium-trace-processor` runs Perfetto v58.2. The original
verifier was executed against a separate symlinked analysis directory, including
the recovered third trace. Its code and original evidence were not overwritten.
It exited 1: **54 provisionally accepted / 79 unassociated**. Those are the old
verifier's results, not final acceptance or a valid 240-sample distribution.

Trace statistics show ring-buffer data loss:

| Original trace | Bytes overwritten | Chunks overwritten |
|---|---:|---:|
| One video | 146,567,168 | 4,948 |
| Two videos | 202,932,224 | 6,809 |
| Three videos, recovered | 15,556,608 | 515 |

The 64 MiB non-streaming ring buffer retained only late portions of each run.
Many early requests, graph ownership and decoder initialization events are gone.
Flushing a trace cannot recover overwritten events. This is a collection defect,
not proof of editor failure. Original raw trace/CSV/processor logs, old verifier
results and all captures remain retained.

Other verifier gaps: trusted recorded marker IDs instead of rereading PNGs;
unchecked equal-RGB-sum colour cancellation; photo-center-only verification;
partial/unbounded compositor groups; incomplete process/graph/time associations;
limited exception handling; omission of wholly missing workload cells. The
corrected verifier is being tested separately and has no authority to change
correct application output to satisfy its assumptions.

## Controlled remeasurement

The collection harness now streams trace buffers to disk every second and flushes
periodically. It also retains trace paths before launch, per-attempt records,
final request logs, atomic completed-record writes and APK/driver/manifest identity.
A separately named full run is `seek-measurements-streamed-01/`; it must never be
pooled with the interrupted run. Same installed normal APK SHA-256:
`27e790b23b997a474e93fb9211e6644108dc7284f784bd887a05eb6172616ac5`.
The normal APK's recorded production patch sections and PreviewPhaseTrace helper
match current committed production source. The fixture-runner patch differs only
in androidTest source (explicit owned sourceProjectId), not normal APK code.

“Process-cold” in this driver means force-stop, launch, reopen, Fit, observe frame
zero, **then seek**. It is not a first-seek-before-decoder-initialization benchmark.
Multi-input seeks still deliberately replace their graph. Dispatch, accepted
video/bitmap input, composited output readiness, GL submission and screenshot
observation must remain distinct. No SurfaceFlinger/pointer-to-photon precision or
physical-device certification follows from these counters.

## Gate at this checkpoint

Stage 5 **not accepted**. Corrected independent verification, remaining normal
production pacing/resource observation, fresh final-source complete regressions,
production build confirmation and final reporting remain required. Stage 6 has
not started. No speculative optimization or unsafe Media3 flush was introduced.

## Independently verified seek results

The new streamed run completed **240/240**, independently verified **240/240**,
with zero failed, unverified, partial or missing cases. Each cell has n=20.
The original run is separate: corrected verification accepts 53/133 and excludes
80/133 for missing trace associations (including a missing request-player link);
its 1 partial and 106 unattempted cases remain unmeasured. Do not pool the runs.

All following values are milliseconds, **p50 / p95**. p50 is the conventional
median; p95 is nearest rank `ceil(0.95*n)` (the 19th sorted value for n=20).
Min/max/population standard deviation, clock uncertainty, per-input timestamps,
decoder preparation, pixel scores and every sample label are in the JSON.

| Workload / mode | Dispatch | Main decoded input accepted | Output ready | GL submitted | Screen lower bound | Screen upper bound |
|---|---:|---:|---:|---:|---:|---:|
| one-video / warm | 190.582 / 229.766 | 375.961 / 592.762 | 376.882 / 593.695 | 385.424 / 603.239 | 148.371 / 519.350 | 556.971 / 825.782 |
| one-video / process-cold | 159.608 / 207.494 | 556.013 / 587.903 | 556.870 / 588.975 | 566.062 / 598.739 | 315.796 / 522.463 | 808.070 / 834.360 |
| one-video / alternating | 186.462 / 290.159 | 366.456 / 509.176 | 367.724 / 509.963 | 377.062 / 519.381 | 283.763 / 316.744 | 607.180 / 785.647 |
| two-videos / warm | 264.360 / 688.787 | 576.752 / 954.275 | 667.801 / 1068.966 | 693.826 / 1098.360 | 431.710 / 989.603 | 960.863 / 1327.486 |
| two-videos / process-cold | 155.765 / 173.568 | 834.163 / 887.042 | 937.887 / 991.159 | 959.914 / 1006.814 | 820.283 / 883.919 | 1193.161 / 1214.229 |
| two-videos / alternating | 118.887 / 423.786 | 456.918 / 741.451 | 569.725 / 870.430 | 590.422 / 897.096 | 382.673 / 684.686 | 783.939 / 1068.268 |
| three-videos / warm | 320.742 / 932.756 | 1151.167 / 1335.366 | 1292.429 / 1426.892 | 1319.195 / 1460.879 | 1039.047 / 1204.467 | 1535.004 / 1709.521 |
| three-videos / process-cold | 274.218 / 293.342 | 1204.334 / 1277.547 | 1364.427 / 1420.869 | 1391.541 / 1447.859 | 1123.538 / 1326.386 | 1625.473 / 1686.935 |
| three-videos / alternating | 333.404 / 422.140 | 814.722 / 1027.831 | 943.318 / 1149.366 | 966.266 / 1164.546 | 754.848 / 1020.829 | 1165.963 / 1402.863 |
| photo-overlay / warm | 188.577 / 359.708 | 499.519 / 544.222 | 547.019 / 599.360 | 560.941 / 613.557 | 317.774 / 500.657 | 813.976 / 845.813 |
| photo-overlay / process-cold | 36.932 / 55.700 | 478.116 / 495.788 | 529.549 / 552.648 | 547.630 / 570.714 | 312.775 / 486.006 | 794.255 / 819.104 |
| photo-overlay / alternating | 333.529 / 426.051 | 535.687 / 709.801 | 591.759 / 762.142 | 612.679 / 780.103 | 503.755 / 543.433 | 844.095 / 1029.194 |

Screen columns are quantiles of conservative interval endpoints, not an exact
presentation-latency distribution. Bounds use each host/device calibration interval
including uptime quantization and ADB transport. They use the last observed
non-target capture and a correct capture definitely after traced submission.
Sub-millisecond digits retain arithmetic reproducibility, not that level of
measurement accuracy. The decoded-input phase is acceptance/release of a decoded
frame to its producer surface, not isolated decoder execution time.

The strengthened verifier rereads every PNG marker; validates the last three
captured identities; checks first/final correct images against independently
FFmpeg-decoded main/PiP frames; and checks photo interior and all four edges.
It retains the original RGB-sum threshold 30 and adds a channel-wise L1 threshold
30 to prevent equal-channel-sum colour cancellation. It verifies each required
input and complete compositor group in the selected graph/time interval, accepted
and latest request, PID plus process lifetime, player, graph generation, buffer
PTS, output readiness/submission and request-log player/generation identity.
Every sample failure is recorded, including parser/file errors. Twelve host-only
verifier tests pass, including wrong process/route/target, partial compositor group,
stale output, retired player, fabricated marker and colour-cancellation rejection.

Normal APK SHA remains `27e790b23b997a474e93fb9211e6644108dc7284f784bd887a05eb6172616ac5`.
All four streamed traces have **zero buffer-overwrite counters**. Each reports
37 ftrace setup notices for unavailable tracing hooks; raw stats are retained.
Those notices are not application playback errors. No physical GPU or exact
SurfaceFlinger presentation metric is inferred.

Evidence: `seek-measurements-streamed-01/` (raw),
`seek-verification-streamed-01/` (240 verified samples, summary, trace stats,
independent verifier source, unit tests and phase breakdown),
`seek-verification-recovered-01/` (separate 53 verified / 80 excluded originals).
All are below the external normal-production root above.

No media-engine optimization was made. The measured dispatch-to-input interval
dominates much of the multi-video latency; it includes decoder/source seek and
safe replacement startup, not just codec initialization. One-video decoder
preparation is explicitly N/A when reused, with its original initialization
provenance retained. No unsafe downstream flush, lower preview quality or weaker
pixel assertion was used to reduce a reported number.


## Fresh independent confirmation and lifecycle audit

Continuation starting SHA: `5a2f858cecc1e9e149b0fbfc6b003112fa19d4ab`.
The current session found the later recovery commits rather than repeating the
interrupted collection. No profiling or instrumentation owner was active, and
only the protected untracked `fancut/` directory was present. The retained
original verifier compiles and the trace processor reports v58.2. The current
12-test verifier suite passes again. A fresh complete offline invocation of
`scripts/verify_stage5_seek.py` independently reproduces **240 completed / 240
verified / 0 unverified**, with the same twelve per-cell metrics above.
Results and freshly FFmpeg-decoded references are in
`normal-production/seek-verification-final-20261014-01/`.

The retained production lifecycle run `continuous-production-02/` uses the same
normal APK hash as the seek distribution. The new offline
`scripts/verify_stage5_continuous.py` verifies each workload's actual PNGs against
independent moving-source references at end, fullscreen recreation, restored
surface, replay end and saved-project reopen. All required main/PiP markers and
strict channel/RGB-sum threshold 30 pass; photo pixels and edges pass too.
The reopened frame is 0, and end/recreated/replayed frames are 179. Its summary
is `normal-production/continuous-verification-final-20261014-02/summary.json`.
The earlier analysis `-01` is retained: its broad GPU-name query accidentally
matched `PrevGpuFrameMissed`; the corrected query reports no GPU-memory series.
This is an analysis correction, not an engine defect or a zero-allocation result.

The earlier collection `continuous-production-01/` failed a one-shot fullscreen
capture (marker absent during recreation). Its trace, screenshot and exception
remain intact. Run 02 polls for the actual frame with a ten-second bound and
retains every candidate. It verifies eventual correct restoration, not instant
surface replacement or a measured restoration-latency percentile. No application
engine change was made between those runs.

Each pacing window is the earliest complete uninterrupted increasing graph pass
from 0.3 through 5.3 seconds: **151 exact consecutive output PTS / 150 intervals**.
Every video input has 121 consecutive accepted PTS from 1 through 5 seconds.
This prevents pooling replay/replacement graphs or silently accepting missing
frames. All four traces have no reported buffer overwrite/lost-byte counters.

| Workload | Submission interval p50 / p95 ms | Min / max ms | Input accepted fps | UI gfxinfo janky / rendered | PSS before / replay / closed / reopened-closed KiB |
|---|---:|---:|---|---:|---:|
| One video | 31.738 / 41.504 | 29.397 / 43.535 | 29.969 | 1 / 198 | 68,775 / 54,436 / 52,878 / 49,824 |
| Two videos | 31.877 / 41.921 | 22.514 / 43.490 | 30.078, 30.072 | 0 / 189 | 71,234 / 55,485 / 49,714 / 54,054 |
| Three videos | 32.086 / 41.063 | 24.831 / 42.621 | 30.072, 30.056, 30.071 | 0 / 190 | 73,343 / 55,435 / 52,079 / 56,185 |
| Photo overlay | 31.953 / 41.047 | 28.890 / 43.187 | 30.029 video | 0 / 190 | 72,397 / 54,147 / 49,672 / 53,024 |

Registered resource-manager clients before/replay are one AAC plus respectively
1/2/3/1 Goldfish H.264 decoders; both close snapshots have **zero registered
clients for the actual app PID**. Historical peak-usage/event-log rows are not
live clients. Pending-input maxima are five frames. Player creation counts
8/9/10/9 cover the entire open/play/recreate/replay/reopen lifecycle, not duplicate
construction for one seek. Main-thread scheduled CPU during each five-second
pacing span is 207.781/246.756/289.927/241.458ms respectively; inclusive main/UI
slices must not be summed with decoder/GL intervals.

Gfxinfo describes Compose/window UI, not SurfaceView video presentation.
FrameTimeline contains transaction-layer prediction/scheduling/drop labels;
raw layer-scoped rows are retained without treating duplicate transaction,
background and SurfaceView records as independent displayed frames or claiming
an exact video jank percentage. Ftrace hook notices and parser statistics remain
in the raw summaries. GPU memory is unavailable. These short snapshots show
resource release and bounded queues in the exercised cases, not sustained-use,
thermal, native/EGL allocation or long-session leak certification. No physical
device was connected, and Goldfish labels do not qualify physical codecs.

The fresh final-source guarded regression is running separately under
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage5-final-acceptance-20261014/`.
Stage 5 remains pending until all its suites/builds and final normal-APK checks
pass. Stage 6 has not begun.


### Decoder observations and variability

For all 60 one-video attempts, the decoder is already prepared when the seek
starts; preparation is N/A, with its original initialization events retained.
The process-cold workload explicitly reopens and observes frame zero first.
For graph-replacing workloads the following values are **per video decoder
initialization**, not a sum of concurrent decoder costs or an entire seek.

| Workload / mode | Decoder events | Preparation p50 / p95 ms | Min / max ms |
|---|---:|---:|---:|
| Two videos / warm | 40 | 14 / 22 | 7 / 33 |
| Two videos / process-cold | 40 | 16 / 22 | 9 / 27 |
| Two videos / alternating | 40 | 15 / 22 | 7 / 22 |
| Three videos / warm | 60 | 15 / 27 | 6 / 31 |
| Three videos / process-cold | 60 | 15 / 24 | 6 / 28 |
| Three videos / alternating | 60 | 13 / 28 | 5 / 43 |
| Photo overlay / warm | 20 | 15.5 / 21 | 10 / 33 |
| Photo overlay / process-cold | 20 | 15.5 / 20 | 8 / 22 |
| Photo overlay / alternating | 20 | 18.5 / 24 | 9 / 25 |

The worst request-to-GL-submission attempt is `two-videos-warm-018`,
**1,758.238667ms**. Every per-cell min/max/population standard deviation and
individual image-load observation is retained in the verified JSON, including
that tail rather than trimming it. Calibration interval widths are
26.986333–31.281250ms; decimal digits are arithmetic, not display precision.
There are no final wrong/stale frames or timeouts in the 240 planned attempts.
Intermediate non-target captures are retained and counted per attempt as settling
observations; they are not silently removed or reported as already-correct output.
The original trace-loss exclusions remain separate and do not enter percentiles.


Final Stage 5 emulator acceptance and fresh XML/build/runtime evidence are recorded in [Stage 5 final acceptance](STAGE5_PREMIUM_UX_PERFORMANCE.md#stage-5-final-emulator-acceptance--october-14-2026). All gates pass; Stage 6 may begin.
