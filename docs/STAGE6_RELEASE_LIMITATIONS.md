# Stage 6 release limitations

**STAGE 6 — ACCEPTED (API 36 EMULATOR SCOPE ONLY).** All implemented technical gates pass on production commit `1504b2ea23aa58b70b6f79431b3905f189ad6c38`: 342 host, 132 media, 52 editor, 20 app-debug and 20 app-release tests; 240 independently valid seek records meeting all 12 original p95 budgets; 26 ordinary R8 frame checks; four continuous workloads; and two exports with verified source/video identity and native/FFmpeg audio.

The historical 4.75-second export reference was proven unrecoverable: it first appears as an unsourced target in a 2026-10-17 working document and was never an approved contract. It is retained as an unqualified reference, not retrospectively qualified. By explicit project-owner decision on 2026-10-18 it is superseded by the approved `stage6-export-v1` benchmark (see STAGE6_EXPORT_BENCHMARK_CONTRACT.md), which PASSED on 2026-10-18: 20/20 valid process-cold exports of the frozen four-second mixed-content fixture, medians 3414.560898 ms (720p, threshold 5000 ms) and 3557.620126 ms (1080p, threshold 6000 ms), Transformer.start→onCompleted timing, on the existing API 36 emulator.

The native Codec2 no-output cause remains unknown. Bounded generation-owned recovery and terminal cleanup are tested at the observable decoder/GL boundary; this does not reproduce or explain the native execution failure. An earlier current-source campaign failed during proven device ENOSPC; its attempts and truncated trace remain preserved. The fresh complete campaign is separate, with no pooling or weakened budgets.

Ordinary APK SHA-256: `8103a1ed84bcc505ba0c265e15074b52f5ac9f3900c883143b8be519887760ef`.
AAB SHA-256: `b0437dc86a8e95a58a17d4ec9ef21f3273dbfe51201dcf1c4fb2708d23a2c72f`.
Local test signing only. Release instrumentation is a distinct artifact. No physical-device, Play or production-distribution certification is claimed.

User projects, databases, imported media, premium UI and the existing AVD are preserved. Protected folders were not inspected or modified during this continuation; the initial discovery exception is disclosed in the evidence index. Large evidence remains outside Git. See STAGE6_FINAL_ACCEPTANCE.md for the final report.
