// weather_snow (vertex), pass 6: 720 flakes as point sprites in three depths. Far flakes are many, small, faint and
// slow; near ones few, large, soft and a little faster, which gives the parallax. Everything is worked out from time,
// so the CPU only uploads the flakes once. Flakes near the clock catch its glow.
attribute vec4 flake;     // x and y from 0 to 1, depth from 0 (far) to 1 (near), phase
uniform vec2 res;         // 960 x 540
uniform float t;
uniform float drift;      // the wind so far, in screen heights, summed on the CPU so a change of wind never jumps
uniform float amount;
uniform vec2 clock;       // the clock's centre, in p units
uniform vec3 tint;        // the flakes' own colour: cold white with a little of the palette
uniform vec3 lit;         // the clock glow's colour
varying vec3 col;
varying float soft;
void main() {
  float z = flake.z, aspect = res.x / res.y, span = aspect + 0.2;
  float speed = mix(0.011, 0.046, z * z);              // screen heights per second: 6 to 25 px/s at 540
  float y = 0.55 - fract(flake.y + t * speed / 1.1) * 1.1;
  // each flake sways at its own pace, and flutters a little on top
  float sway = sin(t * (0.25 + 0.3 * fract(flake.w * 7.13)) + flake.w) * mix(0.006, 0.028, z)
             + sin(t * 1.3 + flake.w * 3.0) * mix(0.001, 0.004, z);
  float x = mod(flake.x * span + drift * mix(0.35, 1.0, z) + sway, span) - 0.5 * span;
  vec2 p = vec2(x, y);
  gl_Position = vec4(x / aspect * 2.0, y * 2.0, 0.0, 1.0);
  gl_PointSize = mix(1.7, 17.0, z * z * z) * res.y / 540.0;
  soft = z;
  vec2 q = (p - clock) * vec2(0.8, 1.2);
  float l = 1.0 / (1.0 + dot(q, q) * 40.0);
  float v = smoothstep(1.45, 0.4, length(p * vec2(0.8, 1.1)));
  col = (tint * mix(0.22, 0.16, z) + lit * l * mix(0.35, 0.45, z)) * amount * v;   // near ones are large, so fainter
}
