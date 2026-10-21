/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;
import static androidx.media3.common.util.Assertions.checkNotNull;
import android.content.Context;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.media3.common.*;
import androidx.media3.common.util.*;
import java.util.List;
final class Stage9CompositorProgram {
    private static final String TAG = "CompositorGlProgram";
    private static final String VERTEX_SHADER_PATH =
        "shaders/vertex_shader_transformation_es2.glsl";
    private static final String LAYER_FRAGMENT_SHADER =
        "precision highp float;\n"
        + "uniform sampler2D uTexSampler; uniform float uAlphaScale;\n"
        + "uniform int uTransition; uniform int uIncoming; uniform float uProgress;\n"
        + "varying vec2 vTexCoord;\n"
        + "void main() { vec4 c = texture2D(uTexSampler, vTexCoord); float p = clamp(uProgress, 0.0, 1.0);\n"
        + "if (uTransition == 2 && uIncoming == 0) c.rgb *= max(1.0 - 2.0 * p, 0.0);\n"
        + "if (uTransition == 3) c.rgb = mix(c.rgb, vec3(1.0), uIncoming == 1 ? clamp(2.0 - 2.0 * p, 0.0, 1.0) : clamp(2.0 * p, 0.0, 1.0));\n"
        + "float mask = 1.0; if (uIncoming == 1 && p <= 0.0) mask = 0.0;\n"
        + "else if (uIncoming == 1 && p < 1.0) { float f = 0.005;\n"
        + "if (uTransition == 6) mask = smoothstep(1.0-p-f, 1.0-p+f, vTexCoord.x);\n"
        + "if (uTransition == 7) mask = 1.0-smoothstep(p-f, p+f, vTexCoord.x);\n"
        + "if (uTransition == 8) mask = 1.0-smoothstep(p-f, p+f, vTexCoord.y);\n"
        + "if (uTransition == 9) mask = smoothstep(1.0-p-f, 1.0-p+f, vTexCoord.y);\n"
        + "if (uTransition == 11) mask = 1.0-smoothstep(p*0.5-f, p*0.5+f, abs(vTexCoord.x-0.5)); }\n"
        + "gl_FragColor = vec4(c.rgb, c.a * uAlphaScale * mask); }\n";

    private static final String BLEND_VERTEX_SHADER =
        "attribute vec4 aFramePosition;\n"
        + "uniform mat4 uTransformationMatrix;\n"
        + "varying vec2 vTexCoord;\n"
        + "void main() {\n"
        + "  gl_Position = uTransformationMatrix * aFramePosition;\n"
        + "  vTexCoord = aFramePosition.xy * 0.5 + 0.5;\n"
        + "}\n";

