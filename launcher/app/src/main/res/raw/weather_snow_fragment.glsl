// weather_snow (fragment): a round flake; near ones are larger, so the same soft edge reads as out of focus.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec3 col;
varying float soft;
uniform float night;
vec3 grade(vec3 c) {
  float l = dot(c, vec3(0.3, 0.55, 0.15));
  return mix(c, vec3(1.0, 0.36, 0.2) * l * 1.6, night * 0.85) * (1.0 - 0.8 * night);
}
void main() {
  float r = length(gl_PointCoord - 0.5) * 2.0;
  float s = 1.0 - smoothstep(mix(0.2, 0.45, soft), 1.0, r);
  gl_FragColor = vec4(grade(col * s), 1.0);
}
