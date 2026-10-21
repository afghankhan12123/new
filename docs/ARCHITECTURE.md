# Architecture

The Video Editor is a native Android application built with Kotlin, Jetpack Compose,
AndroidX Media3, Room, DataStore, and Hilt. It is organized into independent systems
with strict layer boundaries.

## Layering

```
┌─────────────────────────────────────────────────────────┐
│  UI Layer (feature/*, core/designsystem)                │
│  Screens, components, navigation, theme.                │
│  No video-processing business logic.                    │
├─────────────────────────────────────────────────────────┤
│  Domain Layer (editing/)                                │
│  Project edit operations, timeline logic, edit history, │
│  clip transformations, business rules.                  │
├─────────────────────────────────────────────────────────┤
│  Media Engine (core/media/)                             │
│  Decoding, playback, synchronization, compositing,      │
│  rendering, audio processing, exporting.                │
├─────────────────────────────────────────────────────────┤
│  Data Layer (core/database, core/datastore)             │
│  Project persistence, metadata, preferences, caches.    │
└─────────────────────────────────────────────────────────┘
```

The UI never contains video-processing logic. The media engine is kept
sufficiently independent that an iOS implementation could later reuse the same
project-file specification and editing semantics.

## Module map

| Module | Responsibility |
|--------|----------------|
| `app` | Application entry, navigation host, theme wiring |
| `core:common` | Result type, IDs, coroutine dispatchers |
| `core:model` | The non-destructive edit model (Project, Timeline, Clip, …) |
| `core:designsystem` | Color tokens, gradients, spacing, typography, theme |
| `core:database` | Room index + serialized project document storage |
| `core:datastore` | User preferences (theme, defaults) |
| `core:media` | Metadata extraction, thumbnails, composition, export, preview |
| `editing` | Edit commands, undo/redo history, project creation |
| `feature:home` | Home screen |
| `feature:projects` | All Projects, search, filters, trash |
| `feature:mediaimport` | Photo Picker import + project creation |
| `feature:editor` | The editing workspace (preview, timeline, tools) |
| `feature:export` | Export settings, progress, completion, failure |
| `feature:settings` | App settings |

## The non-destructive edit model

Every project is stored as an editable timeline description. Original media
files are never modified. The model lives in `core:model`:

- `Project` — versioned document: id, name, dates, canvas, frame rate, media
  references, timeline, export preferences.
- `Timeline` — separate ordered lists for video clips, audio clips, and text
  overlays, so layering and render order are explicit.
- `Clip` — source in/out, timeline placement, speed, volume, transform, filter,
  color adjustments.
- `MediaRef` — a persistable URI reference plus extracted metadata.

All timing is integer microseconds (`Timing`) to avoid cumulative floating-point
drift. Conversions to seconds and frame positions are centralized.

## Single source of truth: preview == export

`CompositionBuilder` (in `core:media`) is the single bridge that converts the
`Project` model into a Media3 `Composition`. Both preview and export consume the
same model, so the exported video faithfully reproduces the preview. There is no
second interpretation of the edit.

## Persistence strategy

Two stores, deliberately separated:

- **Room index** (`projects` table): fast listing, search, sort, trash, favorites.
- **Serialized document** (`files/projects/<id>.json`): the authoritative edit.

Writes go document-first, then index, so the index never points at a missing
document. Document writes are atomic (write to temp, then rename) so an
interrupted save never corrupts a project. This is the crash-safe persistence
the product requires.

## Undo/redo

`EditHistory` holds a bounded stack of reversible `EditCommand` value objects.
Commands operate on the immutable `Project` and produce a new one. A new edit
clears the redo branch. Structural timeline changes re-pack clips so positions
stay contiguous and deterministic.