    private static final String BLEND_FRAGMENT_SHADER =
        "precision highp float;\n"
        + "uniform sampler2D uTexSampler;\n"
        + "uniform sampler2D uBgSampler;\n"
        + "uniform float uAlphaScale;\n"
        + "uniform vec2 uOutputResolution;\n"
        + "uniform int uBlendMode;\n"
        + "varying vec2 vTexCoord;\n"
        + "void main() {\n"
        + "  vec2 bgCoord = gl_FragCoord.xy / uOutputResolution;\n"
        + "  vec4 bg = texture2D(uBgSampler, bgCoord);\n"
        + "  vec4 fg = texture2D(uTexSampler, vTexCoord);\n"
        + "  float alpha = fg.a * uAlphaScale;\n"
        + "  if (alpha <= 0.0) {\n"
        + "    gl_FragColor = bg;\n"
        + "    return;\n"
        + "  }\n"
        + "  vec3 b = bg.a > 0.00001 ? bg.rgb / bg.a : vec3(0.0);\n"
        + "  vec3 f = fg.rgb;\n"
        + "  vec3 blend = f;\n"
        + "  if (uBlendMode == 1) {\n"
        + "    blend = b * f;\n"
        + "  } else if (uBlendMode == 2) {\n"
        + "    blend = vec3(1.0) - (vec3(1.0) - b) * (vec3(1.0) - f);\n"
        + "  } else if (uBlendMode == 3) {\n"
        + "    blend.r = (b.r < 0.5) ? (2.0 * b.r * f.r) : (1.0 - 2.0 * (1.0 - b.r) * (1.0 - f.r));\n"
        + "    blend.g = (b.g < 0.5) ? (2.0 * b.g * f.g) : (1.0 - 2.0 * (1.0 - b.g) * (1.0 - f.g));\n"
        + "    blend.b = (b.b < 0.5) ? (2.0 * b.b * f.b) : (1.0 - 2.0 * (1.0 - b.b) * (1.0 - f.b));\n"
        + "  } else if (uBlendMode == 4) {\n"
        + "    blend = min(b, f);\n"
        + "  } else if (uBlendMode == 5) {\n"
        + "    blend = max(b, f);\n"
        + "  } else if (uBlendMode == 6) {\n"
        + "    blend = min(b + f, vec3(1.0));\n"
        + "  } else if (uBlendMode == 7) {\n"
        + "    blend = min(b / max(vec3(1.0) - f, vec3(0.001)), vec3(1.0));\n"
        + "  } else if (uBlendMode == 8) {\n"
        + "    blend = max(vec3(1.0) - (vec3(1.0) - b) / max(f, vec3(0.001)), vec3(0.0));\n"
        + "  } else if (uBlendMode == 9) {\n"
        + "    blend.r = (f.r < 0.5) ? (2.0 * b.r * f.r) : (1.0 - 2.0 * (1.0 - b.r) * (1.0 - f.r));\n"
        + "    blend.g = (f.g < 0.5) ? (2.0 * b.g * f.g) : (1.0 - 2.0 * (1.0 - b.g) * (1.0 - f.g));\n"
        + "    blend.b = (f.b < 0.5) ? (2.0 * b.b * f.b) : (1.0 - 2.0 * (1.0 - b.b) * (1.0 - f.b));\n"
        + "  } else if (uBlendMode == 10) {\n"
        + "    vec3 d = mix(((16.0*b-12.0)*b+4.0)*b, sqrt(max(b,vec3(0.0))), step(vec3(0.25),b)); blend = mix(b-(vec3(1.0)-2.0*f)*b*(vec3(1.0)-b), b+(2.0*f-vec3(1.0))*(d-b), step(vec3(0.5),f));\n"
        + "  } else if (uBlendMode == 11) {\n"
        + "    blend = abs(b - f);\n"
        + "  } else if (uBlendMode == 12) {\n"
        + "    blend = b + f - 2.0 * b * f;\n"
        + "  }\n"
        + "  vec3 premultiplied = (1.0-alpha)*bg.rgb + alpha*(1.0-bg.a)*f + alpha*bg.a*blend;\n"
        + "  gl_FragColor = vec4(premultiplied, alpha + bg.a * (1.0-alpha));\n"
        + "}\n";

    private final Context context;
    private final OverlayMatrixProvider overlayMatrixProvider;
    private @Nullable GlProgram glProgram;
    private @Nullable GlProgram blendGlProgram;
    private int intermediateTextureId = C.INDEX_UNSET;
    private int intermediateWidth = -1;
    private int intermediateHeight = -1;

    /**
     * Creates an instance.
     *
     * <p>May be called on any thread.
     */
    public Stage9CompositorProgram(Context context) {
      this.context = context;
      this.overlayMatrixProvider = new OverlayMatrixProvider();
    }

