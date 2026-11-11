#version 330 core
in vec3 vColor;
out vec4 fragColor;

void main() {
    vec2 d = gl_PointCoord * 2.0 - 1.0;
    float r2 = dot(d, d);
    if (r2 > 1.0) discard;
    float a = exp(-r2 * 4.0);
    vec3 c = vColor * a;
    // Soft tonemap, then gamma: the sky pass already wrote gamma-encoded colour.
    c = 1.0 - exp(-c);
    fragColor = vec4(pow(c, vec3(1.0 / 2.2)), 0.0);
}
