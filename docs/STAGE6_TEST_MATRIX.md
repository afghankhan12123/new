# Stage 6 test matrix

**STAGE 6 — NOT ACCEPTED.** Production 1504b2ea23aa58b70b6f79431b3905f189ad6c38; existing cricpro_api36/emulator-5554/API 36.

| Gate | Current source evidence | Status |
|---|---|---|
| Host debug/release |342/342,zero failures/errors/skips|PASS|
| Full media |132/132,zero failures/errors/skips|PASS|
| Full editor |52/52,zero failures/errors/skips|PASS|
| App debug |20/20,zero failures/errors/skips|PASS|
| Opt-in release instrumentation |20/20,zero failures/errors/skips; distinct artifact|PASS|
| Focused ownership/audio/transition |17/17 implementation followed by JavaDoc-only change|PASS|
| Benchmark tooling |27/27|PASS|
| Independent PCM diagnostic |30 records, all bytes accounted, maximum RMSE 0.5 s16 units|PASS scoped diagnostic|
| Fresh standalone seek qualification |240/240 valid, zero invalid/recovery records, all 12 original budgets|PASS|
| Ordinary R8 frame identity |26/26|PASS|
| Continuous preview/transport/cleanup |4/4 workloads|PASS|
| New 720p/1080p exports |Both Native/FFmpeg audio and source/video identity|PASS|
| Historical 4.75 s export performance |Unsourced reference; never an approved contract|SUPERSEDED|
| Approved export performance benchmark (stage6-export-v1) |20/20 valid process-cold exports of the frozen four-second mixed-content fixture; medians 3414.560898 ms (720p) / 3557.620126 ms (1080p) vs 5000/6000 ms; deep video identity + audio qualification on final samples|PASS|

First current 1504 campaign transition-safe-240 failed an exact frame check during proven ENOSPC; truncated trace/attempts retained. It cannot be pooled into the fresh campaign. Earlier d50 full 240, all 12 PASS is historical because that source failed an exact CrossDissolve regression. Firstfull 240 and failed experimental variants remain preserved. Current full correctness passes are XML-backed and source-matched. Durable test fixture fixes preserve assertions/deadlines; no source correctness tests are waived.

Evidence: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-production-correction-20261018-115404`. See FINAL_REGRESSION_PROGRESS.json, PERFORMANCE_QUALIFICATION_PROGRESS.json and source manifests. No instrumentation/build overlaps timing. Full performance table is in STAGE6_FINAL_ACCEPTANCE.md and FINAL_PERFORMANCE_COMPARISON.md. Protected folders are not inspected or changed.
