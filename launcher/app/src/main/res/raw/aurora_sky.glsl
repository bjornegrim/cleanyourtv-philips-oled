// clock_aurora, pass 2 of 4 (AuroraView): the night sky and three curtains, drawn at 960 x 180 and stretched to the
// screen's height in pass 3: the curtains are soft upwards and sharp only sideways. Reads each curtain's edge, rays and
// lean from pass 1 (aurora_columns.glsl). GLSL ES 1.0 (OpenGL ES 2.0).
// mediump (fp16 on Mali, twice the speed) for the colour maths; highp only for the texture column, which must hit one
// texel of 960.
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

// One curtain: sharp at its wavy foot, fading upwards in rays that lean and pulse slowly, with a soft bloom at the
// foot. soft is the foot's blur and k how fast it fades: the far curtains are softer, so they read as further away.
vec3 curtain(vec2 p, HI float u, float row, float soft, float k) {
  vec4 a = texture2D(cols, vec2(u, row));
  float h = (a.r * 255.0 + a.g) / 255.0 - 0.5;
  float d = p.y - h;
  if (d < -0.2) return vec3(0.0);                      // well below the foot even the bloom is under 1/255
  float up = max(d, 0.0);
  // the rays higher up come from a column further sideways, so they lean the way the curtain bends
  HI float ur = u + (a.a - 0.5) * 0.32 * up / aspect;
  vec4 b = texture2D(cols, vec2(ur, row));
  float foot = smoothstep(-soft, soft * 1.6, d);
  float fade = exp(-up * k);
  // light running slowly up the rays, each ray in its own phase; never fast enough to read as a flicker
  float xr = float((ur - 0.5) * aspect);
  float run = 0.72 + 0.28 * sin(d * 9.0 + 3.0 * sin(xr * 23.0 + row * 5.0) - pulse);
  float bloom = exp(-abs(d) * 24.0) * 0.30 * a.b;
  vec3 col = mix(base, crown, smoothstep(0.02, 0.38, d));
  return col * (b.b * foot * fade * run + bloom);
}

void main() {
  HI vec2 uv = gl_FragCoord.xy / res;
  vec2 p = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);    // y from -0.5 (bottom) to 0.5 (top), as in the mockup

  // night sky: near black, a breath of the palette colour towards the top
  vec3 col = vec3(0.004, 0.006, 0.012) + base * 0.025 * smoothstep(-0.5, 0.5, p.y);

  // three curtains, far to near: slower, dimmer, softer and higher in the distance
  vec3 far = curtain(p, uv.x, 0.5 / 3.0, 0.022, 3.0);
  vec3 aur = mix(far, vec3(dot(far, vec3(0.3, 0.55, 0.15))), 0.25) * 0.45   // a little haze takes its colour
           + curtain(p, uv.x, 1.5 / 3.0, 0.015, 3.4) * 0.70
           + curtain(p, uv.x, 2.5 / 3.0, 0.010, 3.8) * 1.00;
  col += aur * 0.85;

  col = 1.0 - exp(-col * 1.25);                        // soft tone map, no clipping
  col *= smoothstep(1.35, 0.35, length(p * vec2(0.85, 1.15)));   // vignette keeps the edges black
  col += (texture2D(dither, gl_FragCoord.xy / 64.0).r - 0.5) / 255.0;   // dither before this target's 8 bits
  gl_FragColor = vec4(max(col, 0.0), 1.0);
}
