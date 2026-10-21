# Premium UI refinements

The graphite/cyan four-region editor, shared metrics, contextual dock and existing real galleries remain.

Transitions now prioritize gallery → duration → a short actionable handle status. The large source-handle explanation lives in an explicit Review trims dialog. That dialog shows actual source and visible-tail/head removal, project duration reduction, unchanged project-time anchoring and final transition duration. Trim & apply commits one atomic command; Cancel changes nothing. Header Preview requires valid existing handles instead of silently previewing a different trimmed timeline. Apply cannot remove an existing transition merely because a draft is invalid. This reuses the existing adaptive pinned-header tool host.

New captions default to 90% canvas-width wrapping. The optional `maxWidthFraction` defaults to null in old documents, preserving existing unwrapped layout. Android StaticLayout handles first-strong paragraph direction, Unicode shaping, emoji, mixed Pashto/Arabic/Latin and explicit newlines. The same canvas width, text layout and padding feed preview/export rasterization and selection measurement. Position tools expose Wrap to canvas and Text width. Bounds turn amber when rotated corners extend beyond the canvas; no automatic reposition or scaling changes an authored object. Font/style/animation/draft identities and keyboard behavior are retained.

Loading covers pending-frame/reconfiguration/recovery states, with the existing short indicator delay; errors suppress the spinner. Fatal errors do not display a misleading Retry action. PiP gains direct contextual Mask/Chroma/Blend routes; duplicate and sticker replace now perform real edits. Existing PiP mode controls show two distinct Fit/Fill choices instead of two indistinguishable Fill labels for Fill/Crop enum aliases.

Main, PiP and sticker timeline rows share horizontal navigation, and secondary tracks read clock positions in drawing rather than recomposing their presentation on each playback sample. Additional sticker rows are scrollable.

Home/import remain readable in the user's existing app theme; Export retains its working dark flow. A forced global theme change was not necessary for the production defects, and the external Android picker was not restyled. No claim of fresh device screenshots or visual runtime acceptance is made in this build-only delivery. The retained screenshots are evidence from the supplied recording, not the modified app running.
