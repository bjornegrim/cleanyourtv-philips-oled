// weather_rain (vertex), pass 7: 350 thin streaks, each a quad of two triangles (no instancing in ES 2.0, so each
// drop's four corners are in the buffer). Diagonal with the wind, two depths: far ones short, faint and slower.
attribute vec4 drop;      // x and y from 0 to 1, depth from 0 (far) to 1 (near), corner 0..3 (along + 2 * side)
uniform vec2 res;
uniform float t;
uniform float slant;      // sideways per unit fallen
uniform float amount;
uniform vec2 clock;
uniform vec3 tint;
uniform vec3 lit;
varying vec2 st;          // along the streak 0 (tail) to 1 (head), across -1 to 1
varying vec3 col;
void main() {
  float z = drop.z, aspect = res.x / res.y, span = aspect + 0.5;
  float speed = mix(0.30, 0.58, z);                    // screen heights per second
  float len = mix(0.030, 0.085, z);
  float fall = fract(drop.y + t * speed / 1.3);
  float y = 0.65 - fall * 1.3;
  float x = mod(drop.x * span + fall * 1.3 * slant, span) - 0.5 * span;   // wraps only off screen
  vec2 dir = normalize(vec2(slant, -1.0));
  float along = mod(drop.w, 2.0), side = floor(drop.w / 2.0) * 2.0 - 1.0;
  vec2 head = vec2(x, y);
  vec2 q = head - dir * len * (1.0 - along) + vec2(-dir.y, dir.x) * side * (1.0 / res.y);
  gl_Position = vec4(q.x / aspect * 2.0, q.y * 2.0, 0.0, 1.0);
  st = vec2(along, side);
  vec2 c = (head - clock) * vec2(0.8, 1.2);
  float l = 1.0 / (1.0 + dot(c, c) * 40.0);
  float v = smoothstep(1.45, 0.4, length(head * vec2(0.8, 1.1)));
  col = (tint * mix(0.10, 0.24, z) + lit * l * mix(0.2, 0.5, z)) * amount * v;
}
