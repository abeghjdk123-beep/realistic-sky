#version 330 core
// Real stars (Hipparcos-derived catalog down to magnitude 8) drawn as point sprites.
layout(location = 0) in vec3 aDir;    // equatorial unit vector (J2000)
layout(location = 1) in vec3 aColor;  // linear RGB from B-V colour index
layout(location = 2) in float aMag;   // apparent visual magnitude

uniform mat4 uViewProj;    // projection * camera rotation
uniform mat3 uEqToWorld;
uniform float uExposure;
uniform float uStarScale;
uniform float uTime;
uniform float uPixelScale;

out vec3 vColor;

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

void main() {
    vec3 w = uEqToWorld * aDir;
    if (w.y < -0.03) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        gl_PointSize = 0.0;
        vColor = vec3(0.0);
        return;
    }
    float flux = pow(10.0, -0.4 * aMag);

    // Atmospheric extinction and reddening toward the horizon (Kasten-Young style airmass).
    float alt = max(w.y, 0.0);
    float airmass = 1.0 / (alt + 0.50572 * pow(degrees(asin(alt)) + 6.07995, -1.6364));
    vec3 ext = exp(-airmass * vec3(0.12, 0.17, 0.30));

    // Scintillation: stronger near the horizon.
    float seed = float(gl_VertexID) * 1.731;
    float twAmt = clamp(0.08 + 0.05 * airmass, 0.0, 0.6);
    float tw = 1.0 + twAmt * (sin(uTime * (9.0 + 7.0 * hash11(seed)) + seed * 17.0) * 0.6
                            + sin(uTime * (23.0 + 11.0 * hash11(seed + 3.0)) + seed) * 0.4);

    float brightness = flux * uStarScale * uExposure * tw;
    float size = mix(1.6, 5.0, clamp((3.5 - aMag) / 5.0, 0.0, 1.0)) * uPixelScale;
    // Keep total energy roughly independent of sprite size.
    float peak = brightness * (2.6 / (size * size / (uPixelScale * uPixelScale)));

    vColor = aColor * ext * peak;
    gl_PointSize = size;
    gl_Position = uViewProj * vec4(w, 1.0);
}
