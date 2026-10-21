# Editor interaction model

- Main tools insert or open the requested tool. **Text means add new text**, even when another caption covers that time. Existing text is edited by tapping that exact canvas object or using its contextual Edit action.
- A selected clip/layer replaces the main tool dock. The fixed back arrow clears selection and restores main tools. Applying a tool returns to its selected object; cancel rolls back its draft.
- New text is immediately selected and opens focused input. Typing updates the existing draft ID. Done commits one command; X rolls back the original or removes a new uncommitted caption. Outside-canvas tap finishes direct text entry before clearing selection.
- Text category taps hide the IME and expose actual template/font/style/effect/animation/position controls. Keyboard-visible controls prioritize input and an always-reachable Done action.
- Canvas layers use one existing fitted coordinate plane and hit order: PiP, stickers, captions. Drag, corner resize, pinch and rotation commit one reversible gesture. Outside taps resolve through the same selection route.
- Main timeline video/audio gestures retain their existing single gesture machine. Text gains selected-edge duration trimming with commit/cancel and undo; long press still moves its interval. Two-finger input cancels a pending edit before zooming.
- Every eligible video join stays tappable, including when its neighbor is selected. Global Transitions resolves a real adjacent video pair, preferring the selected clip, then the nearest join. The draft pins both IDs.
- Insufficient handles produce the existing exact planner explanation and a separate **Trim clips & add transition** action. Generic confirmation cannot silently trim footage. The handle/transition command stays atomic and undoable.
- Effects are browsed before parameter details. Selecting an already-present type selects that instance; duplicate explicitly adds another instance. Compare toggles the draft stack off/on. X discards; confirmation commits.
- Sticker insertion creates/selects a new layer. Replace preserves the selected sticker's ID, transform, interval and history. Recent assets are bounded locally; search covers all genuine bundled categories.
- Audio source actions import, extract from the selected video or record voiceover. Project-library rows reuse an existing durable media reference. Extraction no longer loses its video selection when Audio opens. Contextual audio Speed uses the existing 0.5×–2× retiming model and commits an ordinary reversible audio update.

No unsupported speed-curve, reverse, split-all or master-track controls are presented as working actions. Existing working controls remain available.
