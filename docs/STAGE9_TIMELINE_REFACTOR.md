# Timeline refactor

`TimelineViewport` remains the sole time/pixel mapping. Centering and drawing now share the same rounded time-pixel product, and the scroll limit is computed directly from duration/scale. This fixes the former inverse mismatch (499.99997 versus an expected 500) without introducing another playhead clock. Integer microseconds remain authoritative.

`TimelineLayout` gives the main thumbnail strip greater relative height, with smaller secondary text/audio lanes. Drawing and hit testing consume these same row dimensions. The workspace bounds vertical track content, retaining access to additional PiP/sticker and overlapping lanes by vertical scrolling. Existing asynchronous thumbnail caches and viewport/zoom-bucket loading remain.

Text hit testing adds edge identity only for the selected caption and its actual overlap lane. Selected text shows duration handles. `beginTextTrim`/`textTrimBy`/`endTextTrim` reuse the shared text-transform transaction, clamp the interval to the project and commit one `UpdateTextOverlay` command. Cancellation restores the original interval. Main clip trim/reorder, audio waveform/envelope editing, fling, zoom and free interval movement retain their production gesture paths.

Transition markers remain at shared viewport cut coordinates and now accept taps with either neighbor selected. Append Media still uses its separate trailing plus action and existing durable multi-import path.

Preview targets still coalesce at display cadence. For multi-input compositions, crossing a source period retires/reconstructs one bounded graph; samples within a period retain in-place seeking. This is a correctness tradeoff supported by NE2211 failures, not a claim of CapCut-equivalent scrub latency. Sustained large-project interaction remains an owner check.
