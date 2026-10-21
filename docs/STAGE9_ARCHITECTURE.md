# STAGE 9 ARCHITECTURE SPECIFICATION
## Professional Visual Effects, Advanced Transitions, Masks, Chroma Key, Blend Modes, and Keyframes

**Date:** October 20, 2026  
**Status:** Qualified, Reconciled & Accepted  
**Target Environment:** Android API 36 ARM64 Emulator (`cricpro_api36`, `emulator-5554`)  
**Production Baseline Commit:** `9bf3ab21798b9d722cc32a5d80873796cfd4e9f5`  
**Media Engine:** AndroidX Media3 1.5.1 with Custom OpenGL ES / GLSL Shaders  

---

## 1. System Overview & Architectural Integration

Stage 9 extends the Android Video Editor into a professional, hardware-accelerated creative studio without introducing foreign rendering engines, AI abstractions, or destructive operations. All processing is local, deterministic, and executed on the device GPU via OpenGL ES 2.0/3.0 fragment shaders within AndroidX Media3 1.5.1's composition graph.

```
+-----------------------------------------------------------------------------------+
|                                 USER INTERFACE                                    |
|   Compose Contextual Toolbars (Edit | Effects | Color | Transition | Mask | PiP)  |
|   Timeline Indicators (Effect Spans, Transition Badges, Keyframe Diamonds)        |
|   Keyframe Steppers (< Prev, Next >), Interpolation Pickers (Linear, Hold, Smooth)|
|   Direct Preview Manipulation Handles (Mask & PiP Transform)                      |
+-----------------------------------------------------------------------------------+
                                         |
                                  Commands (UDF)
                                         v
+-----------------------------------------------------------------------------------+
|                                EDIT HISTORY & MODEL                               |
|   Project -> Timeline -> VideoClips / OverlayClips / Transitions / MediaRefs      |
|   Clip / OverlayClip: visualEffects, mask, chromaKey, blendMode, keyframeTracks    |
+-----------------------------------------------------------------------------------+
                                         |
                           CompositionBuilder.kt (Shared)
                                         |
        +--------------------------------+--------------------------------+
        v                                                                 v
+--------------------------------+                               +--------------------------------+
|        PREVIEW PIPELINE        |                               |        EXPORT PIPELINE         |
|   CompositionPlayer            |                               |   Media3 Transformer           |
|   PreviewVideoCompositor       |                               |   DefaultVideoCompositor       |
|   ForwardingAudioSink          |                               |   Hardware / Muxer Encoder     |
+--------------------------------+                               +--------------------------------+
        |                                                                 |
        +--------------------------------+--------------------------------+
                                         v
+-----------------------------------------------------------------------------------+
|                              SHARED GL RENDER GRAPH                               |
|   1. Source Decode & Crop / Flips                                                 |
|   2. Clip-local Visual Effects (Color Grades, Adjustments, Blur, Sharpen, Grain)  |
|   3. Mask (Alpha evaluation via Signed Distance Functions: Rect, Circle, Polygon) |
|   4. Chroma Key (YUV/RGB chromaticity keying, spill suppression, alpha modulation)|
|   5. Transition Overlap / Modulation (13 Transitions: Dissolves, Wipes, Slides)   |
|   6. Compositor Layer Blending (13 Standard Modes: Normal, Multiply, Screen, Add) |
|   7. Dynamic Keyframing (Animated Opacity, Mask Params, Effect Intensities)       |
|   8. Stickers (StickerOverlayEffect)                                              |
|   9. Text Captions (TextOverlayEffect)                                            |
|  10. Canvas Presentation & Scaler                                                 |
+-----------------------------------------------------------------------------------+
```

---

## 2. Core Domain Data Models (`:core:model`)

### 2.1 Visual Effects Model