    /** Draws {@link CompositorFrame}s onto an output {@link GlTextureInfo}. */
    // Enhanced for-loops are discouraged in media3.effect due to short-lived allocations.
    @SuppressWarnings("ListReverse")
    public void drawFrame(List<CompositorFrame> framesToComposite, GlTextureInfo outputTexture,
        long outputTimeUs, VideoCompositorSettings settings)
        throws GlUtil.GlException, VideoFrameProcessingException {
      ensureConfigured();
      GlUtil.focusFramebufferUsingCurrentContext(
          outputTexture.fboId, outputTexture.width, outputTexture.height);
      overlayMatrixProvider.configure(new Size(outputTexture.width, outputTexture.height));
      GlUtil.clearFocusedBuffers();

      if (settings instanceof com.videoeditor.core.media.composition.OverlayCompositorSettings) {
        com.videoeditor.core.media.composition.OverlayCompositorSettings project =
            (com.videoeditor.core.media.composition.OverlayCompositorSettings) settings;
        framesToComposite = new java.util.ArrayList<>(framesToComposite);
        framesToComposite.sort(java.util.Comparator.comparingInt((CompositorFrame frame) -> project.stackOrder(frame.inputIndex)).reversed());
      }
      // Draw textures from back to front.
      for (int i = framesToComposite.size() - 1; i >= 0; i--) {
        CompositorFrame frame = framesToComposite.get(i);
        OverlaySettings overlay = settings instanceof com.videoeditor.core.media.composition.OverlayCompositorSettings
            ? settings.getOverlaySettings(frame.inputIndex, outputTimeUs) : frame.overlaySettings;
        com.videoeditor.core.model.BlendMode blendMode = settings instanceof com.videoeditor.core.media.composition.OverlayCompositorSettings
            ? ((com.videoeditor.core.media.composition.OverlayCompositorSettings) settings).blendModeAt(frame.inputIndex, outputTimeUs)
            : com.videoeditor.core.model.BlendMode.Normal;

        if (overlay.alphaScale <= 0f) continue;
        boolean isBackgroundLayer = (i == framesToComposite.size() - 1);
        if (isBackgroundLayer || blendMode == com.videoeditor.core.model.BlendMode.Normal) {
          GLES20.glEnable(GLES20.GL_BLEND);
          GLES20.glBlendFuncSeparate(
              GLES20.GL_SRC_ALPHA,
              GLES20.GL_ONE_MINUS_SRC_ALPHA,
              GLES20.GL_ONE,
              GLES20.GL_ONE_MINUS_SRC_ALPHA);
          blendOntoFocusedTexture(frame, overlay, settings, outputTimeUs);
          GLES20.glDisable(GLES20.GL_BLEND);
        } else {
          // Creative blend mode (Multiply, Screen, Overlay, Darken, Lighten, Add)
          drawWithBlendProgram(frame, overlay, blendMode, outputTexture);
        }
      }
      GlUtil.checkGlError();
    }

    public void release() {
      try {
        if (glProgram != null) {
          glProgram.delete();
          glProgram = null;
        }
        if (blendGlProgram != null) {
          blendGlProgram.delete();
          blendGlProgram = null;
        }
        if (intermediateTextureId != C.INDEX_UNSET) {
          GlUtil.deleteTexture(intermediateTextureId);
          intermediateTextureId = C.INDEX_UNSET;
          intermediateWidth = -1;
          intermediateHeight = -1;
        }
      } catch (GlUtil.GlException e) {
        Log.e(TAG, "Error releasing GL Program", e);
      }
    }

