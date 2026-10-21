# Stage 8 implementation continuation

The six Stage 8 features originate at `02c7013c`; continuation preserves the shared composition builder and approved UI.

Android qualification found fractional-speed automation mapping truncated playback speed before multiplying frame positions. The correction retains fractional precision until converting to source microseconds. Android gates cover .5×,1.5×,2.5× at 44.1/48kHz after trim and seek.

Automation and ducking now preserve explicit seek intent through decoder reset/flush, matching the accepted Stage7 fade contract. A native device regression reproduced unity output instead of the expected envelope after reconfiguration before the correction.

Muted/zero-duration triggers are deliberately not decoded. An all-muted measurement previously became a decoder failure in DuckingAnalysisService, making DuckingPanel show misleading error text. It now caches a neutral curve for intentionally silent triggers; failed native decode remains a failure.

Pipeline: Sonic speed → Volume → Automation → Fade → EQ → Balance → Ducking → shared seek-aware duration trim. Preview and export use the existing common builder.

Evidence root: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage8-continuation-20261019-codex/`. See test matrix and final acceptance for scope; implementation does not imply completed qualification.

Native staggered-trigger test found the analysis grid was sized by the longest individual trigger, omitting later timeline activity (399 windows vs requested index550). It now spans measured timeline positions plus the project window. Separate native tests reproduced attenuation before a delayed trigger (7531 vs10000) and frozen attenuation after a trimmed trigger (2511 vs10000). Silent grid windows now cover project start/end so normal attack/release calculation handles both boundaries. Cache identity includes project duration, retaining canonical preview/export sharing when the renderer shortens an audio clip.

Independent EQ oracle: Android-native decoded PCM, five presets plus manual and multiple boosts, compared with FFmpeg9 double-precision RBJ lowshelf/peaking/highshelf filters.28 steady-state frequency observations pass unchanged0.15dB tolerance; maximum measured difference0.00186dB. The initial bass/treble oracle used FFmpeg's distinct shelf formula; failed attempt and exact primary source retained in evidence.

Continuation2 adds main-timeline envelope drawing and direct point gestures, one
commit per drag, cancellation rollback, and accessible add/delete/interpolation.
Automation panel handlers keep stable pointer keys and Add point at playhead maps
project time through fractional speed into the source window. Audio panel draft
state follows the selected clip/current effect settings. A full-screen regression
reproduced missing panel routing; all five flags now mount the existing focused
workspace panel while retaining preview and audio timeline.

Full-path qualification reproduced missing stereo AAC priming finalization.
The existing actual-component chirp calibration now measures mono and stereo
frames/channel configurations independently and retains its original stability,
correlation and codec-config checks. The same canonical MP4 finalizer is used.
Preview mixer format now follows export's first real standalone audio stream at
project zero; synthetic primary silence no longer down-samples48kHz EQ to44.1kHz.
Gap-padded sequences preserve existing registration order to avoid deadlock.

The shared duration trim now also bounds preview decoder/Sonic padding. It uses
the existing seek intent contract, so paused seek/reset/replay update the remaining
authored window. Independent verification found768 extra frames before this fix.
All changes are under qualification; current status/evidence follows the test matrix.
