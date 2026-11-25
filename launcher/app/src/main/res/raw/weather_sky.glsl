// weather_sky.glsl, pass 2: the sky for the time of day, and the aurora's three curtains when it shows. Drawn at
// 960 x 180 and stretched to the screen's height in pass 4: everything here is soft upwards. aurora_sky.glsl with the
// flat night sky replaced by a gradient and a dawn/dusk glow, and the curtains scaled by `aurora` (skipped at 0).
// GLSL ES 1.0 (OpenGL ES 2.0).
precision mediump float;
#ifdef GL_FRAGMENT_PRECISION_HIGH
#define HI highp
#else
#define HI mediump
#endif
uniform HI vec2 res;      // this target: 960 x 180
uniform float aspect;     // the screen's width over its height
uniform float pulse;      // phase of the light running up the rays, wrapped to 0..2 pi on the CPU
uniform vec3 base;        // curtain foot: the clock glow's colour
uniform vec3 crown;       // curtain tops: its partner colour (Aurora.crown)
uniform sampler2D cols;   // pass 1: one row per curtain, far to near
uniform sampler2D dither; // 64 x 64 noise
uniform vec3 top;         // sky overhead
uniform vec3 hor;         // sky at the horizon, the screen's bottom edge
uniform vec3 sunLo;       // warm glow hugging the horizon at dawn and dusk, already scaled by its strength
uniform vec3 sunHi;       // the cooler rose band above it
uniform float sunX;       // where the sun is under the horizon, in p.x units
uniform float breath;     // how much of the palette colour tints the upper sky
uniform float aurora;     // the curtains' strength, 0 to 1: time of night and clear sky

vec3 curtain(vec2 p, HI float u, float row, float soft, float k) {
  vec4 a = texture2D(cols, vec2(u, row));
  float h = (a.r * 255.0 + a.g) / 255.0 - 0.5;
  float d = p.y - h;
  if (d < -0.2) return vec3(0.0);
  float up = max(d, 0.0);
  HI float ur = u + (a.a - 0.5) * 0.32 * up / aspect;
  vec4 b = texture2D(cols, vec2(ur, row));
  float foot = smoothstep(-soft, soft * 1.6, d);
  float fade = exp(-up * k);
  float xr = float((ur - 0.5) * aspect);
  float run = 0.72 + 0.28 * sin(d * 9.0 + 3.0 * sin(xr * 23.0 + row * 5.0) - pulse);
  float bloom = exp(-abs(d) * 24.0) * 0.30 * a.b;
  vec3 col = mix(base, crown, smoothstep(0.02, 0.38, d));
  return col * (b.b * foot * fade * run + bloom);
}

void main() {
  HI vec2 uv = gl_FragCoord.xy / res;
  vec2 p = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);
  float h = uv.y;                                      // 0 at the horizon, 1 at the top

  vec3 col = mix(hor, top, smoothstep(0.0, 0.85, h)) + base * breath * smoothstep(0.1, 1.0, h);
  // dawn and dusk: an amber line on the horizon under the sun, a rose band above it, fading into the blue
  float dx = p.x - sunX;
  float side = exp(-dx * dx * 0.8);
  col += sunLo * (exp(-h * 6.5) * (0.30 + 0.70 * side) + 0.7 * exp(-dx * dx * 4.0 - h * h * 45.0));
  col += sunHi * exp(-h * 2.4) * (0.45 + 0.55 * side);

  if (aurora > 0.0) {
    vec3 far = curtain(p, uv.x, 0.5 / 3.0, 0.022, 3.0);
    vec3 aur = mix(far, vec3(dot(far, vec3(0.3, 0.55, 0.15))), 0.25) * 0.45
             + curtain(p, uv.x, 1.5 / 3.0, 0.015, 3.4) * 0.70
             + curtain(p, uv.x, 2.5 / 3.0, 0.010, 3.8) * 1.00;
    col += aur * 0.85 * aurora;
  }

  col = 1.0 - exp(-col * 1.25);                        // soft tone map, no clipping
  col *= smoothstep(1.35, 0.35, length(p * vec2(0.85, 1.15)));   // vignette keeps the edges black
  col += (texture2D(dither, gl_FragCoord.xy / 64.0).r - 0.5) / 255.0;
  gl_FragColor = vec4(max(col, 0.0), 1.0);
}
