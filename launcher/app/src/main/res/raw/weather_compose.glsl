// weather_compose.glsl, pass 4: the sky stretched to the screen, the fog laid over it in its colour and lit by the
// clock's glow, night mode, the clock's own glow, and dither so the near-black gradients do not band.
// GLSL ES 1.0 (OpenGL ES 2.0).
// The glow behind the clock is drawn here rather than by ClockScene's GradientDrawable: in a TextureView the window
// redraws with every frame, and a full-screen gradient 30 times a second cost more than this pass.
precision mediump float;
#ifdef GL_FRAGMENT_PRECISION_HIGH
varying highp vec2 uv;
#else
varying mediump vec2 uv;
#endif
uniform sampler2D sky;    // pass 2, 960 x 180
uniform sampler2D fog;    // pass 3, 240 x 135
uniform sampler2D dither;
uniform float aspect;
uniform vec3 fogCol;      // the fog's own colour for the time of day
uniform vec3 fogLit;      // the clock glow's colour on the fog, at the clock
uniform vec2 clock;       // the clock's centre, in p units
uniform float night;      // night mode, 0 to 1
uniform vec3 glow;        // the clock glow's colour (ClockScene's palette)
uniform float glowR;      // its radius, in p units: GradientDrawable's 0.55 of the screen's width

vec3 grade(vec3 c) {      // night mode: warm, and a fifth of the light
  float l = dot(c, vec3(0.3, 0.55, 0.15));
  return mix(c, vec3(1.0, 0.36, 0.2) * l * 1.6, night * 0.85) * (1.0 - 0.8 * night);
}

void main() {
  vec3 c = texture2D(sky, uv).rgb;
  float a = texture2D(fog, uv).r;
  vec2 p = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);
  vec2 q = (p - clock) * vec2(0.8, 1.2);
  vec3 f = (fogCol + fogLit / (1.0 + dot(q, q) * 18.0)) * smoothstep(1.5, 0.45, length(p * vec2(0.8, 1.1)));
  c = grade(mix(c, f, a));
  // ClockScene's glow: 25 % at the clock, 8 % halfway, nothing at glowR; in night mode warm red at a fifth
  float r = length(p - clock) / glowR;
  float g = r < 0.5 ? mix(0.251, 0.078, r * 2.0) : mix(0.078, 0.0, min(r * 2.0 - 1.0, 1.0));
  c = mix(c, mix(glow, vec3(0.690, 0.251, 0.165), night), g * (1.0 - 0.78 * night));
  c += (texture2D(dither, gl_FragCoord.xy / 64.0).r - 0.5) / 255.0;
  gl_FragColor = vec4(c, 1.0);
}