```kotlin
@Serializable
enum class VisualEffectType {
    // 9 Color Adjustments
    Exposure, Brightness, Contrast, Saturation, Temperature, Tint, Highlights, Shadows, Hue,
    // 8 Spatial & Stylistic Image Effects
    GaussianBlur, Sharpen, Vignette, FilmGrain, BlackAndWhite, Sepia, FadeWashed, InvertColor,
    // 7 Creative Presets
    PresetCinematic, PresetVintage, PresetWarmFilm, PresetCoolFilm, PresetBlackAndWhite, PresetVibrant, PresetSoftPortrait
}

@Serializable
enum class KeyframeInterpolation {
    Linear,
    Hold,
    Smooth
}

@Serializable
data class VisualKeyframe(
    val id: String,
    val timeMicros: Long,
    val value: Float,
    val interpolation: KeyframeInterpolation = KeyframeInterpolation.Linear
)

@Serializable
data class VisualEffect(
    val id: String,
    val type: VisualEffectType,
    val enabled: Boolean = true,
    val startTimeMicros: Long = 0L,
    val durationMicros: Long? = null,
    val intensity: Float = 1.0f,
    val parameters: Map<String, Float> = emptyMap(),
    val keyframes: List<VisualKeyframe> = emptyList(),
)
```

### 2.2 Layer Masks Model

```kotlin
@Serializable
enum class MaskType {
    Rectangle,
    RoundedRectangle,
    Circle,
    LinearGradient,
    Polygon // Supports 3-8 vertices (Triangle, Pentagon, Hexagon, Octagon, etc.)
}

@Serializable
data class LayerMask(
    val type: MaskType,
    val centerX: Float = 0.0f,       // -1.0 .. 1.0 normalized offset from layer center
    val centerY: Float = 0.0f,       // -1.0 .. 1.0 normalized offset from layer center
    val scaleX: Float = 0.5f,        // Normalized width fraction
    val scaleY: Float = 0.5f,        // Normalized height fraction
    val rotationDegrees: Float = 0f, // 0 .. 360 degrees
    val feather: Float = 0.0f,       // 0.0 .. 1.0 edge softness
    val invert: Boolean = false,     // Invert mask interior / exterior
    val polygonSides: Int = 5,       // Minimum 3, maximum 8 vertices
    val enabled: Boolean = true
)
```

### 2.3 Chroma Key Model

```kotlin
@Serializable
data class ChromaKeySettings(
    val enabled: Boolean = false,
    val keyColor: Int = PRESET_GREEN, // ARGB format, default Green (#00FF00)
    val tolerance: Float = 0.4f,      // 0.0 .. 1.0 color similarity threshold
    val softness: Float = 0.1f,       // 0.0 .. 1.0 edge alpha falloff
    val spillSuppression: Float = 0.5f// 0.0 .. 1.0 residual color suppression
)
```

### 2.4 GPU Blend Modes Model

All 13 standard industry blend modes are modeled and implemented across both preview and export shaders:

```kotlin
@Serializable
enum class BlendMode {
    Normal,       // modeInt = 0
    Multiply,     // modeInt = 1: b * f
    Screen,       // modeInt = 2: 1 - (1 - b) * (1 - f)
    Overlay,      // modeInt = 3: b < 0.5 ? 2*b*f : 1 - 2*(1-b)*(1-f)
    Darken,       // modeInt = 4: min(b, f)
    Lighten,      // modeInt = 5: max(b, f)
    Add,          // modeInt = 6: min(b + f, 1.0)
    ColorDodge,   // modeInt = 7: f == 1.0 ? 1.0 : min(1.0, b / (1.0 - f))
    ColorBurn,    // modeInt = 8: f == 0.0 ? 0.0 : max(0.0, 1.0 - (1.0 - b) / f)
    HardLight,    // modeInt = 9: f < 0.5 ? 2*b*f : 1 - 2*(1-b)*(1-f)
    SoftLight,    // modeInt = 10: (1-2*f)*b*b + 2*f*b
    Difference,   // modeInt = 11: abs(b - f)
    Exclusion     // modeInt = 12: b + f - 2*b*f
}
```

### 2.5 Transitions Model

All 13 standard transition types are fully implemented:

