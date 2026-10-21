# Owner manual test — NE2211

Use a duplicate of an existing project plus a fresh project. Record any failure with the project time and tool visible. Focused developer fixtures have been exercised separately; these checks remain owner acceptance.

- **Start/import:** create a project with several videos/photos; check selection order, project cover and reopening after an app restart. Append media from the end plus; confirm it adds instead of replacing.
- **Timeline:** pan/fling, pinch around both ends, scrub backward across clip and overlay boundaries, trim video/audio, split, reorder and undo/redo. Verify the central playhead, timestamp and displayed source frame agree.
- **Text:** Add Text, type with the keyboard, confirm the preview/Done stay visible. Apply templates, fonts, bold/italic, colors, outline/shadow and animations. Tap an existing caption to edit it; use Add Text for another overlapping caption. Cancel both a new and an existing draft. Drag/rotate/resize, trim its timeline edges and undo.
- **Stickers:** search/browse, insert, drag/pinch/rotate, replace, change duration/order and duplicate/delete. Reopen the browser and check Recent.
- **PiP:** add image/video, select the exact layer, transform/crop/Fit/Fill, adjust opacity, ordering, masks, chroma and blend modes. Check keyframe navigation/interpolation and trim its interval.
- **Effects:** inspect visual source thumbnails, switch categories, apply intensity and specific parameters, compare, cancel, stack/reorder/disable and undo. Check all nine adjustments and the supported creative presets.
- **Transitions:** tap a specific join with and without a neighbor selected. Check exact insufficient-footage explanation. Use the explicit trim-and-add action only when its explained changes are acceptable; undo it. Play and seek across different transition types.
- **Preview:** repeat play/pause/seeks and selection changes; background/return and reopen projects. Report persistent loading, a stopped preview, incorrect frame or unusually long cross-period waits.
- **Audio:** import, reuse project music, extract audio, record a voiceover; split/trim, volume, speed, fade curves, automation, EQ, balance and ducking. Check alignment by listening across joins and after seeking.
- **Export:** use effects, transitions, styled text, stickers, PiP and audio together at 720p and 1080p. Verify actual output size/duration/frame rate, image/text position and audio sync. Cancel then export again. Save to Photos/Files, open and share.
- **Persistence:** save, close, restart and verify old/new layers, masks, keys, transitions, audio and export preferences. Confirm original source files are unchanged.

Remaining acceptance includes sustained smoothness on large projects and broad codec/GPU/audio coverage. Passing a short fixture does not establish those properties.
