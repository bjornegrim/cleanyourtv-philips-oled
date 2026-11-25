// clock_aurora, pass 1 of 4 (AuroraView): everything about a curtain that depends only on the screen column.
// Drawn into a (width x 3) texture, one row per curtain, so the costly noise runs 2,880 times a frame
// instead of half a million. GLSL ES 1.0 (OpenGL ES 2.0). Started from work/design-forslag/12-skarmslackare-jade.html.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;   // time grows for hours; mediump (fp16 on Mali) would make the motion step
#else
precision mediump float;
#endif
uniform vec2 res;        // render size in pixels: 960 x 540
uniform float t;         // seconds

// Hash without sine (Dave Hoskins): stable at large coordinates, unlike fract(sin(x) * 43758) on a GPU.
float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) {
  float v = 0.0, a = 0.5;
  for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.03 + vec2(1.7, 9.2); a *= 0.5; }
  return v;
}

void main() {
  // seed, speed and lift of each curtain, far to near (rows 0 to 2), as in the mockup
  vec3 curtain = gl_FragCoord.y < 1.0 ? vec3(0.0, 0.018, 0.10)
               : gl_FragCoord.y < 2.0 ? vec3(3.7, 0.026, 0.0) : vec3(8.1, 0.034, -0.12);
  float seed = curtain.x, speed = curtain.y, lift = curtain.z;
  float px = (gl_FragCoord.x - 0.5 * res.x) / res.y;
  // slow, deep folds, and a finer fold riding on them so the curtain curls rather than sways
  float x = px + 0.55 * (fbm(vec2(px * 0.9 + seed, t * speed)) - 0.5);
  x += 0.08 * (noise(vec2(x * 4.0 - seed, t * speed * 2.3)) - 0.5);
  // the wavy lower edge
  float h = lift + 0.34 * (fbm(vec2(x * 1.1 + seed, t * speed * 0.8)) - 0.5);
  // fine rays that drift slowly sideways, each curtain its own way
  float drift = (fract(seed * 0.37) - 0.5) * 0.7;
  float rays = 0.25 + 0.75 * pow(noise(vec2(x * 60.0 + seed * 13.0 + t * drift, t * 0.12)), 1.6)
                          * (0.6 + 0.4 * noise(vec2(x * 9.0 - seed + t * drift * 0.15, t * 0.05)));
  // open sky between arcs, soft edges
  float g = smoothstep(0.34, 0.74, fbm(vec2(x * 1.3 + seed * 3.1, t * speed * 1.4)));
  // how far the rays lean sideways higher up (pass 2), 0.5 is upright
  float lean = noise(vec2(x * 2.0 + seed * 5.0, t * speed * 1.7));
  // the edge in 16 bits over two channels: 8 bits stepped ~2 screen pixels as it moved
  float v = clamp(h + 0.5, 0.0, 1.0) * 255.0;
  gl_FragColor = vec4(floor(v) / 255.0, fract(v), rays * g * g, lean);
}
