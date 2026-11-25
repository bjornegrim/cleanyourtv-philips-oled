// weather_rain (fragment): bright at the head, fading to nothing at the tail, soft across.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 st;
varying vec3 col;
uniform float night;
vec3 grade(vec3 c) {
  float l = dot(c, vec3(0.3, 0.55, 0.15));
  return mix(c, vec3(1.0, 0.36, 0.2) * l * 1.6, night * 0.85) * (1.0 - 0.8 * night);
}
void main() {
  float a = st.x * st.x * (1.0 - st.y * st.y);
  gl_FragColor = vec4(grade(col * a), 1.0);
}