```kotlin
@Serializable
enum class TransitionType {
    CrossDissolve,
    FadeThroughBlack,
    FadeThroughWhite,
    SlideLeft,
    SlideRight,
    WipeLeft,
    WipeRight,
    WipeUp,
    WipeDown,
    Push,
    Split,
    ZoomDissolve,
    SmoothZoom
}
```

---

## 3. GPU Shader Algorithms & Mathematics

### 3.1 Signed Distance Field Polygon Mask ($N$-gon)

For polygon masks, normalized UV coordinates relative to mask center $(c_x, c_y)$ are rotated by $-\theta$ and transformed into polar coordinates $(r, \phi)$:

$$\phi' = \text{mod}\left(\phi - \frac{\pi}{2}, \frac{2\pi}{N}\right) - \frac{\pi}{N}$$
$$d = r \cos(\phi') - r_0$$

Where $r_0 = \frac{\text{scale}}{2} \cos\left(\frac{\pi}{N}\right)$ defines the in-radius of the regular polygon. Edge alpha is modulated via Hermite interpolation:

$$\alpha = \text{smoothstep}(\text{feather}, -\text{feather}, d)$$

If `invert == true`, $\alpha = 1.0 - \alpha$. Background video pixels outside the mask remain completely intact, and overlay video inside the mask is composited using the selected blend mode.

### 3.2 Additive & Advanced Blend Modes GLSL Implementation

In `DefaultVideoCompositor.java` and `PreviewVideoCompositor.java`, fragment blending is computed in normalized linear color space:

- **Add:**
  $$\mathbf{C}_{\text{blend}} = \min(\mathbf{B} + \mathbf{F}, 1.0)$$
- **Color Dodge:**
  $$C_{\text{blend}, i} = \begin{cases} 1.0 & F_i \ge 1.0 \\ \min(1.0, B_i / (1.0 - F_i)) & F_i < 1.0 \end{cases}$$
- **Color Burn:**
  $$C_{\text{blend}, i} = \begin{cases} 0.0 & F_i \le 0.0 \\ \max(0.0, 1.0 - (1.0 - B_i) / F_i) & F_i > 0.0 \end{cases}$$
- **Difference & Exclusion:**
  $$\mathbf{C}_{\text{diff}} = |\mathbf{B} - \mathbf{F}|, \quad \mathbf{C}_{\text{excl}} = \mathbf{B} + \mathbf{F} - 2\mathbf{B}\mathbf{F}$$

Final compositing respects layer alpha $\alpha_f$ and animated opacity $O(t)$:

$$\mathbf{C}_{\text{final}} = \mathbf{B} \cdot (1.0 - \alpha_f \cdot O(t)) + \mathbf{C}_{\text{blend}} \cdot (\alpha_f \cdot O(t))$$

### 3.3 Visual Keyframe Interpolation

Visual keyframes support three distinct interpolation regimes:
1. **Linear:**
   $$V(t) = V_1 + (V_2 - V_1) \cdot \frac{t - t_1}{t_2 - t_1}$$
2. **Hold:**
   $$V(t) = V_1 \quad \forall t \in [t_1, t_2)$$
3. **Smooth (Hermite / Smoothstep S-curve):**
   $$u = \frac{t - t_1}{t_2 - t_1}, \quad w = u^2 (3 - 2u), \quad V(t) = V_1 + (V_2 - V_1) \cdot w$$

---

## 4. Preview and Export Symmetry

The rendering pipeline enforces absolute identity between the preview controller (`CompositionPreviewController` + `PreviewVideoCompositor`) and the export engine (`ExportEngine` + `DefaultVideoCompositor`):
- Both engines share the same `CompositionBuilder` transformation routines.
- Both engines compile the identical GLSL fragment shader programs for transitions, visual effects, and multi-input layer blending.
- Verified by automated on-device pixel parity assertions (`PreviewPixelParityDeviceTest` and `Stage9CombinedProjectDeviceTest`), yielding overall mean RGB error $\le 0.63$ levels (limit $\le 12.0$).
