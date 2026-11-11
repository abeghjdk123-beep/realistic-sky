// Shared code for the Realistic Sky shaders, pasted in by the loader at each include line.
// Units: kilometres. World axes follow Minecraft: +X east, +Y up, +Z south.

const float PI = 3.14159265359;
const float RG = 6360.0;
const float RT = 6460.0;
const vec3 BETA_R = vec3(5.802e-3, 13.558e-3, 33.1e-3);
const float H_R = 8.0;
const float BETA_M = 3.996e-3;
const float BETA_M_EXT = 4.40e-3;
const float H_M = 1.2;
const vec3 BETA_O = vec3(0.650e-3, 1.881e-3, 0.085e-3);

const float CLOUD_BOTTOM = 1.2;
const float CLOUD_TOP = 3.0;
const float CLOUD_SIGMA = 42.0;

vec2 raySphere(vec3 ro, vec3 rd, float r) {
    float b = dot(ro, rd);
    float c = dot(ro, ro) - r * r;
    float d = b * b - c;
    if (d < 0.0) return vec2(-1.0);
    d = sqrt(d);
    return vec2(-b - d, -b + d);
}

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float remap(float v, float a, float b, float c, float d) {
    return c + (v - a) / (b - a) * (d - c);
}

float hgPhase(float mu, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(1.0 + g2 - 2.0 * g * mu, 1.5));
}

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// ---- sky-view lookup table: world azimuth x elevation (0..90 deg, denser near the horizon)
vec2 skyLutUv(vec3 d) {
    float az = atan(d.z, d.x);
    float el = asin(clamp(d.y, 0.0, 1.0));
    return vec2(az / (2.0 * PI) + 0.5, sqrt(el / (0.5 * PI)));
}

vec3 skyLutDir(vec2 uv) {
    float az = (uv.x - 0.5) * 2.0 * PI;
    float el = uv.y * uv.y * 0.5 * PI;
    return vec3(cos(el) * cos(az), sin(el), cos(el) * sin(az));
}

// ---- clouds
uniform sampler3D uNoise;
uniform vec3 uCloudOffset;
uniform float uCoverage;
uniform float uRain;

float n01(float v) { return clamp((v - 0.45) * 2.0, 0.0, 1.0); }

// Weather: where clouds are (coverage) and what kind (0 = small fair-weather puffs, 1 = big towering cumulus).
vec2 cloudWeather(vec2 xz) {
    float covMap = n01(texture(uNoise, vec3(xz / 38.0, 0.37)).r);
    float typeMap = texture(uNoise, vec3(xz / 21.0 + 0.5, 0.71)).g;
    typeMap = smoothstep(0.25, 0.75, typeMap);
    float cov = clamp(uCoverage + (covMap - 0.5) * 0.9, 0.0, 1.0);
    cov = mix(cov, 0.97, uRain);
    return vec2(cov, mix(typeMap, 1.0, uRain));
}

float cloudDensity(vec3 p, bool detail, out float hf) {
    float h = length(p) - RG;
    hf = (h - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM);
    if (hf <= 0.0 || hf >= 1.0) return 0.0;
    vec3 q = vec3(p.x, h, p.z) + uCloudOffset;

    vec2 w = cloudWeather(q.xz);
    float cov = w.x, type = w.y;
    if (cov < 0.02) return 0.0;

    // Small clouds stay low and flat; big ones tower. Flat bases, rounded tops.
    float top = mix(0.28, 1.0, type);
    float hg = smoothstep(0.0, 0.06, hf) * smoothstep(top, top * 0.35, hf);
    if (hg <= 0.0) return 0.0;

    // Two shape scales so the sky mixes small puffs with larger clouds.
    float small = n01(texture(uNoise, q / vec3(2.4, 1.6, 2.4)).r);
    float large = n01(texture(uNoise, q / vec3(6.5, 3.5, 6.5) + 0.31).r);
    float base = mix(small, large * 0.85 + small * 0.15, type);
    base = remap(base * hg, 1.0 - cov, 1.0, 0.0, 1.0);
    if (base <= 0.0) return 0.0;
    base *= cov;

    if (detail) {
        // Erode edges with Worley detail: wispy bases, cauliflower tops.
        vec4 dn = texture(uNoise, q / 0.55);
        float det = dn.g * 0.625 + dn.b * 0.25 + dn.a * 0.125;
        det = mix(1.0 - det, det, clamp(hf * 3.0 / top, 0.0, 1.0));
        base = remap(base, det * mix(0.4, 0.55, type), 1.0, 0.0, 1.0);
    }
    return clamp(base, 0.0, 1.0) * mix(1.0, 1.5, uRain);
}
