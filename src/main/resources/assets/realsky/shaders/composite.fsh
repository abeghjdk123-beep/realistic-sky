#version 330 core
// Pass 3: final sky. Samples the sky table and the low-res clouds, adds sun, moon, Milky Way, nebulae,
// cirrus and the reflective horizon, then tonemaps. Alpha = how visible stars are at each pixel.
#include "common.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform mat4 uInvViewProj;
uniform mat3 uWorldToEq;
uniform vec3 uSunDir;
uniform vec3 uMoonDir;
uniform vec3 uMoonLightDir;
uniform vec3 uPoleDir;
uniform vec3 uSunTrans;
uniform vec3 uMoonTrans;
uniform vec3 uSunColor;
uniform vec3 uAmbient;
uniform float uSunI;
uniform float uExposure;
uniform float uNightScale;
uniform float uSunRadius;
uniform float uMoonRadius;
uniform float uMilkyWay;
uniform float uNebula;
uniform float uCirrus;
uniform vec2 uCloudTexel;
uniform int uCloudsOn;
uniform int uReflect;

uniform sampler2D uSkyLut;
uniform sampler2D uClouds;
uniform sampler2D uMilkyWayTex;

const mat3 EQ2GAL = mat3(
    -0.0548755604, 0.4941094279, -0.8676661490,
    -0.8734370902, -0.4448296300, -0.1980763734,
    -0.4838350155, 0.7469822445, 0.4559837762);

// ---------------------------------------------------------------- deep sky
float fbm3(vec3 p) {
    return texture(uNoise, p).r * 0.6 + texture(uNoise, p * 2.03).g * 0.25 + texture(uNoise, p * 4.1).b * 0.15;
}

vec3 eqDir(float raDeg, float decDeg) {
    float ra = radians(raDeg), dec = radians(decDeg);
    return vec3(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec));
}

// Gaussian blob with noisy structure around an equatorial direction.
vec3 deepSkyObject(vec3 eq, float raDeg, float decDeg, float radiusDeg, vec3 color, float seed) {
    vec3 c = eqDir(raDeg, decDeg);
    if (dot(eq, c) < cos(radians(radiusDeg) * 3.0)) return vec3(0.0);
    float ang = acos(clamp(dot(eq, c), -1.0, 1.0));
    float r = ang / radians(radiusDeg);
    if (r > 3.0) return vec3(0.0);
    float n = fbm3(eq * 9.0 + seed);
    float shape = exp(-r * r * 2.0) * smoothstep(0.25, 0.75, n + 0.35 - r * 0.15);
    return color * shape;
}

