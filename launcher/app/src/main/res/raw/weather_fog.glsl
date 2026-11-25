// weather_fog.glsl, pass 3: how dense the fog is, as one number per pixel at 240 x 135. Fog is soft in every
// direction, so a quarter of the size each way loses nothing, and the noise runs 32,400 times a frame instead of
// half a million. Slow veils near the ground, a thinner one higher up, and a faint overcast deck at the top.
// GLSL ES 1.0 (OpenGL ES 2.0).
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;   // time grows for hours
#else
precision mediump float;
#endif
uniform vec2 res;        // this target: 240 x 135
uniform float aspect;
uniform float t;
uniform float low;       // veils near the ground, 0 to 1
uniform float deck;      // overcast high up, 0 to 1

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
  for (int i = 0; i < 3; i++) { v += a * noise(p); p = p * 2.07 + vec2(5.3, 1.9); a *= 0.5; }
  return v / 0.875;
}

void main() {
  vec2 uv = gl_FragCoord.xy / res;
  float x = (uv.x - 0.5) * aspect, h = uv.y;
  // one slow warp, so the veils curl and thin out rather than slide past like a texture
  float w = noise(vec2(x * 0.7 + t * 0.007, h * 1.6 - t * 0.003));
  // three veils, near to far: the lowest is the thickest, and each drifts its own way and wavers in height
  float d = 0.0;
  for (int i = 0; i < 3; i++) {
    float k = float(i), dir = 1.0 - 2.0 * mod(k, 2.0);
    float c = 0.02 + 0.14 * k + 0.06 * (noise(vec2(x * (0.9 + 0.4 * k) + k * 4.1 + t * 0.004 * dir, t * 0.006)) - 0.5);
    float y = (h - c) / (0.10 - 0.015 * k);
    float body = fbm(vec2(x * (1.5 - 0.3 * k) - t * (0.011 - 0.003 * k) * dir + w * 0.8 + k * 7.0, h * 6.0 + w * 0.5));
    d += exp(-y * y) * smoothstep(0.22, 0.78, body) * (0.85 - 0.22 * k);
  }
  float bed = smoothstep(0.22, 0.0, h);                // an even haze at the bottom, so no veil ends in a hard line
  float c = fbm(vec2(x * 0.55 - t * 0.004 + 2.0, h * 2.2 + w * 0.4));
  float dk = smoothstep(0.50, 1.0, h) * smoothstep(0.30, 0.85, c);
  d = low * (d + bed * 0.35) + deck * dk * 0.30;
  gl_FragColor = vec4(clamp(d, 0.0, 1.0));
}
