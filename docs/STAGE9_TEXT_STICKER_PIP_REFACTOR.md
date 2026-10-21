# Text, sticker and PiP changes

`TextEditorPanel.kt` replaces the former form with focused live text entry and Templates / Fonts / Styles / Effects / Animations / Position categories. Six original recipes map directly to `TextOverlay` values. Existing eight offline fonts remain; bold and italic use the shared styled-text renderer and shared measured bounds. Color, alignment, size, opacity, background, shadow, outline width, enter/exit animation, position, scale, rotation and duration all edit the authoritative caption model.

Add and Edit use distinct identities. Done commits once, cancel restores exactly, and deliberate overlapping captions remain possible. Text can be moved by long press on its timeline block and trimmed using selected edges. No additional export-only text model or rasterizer was introduced. Once the IME layout is measured, the text panel retires Android's earlier pan-to-caret offset so the header and fitted preview remain in view.

The sticker browser enlarges the real bundled artwork, searches all categories and keeps 16 recent asset keys. Explicit Replace updates the original object. Canvas handles appear after insertion through the same selection owner; duration, ordering, duplicate/delete and transform tools retain their existing commands.

`LayerCanvasHandles` still owns the common fitted plane, rotated hit testing, center guides, corner resize, drag, pinch and rotation for text/stickers/PiP. Outside tapping now clears selection through the editor route; during direct text entry it finishes the text transaction. Existing PiP Fit/Fill, crop, opacity, layer ordering, masks, chroma key, blend modes and keyframes remain in the real tool host.

PiP and sticker timelines retain their existing source/timing implementations and share the fixed playhead. They are bounded within the scrollable track region rather than allowed to consume unlimited preview height. Their full transform/render parity across every mask, rotation, device and codec is not newly certified by the focused smoke checks.
