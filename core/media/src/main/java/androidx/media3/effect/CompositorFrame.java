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

import androidx.media3.common.GlTextureInfo;

  /** Holds information on a frame and how to release it. */
final class CompositorFrame {
    public final GlTextureProducer textureProducer;
    public final GlTextureInfo texture;
    public final long presentationTimeUs;
    public final OverlaySettings overlaySettings;
    public final int inputIndex;

    public CompositorFrame(
        GlTextureProducer textureProducer,
        GlTextureInfo texture,
        long presentationTimeUs,
        OverlaySettings overlaySettings, int inputIndex) {
      this.inputIndex = inputIndex;
      this.textureProducer = textureProducer;
      this.texture = texture;
      this.presentationTimeUs = presentationTimeUs;
      this.overlaySettings = overlaySettings;
    }
  }