vec3 spaceRadiance(vec3 rd) {
    vec3 eq = uWorldToEq * rd;
    vec3 gal = EQ2GAL * eq;
    float l = atan(gal.y, gal.x);
    float b = asin(clamp(gal.z, -1.0, 1.0));
    vec2 uv = vec2(fract(l / (2.0 * PI)), (PI * 0.5 - b) / PI);
    // Explicit LOD avoids the mip seam where the longitude wraps around.
    float mw = textureLod(uMilkyWayTex, uv, 0.0).r;
    mw = mw * mw * (3.0 - 2.0 * mw);

    // The catalogue outline is smooth, so add star-cloud granularity and dark dust lanes.
    float grain = texture(uNoise, gal * 2.3).r;
    float fine = texture(uNoise, gal * 9.0).g * 0.6 + texture(uNoise, gal * 23.0).b * 0.4;
    float core = exp(-(l * l) / (2.0 * 0.35 * 0.35)) * exp(-(b * b) / (2.0 * 0.09 * 0.09));
    float bulge = exp(-(l * l) / (2.0 * 0.16 * 0.16)) * exp(-(b * b) / (2.0 * 0.12 * 0.12));
    float clumps = smoothstep(0.35, 0.9, grain * 0.5 + fine * 0.7);
    float band = mw * (0.25 + 1.1 * clumps) + core * 0.25 * (0.5 + fine) + bulge * 0.35;
    float lanes = smoothstep(0.5, 0.78, fbm3(gal * 4.0 + 7.3)) * exp(-(b * b) / (2.0 * 0.035 * 0.035));
    float lanes2 = smoothstep(0.55, 0.85, texture(uNoise, gal * 7.0 + 3.1).r) * mw;
    band *= (1.0 - 0.8 * lanes) * (1.0 - 0.45 * lanes2);

    vec3 coreCol = vec3(1.0, 0.8, 0.58);
    vec3 diskCol = vec3(0.8, 0.86, 1.0);
    vec3 col = mix(diskCol, coreCol, clamp(core * 1.5 + bulge, 0.0, 1.0)) * band * 0.05 * uMilkyWay;

    // Unresolved background starlight.
    col += vec3(0.8, 0.85, 1.0) * 0.004 * uMilkyWay;

    // Nebulae: H-alpha (red/pink) and reflection (blue) clouds, concentrated along the galactic plane.
    float neb = smoothstep(0.55, 0.9, fbm3(gal * 4.0 + 1.7)) * exp(-(b * b) / (2.0 * 0.09 * 0.09));
    float neb2 = smoothstep(0.6, 0.95, fbm3(gal * 6.5 + 4.1)) * exp(-(b * b) / (2.0 * 0.14 * 0.14));
    col += (vec3(1.0, 0.22, 0.32) * neb * 0.03 + vec3(0.35, 0.5, 1.0) * neb2 * 0.012) * uNebula * (0.2 + mw);

    // A few real deep-sky objects at their catalogue positions.
    col += deepSkyObject(eq, 83.8, -5.4, 1.2, vec3(1.0, 0.35, 0.5) * 0.12, 1.0) * uNebula;   // Orion Nebula
    col += deepSkyObject(eq, 270.9, -24.4, 0.9, vec3(1.0, 0.3, 0.45) * 0.08, 2.0) * uNebula;  // Lagoon Nebula
    col += deepSkyObject(eq, 161.3, -59.9, 2.0, vec3(1.0, 0.35, 0.4) * 0.1, 3.0) * uNebula;  // Carina Nebula
    col += deepSkyObject(eq, 98.0, 4.9, 1.1, vec3(1.0, 0.25, 0.35) * 0.05, 4.0) * uNebula;   // Rosette Nebula
    col += deepSkyObject(eq, 314.7, 44.5, 1.6, vec3(1.0, 0.3, 0.35) * 0.05, 5.0) * uNebula;  // North America Nebula
    col += deepSkyObject(eq, 56.9, 24.1, 1.0, vec3(0.45, 0.6, 1.0) * 0.06, 6.0) * uNebula;   // Pleiades reflection nebula
    col += deepSkyObject(eq, 10.7, 41.3, 1.0, vec3(0.95, 0.9, 0.85) * 0.06, 7.0);            // Andromeda Galaxy
    col += deepSkyObject(eq, 80.9, -69.8, 4.0, vec3(0.85, 0.85, 1.0) * 0.08, 8.0);           // Large Magellanic Cloud
    col += deepSkyObject(eq, 13.2, -72.8, 2.0, vec3(0.85, 0.85, 1.0) * 0.05, 9.0);           // Small Magellanic Cloud

    return col * uNightScale;
}

vec3 sunDisc(vec3 rd) {
    float cosA = dot(rd, uSunDir);
    float cosR = cos(uSunRadius);
    if (cosA < cosR) return vec3(0.0);
    float x = clamp(acos(clamp(cosA, -1.0, 1.0)) / uSunRadius, 0.0, 1.0);
    float mu = sqrt(1.0 - x * x);
    vec3 limb = pow(vec3(mu), vec3(0.42, 0.55, 0.70));   // limb darkening, redder at the edge
    float edge = smoothstep(1.0, 0.9, x);
    return vec3(1.0, 0.97, 0.92) * uSunI * 60.0 * limb * edge;
}

vec3 moonDisc(vec3 rd) {
    float cosA = dot(rd, uMoonDir);
    float cosR = cos(uMoonRadius);
    if (cosA < cosR) return vec3(0.0);
    vec3 u = normalize(cross(uMoonDir, uPoleDir));
    vec3 v = cross(u, uMoonDir);
    float sr = sin(uMoonRadius);
    float x = dot(rd, u) / sr;
    float y = dot(rd, v) / sr;
    float r2 = x * x + y * y;
    if (r2 >= 1.0) return vec3(0.0);
    float z = sqrt(1.0 - r2);
    vec3 n = u * x + v * y - uMoonDir * z;
    float lit = max(dot(n, uMoonLightDir), 0.0);
    // Lunar maria: darker basalt plains in a fixed, recognisable pattern.
    vec3 mp = vec3(x, y, z);
    float maria = smoothstep(0.45, 0.7, texture(uNoise, mp * 0.35 + vec3(0.21, 0.63, 0.12)).r);
    float craters = texture(uNoise, mp * 1.7 + 0.5).g;
    float albedo = mix(0.75, 0.42, maria) * (0.85 + 0.3 * craters);
    float edge = smoothstep(1.0, 0.94, r2);
    vec3 earthshine = vec3(0.002);
    return vec3(1.0, 0.96, 0.9) * (albedo * lit * 0.5 + earthshine) * edge;
}

