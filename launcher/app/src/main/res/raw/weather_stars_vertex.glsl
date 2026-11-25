// AuroraView's star sprites, plus amount (clear sky and darkness) and night mode.
attribute vec4 star;   // x and y from 0 to 1, twinkle phase, brightness
uniform vec2 res;
uniform float t;
uniform float amount;
varying float glow;
void main() {
  float w = res.x + 8.0;
  vec2 q = vec2(mod(star.x * w + t * 1.2, w) - 4.0, star.y * res.y);
  vec2 p = (q - 0.5 * res) / res.y;
  gl_Position = vec4(q / res * 2.0 - 1.0, 0.0, 1.0);
  gl_PointSize = 4.0 * res.y / 540.0;
  glow = amount * star.w * (0.5 + 0.5 * sin(t * 0.7 + star.z))
       * smoothstep(-0.2, 0.4, p.y) * smoothstep(1.35, 0.35, length(p * vec2(0.85, 1.15)));
}