    private void ensureConfigured() throws VideoFrameProcessingException, GlUtil.GlException {
      if (glProgram != null) {
        return;
      }
      {
        glProgram = new GlProgram(BLEND_VERTEX_SHADER, LAYER_FRAGMENT_SHADER);
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);



      }
    }

    private void blendOntoFocusedTexture(CompositorFrame inputFrameInfo, OverlaySettings overlay, VideoCompositorSettings settings, long timeUs) throws GlUtil.GlException {
      GlProgram glProgram = checkNotNull(this.glProgram);
      glProgram.use();
      GlTextureInfo inputTexture = inputFrameInfo.texture;
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, /* texUnitIndex= */ 0);
      float[] transformationMatrix =
          overlayMatrixProvider.getTransformationMatrix(
              /* overlaySize= */ new Size(inputTexture.width, inputTexture.height),
              overlay);
      glProgram.setFloatsUniform("uTransformationMatrix", transformationMatrix);
      glProgram.setFloatUniform("uAlphaScale", overlay.alphaScale);
      com.videoeditor.core.media.composition.OverlayCompositorSettings project = settings instanceof com.videoeditor.core.media.composition.OverlayCompositorSettings
          ? (com.videoeditor.core.media.composition.OverlayCompositorSettings) settings : null;
      glProgram.setIntUniform("uTransition", project == null ? 0 : project.transitionKind(inputFrameInfo.inputIndex, timeUs));
      glProgram.setIntUniform("uIncoming", project != null && project.isIncoming(inputFrameInfo.inputIndex, timeUs) ? 1 : 0);
      glProgram.setFloatUniform("uProgress", project == null ? 0f : project.transitionProgress(inputFrameInfo.inputIndex, timeUs));
      glProgram.bindAttributesAndUniforms();

      // The four-vertex triangle strip forms a quad.
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      GlUtil.checkGlError();
    }

    private void drawWithBlendProgram(
        CompositorFrame inputFrameInfo,
        OverlaySettings overlay,
        com.videoeditor.core.model.BlendMode blendMode,
        GlTextureInfo outputTexture)
        throws GlUtil.GlException {
      if (blendGlProgram == null) {
        blendGlProgram = new GlProgram(BLEND_VERTEX_SHADER, BLEND_FRAGMENT_SHADER);
        blendGlProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      }
      GlProgram blendProgram = checkNotNull(this.blendGlProgram);

      if (intermediateTextureId == C.INDEX_UNSET
          || intermediateWidth != outputTexture.width
          || intermediateHeight != outputTexture.height) {
        if (intermediateTextureId != C.INDEX_UNSET) {
          GlUtil.deleteTexture(intermediateTextureId);
        }
        intermediateTextureId = GlUtil.createTexture(
            outputTexture.width, outputTexture.height, /* useHighPrecisionColorType= */ false);
        intermediateWidth = outputTexture.width;
        intermediateHeight = outputTexture.height;
      }

      // Copy current composite background to intermediate texture
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTextureId);
      GLES20.glCopyTexSubImage2D(
          GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, outputTexture.width, outputTexture.height);

      int modeInt = 0;
      switch (blendMode) {
        case Multiply:
          modeInt = 1;
          break;
        case Screen:
          modeInt = 2;
          break;
        case Overlay:
          modeInt = 3;
          break;
        case Darken:
          modeInt = 4;
          break;
        case Lighten:
          modeInt = 5;
          break;
        case Add:
          modeInt = 6;
          break;
        case ColorDodge:
          modeInt = 7;
          break;
        case ColorBurn:
          modeInt = 8;
          break;
        case HardLight:
          modeInt = 9;
          break;
        case SoftLight:
          modeInt = 10;
          break;
        case Difference:
          modeInt = 11;
          break;
        case Exclusion:
          modeInt = 12;
          break;
        default:
          modeInt = 0;
          break;
      }

      blendProgram.use();
      GlTextureInfo inputTexture = inputFrameInfo.texture;
      blendProgram.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, /* texUnitIndex= */ 0);
      blendProgram.setSamplerTexIdUniform("uBgSampler", intermediateTextureId, /* texUnitIndex= */ 1);
      float[] transformationMatrix =
          overlayMatrixProvider.getTransformationMatrix(
              /* overlaySize= */ new Size(inputTexture.width, inputTexture.height),
              overlay);
      blendProgram.setFloatsUniform("uTransformationMatrix", transformationMatrix);
      blendProgram.setFloatUniform("uAlphaScale", overlay.alphaScale);
      blendProgram.setFloatsUniform("uOutputResolution", new float[] {outputTexture.width, outputTexture.height});
      blendProgram.setIntUniform("uBlendMode", modeInt);
      blendProgram.bindAttributesAndUniforms();

      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      GlUtil.checkGlError();
    }
  }