// ---------------------------------------------------------------- cirrus
vec4 cirrus(vec3 rd) {
    if (uCirrus <= 0.0 || rd.y <= 0.02) return vec4(0.0);
    vec3 ro = vec3(0.0, RG, 0.0);
    float t = raySphere(ro, rd, RG + 8.5).y;
    vec2 xz = (rd.xz * t + uCloudOffset.xz * 1.6);
    // Long wind-combed streaks: stretch the noise along the wind direction.
    vec2 uv = vec2(xz.x / 26.0, xz.y / 14.0);
    float n = texture(uNoise, vec3(uv, 0.13)).g * 0.55 + texture(uNoise, vec3(uv * 2.7, 0.57)).b * 0.3
            + texture(uNoise, vec3(uv * 7.3, 0.29)).a * 0.15;
    float patchy = n01(texture(uNoise, vec3(xz / 90.0, 0.91)).r);
    float d = smoothstep(0.52, 0.8, n) * smoothstep(0.35, 0.8, patchy) * uCirrus;
    // Fade out toward the horizon where the layer would alias into streaks.
    d *= smoothstep(0.06, 0.3, rd.y) * (1.0 - uRain);
    float mu = dot(rd, uSunDir);
    vec3 col = uSunColor * (0.05 + hgPhase(mu, 0.6) * 0.6) + uAmbient * 0.9;
    return vec4(col, d * 0.55);
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

vec4 sampleClouds(vec2 uv) {
    // 4-tap tent filter hides the low-res jitter.
    vec2 o = uCloudTexel * 0.5;
    return 0.25 * (texture(uClouds, uv + vec2(o.x, o.y)) + texture(uClouds, uv + vec2(-o.x, o.y))
                 + texture(uClouds, uv + vec2(o.x, -o.y)) + texture(uClouds, uv + vec2(-o.x, -o.y)));
}

void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    vec4 pn = uInvViewProj * vec4(ndc, -1.0, 1.0);
    vec4 pf = uInvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 rd = normalize(pf.xyz / pf.w - pn.xyz / pn.w);

    bool below = rd.y < 0.0;
    vec3 sd = rd;
    float fresnel = 1.0;
    if (below) {
        if (uReflect == 1) {
            sd = vec3(rd.x, -rd.y, rd.z);
            float c = 1.0 - (-rd.y);
            fresnel = 0.02 + 0.98 * c * c * c * c * c;
        } else {
            sd = normalize(vec3(rd.x, 0.0005, rd.z));
        }
    }

    vec4 lut = textureLod(uSkyLut, skyLutUv(sd), 0.0);
    vec3 sky = lut.rgb;
    float transAvg = lut.a;
    vec3 col = sky;

    // Space is invisible in daylight: skip it entirely when it cannot show.
    if (uExposure * uNightScale > 0.002) col += spaceRadiance(sd) * transAvg;
    col += sunDisc(sd) * uSunTrans + moonDisc(sd) * uMoonTrans;

    vec4 ci = cirrus(sd);
    col = mix(col, ci.rgb, ci.a);
    float starVis = transAvg * (1.0 - uRain) * (1.0 - ci.a);

    if (uCloudsOn == 1 && !(below && uReflect == 0)) {
        vec4 cl = sampleClouds(vUv);
        col = col * cl.a + cl.rgb;
        starVis *= cl.a;
    }

    col = mix(col, vec3(luma(col)) * 0.6, uRain * 0.8);

    if (below) {
        if (uReflect == 1) {
            vec3 water = uAmbient * vec3(0.02, 0.05, 0.07);
            col = col * fresnel + water * (1.0 - fresnel);
            starVis *= fresnel;
        } else {
            col *= mix(1.0, 0.25, smoothstep(0.0, 0.2, -rd.y));
            starVis = 0.0;
        }
    }

    vec3 ldr = pow(aces(col * uExposure), vec3(1.0 / 2.2));
    starVis *= 1.0 - smoothstep(0.03, 0.3, luma(ldr));
    fragColor = vec4(ldr, clamp(starVis, 0.0, 1.0));
}
