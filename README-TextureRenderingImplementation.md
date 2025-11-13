## Key Modifications for Texture + Scrolling (Replica Island Migration Sim)
This builds directly on the prior ES 3.0 code, simulating a side-scroller like Replica Island (e.g., scrolling background layers). The fixed quad acts as the "viewport," revealing a portion of the wide PNG texture, which scrolls horizontally per frame.

1. **Texture Loading**:
    - Added `loadTexture()`: Loads `background.png` from `assets/` (place a wide PNG there, e.g., 2048x512 seamless strip). Decodes to Bitmap, uploads via `GLUtils.texImage2D`.
    - Config: Linear filtering for smooth scroll; `GL_REPEAT` on S (horizontal) for seamless wrapping if texture is tileable.
    - Fallback: Logs error if missing (add a procedural gradient for testing if needed).

2. **Vertex Data**:
    - Interleaved VBO: Each vertex now has `vec2 pos + vec2 tex` (16 bytes stride). Base texcoords cover full [0,1] quad—scrolling offsets them.
    - Indices unchanged (two triangles form the quad).

3. **Shaders**:
    - **Vertex**: New `in vec2 vTex`, `out vec2 texCoord`, `uniform vec2 uOffset`. Applies offset to texcoords (could do in fragment too).
    - **Fragment**: Samples `texture(uTexture, texCoord)` instead of solid color. Binds sampler to texture unit 0.

4. **Rendering**:
    - Bind texture + set uniform in `onDrawFrame`.
    - **Scrolling**: Time-based delta (ns to sec). `scrollOffsetX -= speed * delta` moves texture left (adjust sign/speed for direction). For infinite scroll, uncomment wrap modulo (assumes tex width normalized to >1 units).
    - Single draw; VAO encapsulates state.

5. **Attributes Setup**:
    - Two pointers: Pos at offset 0, Tex at 8 bytes. Both enabled in VAO.

6. **Cleanup**: Delete texture in `onDetachedFromWindow`.

**Notes**:
- **Portion Rendering**: The quad shows ~1x1 tex units; if PNG is wider (e.g., texcoords scaled), it reveals a "slice." For Replica-style layers, duplicate for parallax (multiple textures/offsets).
- **Testing**: Add `background.png` to `app/src/main/assets/`. On emulator/device, scrolls smoothly. Speed=1.0 is arbitrary—tune for 60FPS feel.
- **Migration Tie-In**: Mimics Replica Island's tile-based backgrounds (ES 1.x fixed-function) now with shaders/VAOs for ES 3.0 efficiency. No matrices yet—add MVP for camera if expanding.

Run in Android Studio; debug with GPU Inspector for texcoords/offsets. If issues (e.g., black texture), check shader compile logs via `glGetShaderInfoLog`. Let me know for further tweaks!