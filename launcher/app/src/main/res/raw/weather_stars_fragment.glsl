#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying float glow;
uniform float night;
vec3 grade(vec3 c) {
  float l = dot(c, vec3(0.3, 0.55, 0.15));
  return mix(c, vec3(1.0, 0.36, 0.2) * l * 1.6, night * 0.85) * (1.0 - 0.8 * night);
}
void main() {
  float s = smoothstep(0.5, 0.1, length(gl_PointCoord - 0.5));
  gl_FragColor = vec4(grade(vec3(0.75, 0.8, 0.9) * (0.33 * glow * s)), 1.0);
}
