#version 330 core
// Realistic Sky: physically based atmosphere, volumetric clouds, sun, moon, Milky Way and nebulae.
// Units: kilometres. World axes follow Minecraft: +X east, +Y up, +Z south.

in vec2 vUv;
out vec4 fragColor;

uniform mat4 uInvViewProj;
uniform mat3 uWorldToEq;      // world direction -> equatorial (J2000)
uniform vec3 uSunDir;
uniform vec3 uMoonDir;
uniform vec3 uMoonLightDir;   // direction of sunlight hitting the moon (sets the phase)
uniform vec3 uPoleDir;
uniform vec3 uSunColor;       // sunlight reaching the cloud layer
uniform vec3 uMoonColor;      // moonlight reaching the cloud layer
uniform vec3 uAmbient;        // average sky radiance, for cloud ambient light
uniform vec3 uCloudOffset;    // camera position + wind, km (wrapped)
uniform float uSunI;
uniform float uMoonI;
uniform float uExposure;
uniform float uNightScale;
uniform float uCamAlt;
uniform float uRain;
uniform float uCoverage;
uniform float uSunRadius;
uniform float uMoonRadius;
uniform float uMilkyWay;
uniform float uNebula;
uniform float uTime;
uniform int uSteps;
uniform int uCloudsOn;
uniform int uReflect;

uniform sampler2D uMilkyWayTex;   // galactic equirect brightness map
uniform sampler3D uNoise;         // tileable cloud noise

const float PI = 3.14159265359;
const float RG = 6360.0;
const float RT = 6460.0;
const vec3 BETA_R = vec3(5.802e-3, 13.558e-3, 33.1e-3);
const float H_R = 8.0;
const float BETA_M = 3.996e-3;
const float BETA_M_EXT = 4.40e-3;
const float H_M = 1.2;
const vec3 BETA_O = vec3(0.650e-3, 1.881e-3, 0.085e-3);
const float CLOUD_BOTTOM = 1.5;
const float CLOUD_TOP = 3.4;
const float CLOUD_SIGMA = 38.0;

const mat3 EQ2GAL = mat3(
    -0.0548755604, 0.4941094279, -0.8676661490,
    -0.8734370902, -0.4448296300, -0.1980763734,
    -0.4838350155, 0.7469822445, 0.4559837762);

// ---------------------------------------------------------------- helpers
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

float miePhase(float mu) {
    // Cornette-Shanks
    const float g = 0.76;
    float g2 = g * g;
    return 3.0 / (8.0 * PI) * ((1.0 - g2) * (1.0 + mu * mu)) / ((2.0 + g2) * pow(1.0 + g2 - 2.0 * g * mu, 1.5));
}

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// ---------------------------------------------------------------- atmosphere
vec3 densities(float h) {
    return vec3(exp(-h / H_R), exp(-h / H_M), max(0.0, 1.0 - abs(h - 25.0) / 15.0));
}

vec3 extinctionAt(vec3 d) {
    return BETA_R * d.x + vec3(BETA_M_EXT * d.y) + BETA_O * d.z;
}

vec3 lightTransmittance(vec3 p, vec3 L) {
    vec2 g = raySphere(p, L, RG);
    if (g.x > 0.0) return vec3(0.0);
    float t = raySphere(p, L, RT).y;
    float dt = t / 6.0;
    vec3 od = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        vec3 q = p + L * ((float(i) + 0.5) * dt);
        od += extinctionAt(densities(length(q) - RG)) * dt;
    }
    return exp(-od);
}

// Single scattering with a cheap multiple-scattering boost. Returns in-scattered radiance per unit light.
vec3 scatter(vec3 ro, vec3 rd, vec3 L, out vec3 trans) {
    float tmax = raySphere(ro, rd, RT).y;
    vec2 g = raySphere(ro, rd, RG);
    if (g.x > 0.0) tmax = g.x;
    const int N = 16;
    float mu = dot(rd, L);
    float pR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float pM = miePhase(mu);
    vec3 T = vec3(1.0);
    vec3 sum = vec3(0.0);
    float prev = 0.0;
    for (int i = 0; i < N; i++) {
        float f = (float(i) + 1.0) / float(N);
        float t = tmax * f * f;
        float dt = t - prev;
        float tm = prev + dt * 0.5;
        prev = t;
        vec3 p = ro + rd * tm;
        vec3 d = densities(length(p) - RG);
        vec3 ext = extinctionAt(d);
        vec3 sT = lightTransmittance(p, L);
        vec3 scatR = BETA_R * d.x;
        float scatM = BETA_M * d.y;
        vec3 s = (scatR * pR + scatM * pM) * sT
               + (scatR + scatM) * (0.08 * sT + 0.012 * smoothstep(-0.25, 0.1, L.y)) / (4.0 * PI);
        vec3 stepT = exp(-ext * dt);
        sum += T * s * (1.0 - stepT) / max(ext, vec3(1e-7));
        T *= stepT;
    }
    trans = T;
    return sum;
}

vec3 skyRadiance(vec3 ro, vec3 rd, out vec3 trans) {
    vec3 col = vec3(0.0);
    trans = vec3(1.0);
    vec3 t;
    if (uSunDir.y > -0.3) {
        col += scatter(ro, rd, uSunDir, t) * uSunI;
        trans = t;
    }
    if (uMoonI > 0.0 && uMoonDir.y > -0.2) {
        col += scatter(ro, rd, uMoonDir, t) * uMoonI;
        trans = t;
    }
    if (uSunDir.y <= -0.3 && !(uMoonI > 0.0 && uMoonDir.y > -0.2)) {
        // Only transmittance needed.
        float tmax = raySphere(ro, rd, RT).y;
        vec3 od = vec3(0.0);
        float dt = tmax / 8.0;
        for (int i = 0; i < 8; i++) {
            vec3 p = ro + rd * ((float(i) + 0.5) * dt);
            od += extinctionAt(densities(length(p) - RG)) * dt;
        }
        trans = exp(-od);
    }
    // Airglow: a faint greenish night-sky glow, strongest near the horizon.
    float horizon = exp(-max(rd.y, 0.0) * 6.0);
    col += vec3(0.30, 0.42, 0.33) * (0.3 + 0.7 * horizon) * uNightScale * 0.012;
    return col;
}

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

// ---------------------------------------------------------------- clouds
float cloudDensity(vec3 p, out float hf) {
    float h = length(p) - RG;
    hf = (h - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM);
    if (hf <= 0.0 || hf >= 1.0) return 0.0;
    vec3 q = vec3(p.x, h, p.z) + uCloudOffset;

    float weather = texture(uNoise, vec3(q.xz / 72.0, 0.37)).r;
    float cov = clamp(uCoverage + (weather - 0.55) * 0.7, 0.0, 1.0);
    cov = mix(cov, 0.97, uRain);

    // Perlin-Worley base shape; the raw channel sits around 0.4..1, so stretch it to 0..1 first.
    float base = clamp((texture(uNoise, q / vec3(7.0, 5.0, 7.0)).r - 0.45) / 0.5, 0.0, 1.0);
    base = mix(base, base * 0.6 + 0.4 * texture(uNoise, q / 24.0).r, 0.35);
    // Cumulus profile: flat bottoms, rounded towers.
    float hg = smoothstep(0.0, 0.08, hf) * smoothstep(1.0, mix(0.45, 0.2, cov), hf);
    base = remap(base * hg, 1.0 - cov, 1.0, 0.0, 1.0);
    if (base <= 0.0) return 0.0;

    // Erode the edges with higher-frequency Worley detail (wispy at the bottom, billowy at the top).
    vec4 dn = texture(uNoise, q / 1.3);
    float detail = dn.g * 0.625 + dn.b * 0.25 + dn.a * 0.125;
    detail = mix(1.0 - detail, detail, clamp(hf * 4.0, 0.0, 1.0));
    base = remap(base, detail * 0.45, 1.0, 0.0, 1.0);
    return clamp(base, 0.0, 1.0) * mix(1.0, 1.6, uRain);
}

float lightMarch(vec3 p, vec3 L) {
    float od = 0.0;
    float hf;
    float t = 0.0;
    float stepLen = 0.06;
    for (int i = 0; i < 6; i++) {
        t += stepLen;
        od += cloudDensity(p + L * t, hf) * stepLen;
        stepLen *= 1.8;
    }
    return od * CLOUD_SIGMA;
}

// Returns (in-scattered radiance, transmittance).
vec4 clouds(vec3 ro, vec3 rd, vec3 skyCol) {
    if (rd.y < 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
    float t0 = raySphere(ro, rd, RG + CLOUD_BOTTOM).y;
    float t1 = raySphere(ro, rd, RG + CLOUD_TOP).y;
    if (t0 <= 0.0 || t0 > 180.0) return vec4(0.0, 0.0, 0.0, 1.0);
    t1 = min(t1, t0 + 30.0);

    bool sunLit = uSunDir.y > -0.1;
    vec3 L = sunLit ? uSunDir : uMoonDir;
    vec3 Lc = sunLit ? uSunColor : uMoonColor;
    float mu = dot(rd, L);
    float phase = mix(hgPhase(mu, 0.75), hgPhase(mu, -0.2), 0.35) + 0.05 * hgPhase(mu, 0.99);

    int steps = uSteps;
    float dt = (t1 - t0) / float(steps);
    float t = t0 + dt * ign(gl_FragCoord.xy);
    vec3 S = vec3(0.0);
    float T = 1.0;
    float depthSum = 0.0, depthW = 0.0;
    for (int i = 0; i < 192; i++) {
        if (i >= steps) break;
        vec3 p = ro + rd * t;
        float hf;
        float d = cloudDensity(p, hf);
        if (d > 0.002) {
            float od = lightMarch(p, L);
            float beer = max(exp(-od), 0.3 * exp(-od * 0.25));
            float powder = 1.0 - 0.7 * exp(-d * CLOUD_SIGMA * 0.08);
            vec3 direct = Lc * beer * powder * phase * 4.0 * PI * 0.25;
            vec3 ambient = uAmbient * mix(0.35, 1.1, hf);
            vec3 lum = direct + ambient;
            float sig = d * CLOUD_SIGMA;
            float st = exp(-sig * dt);
            S += T * lum * (1.0 - st);
            depthSum += t * T * (1.0 - st);
            depthW += T * (1.0 - st);
            T *= st;
            if (T < 0.01) break;
        }
        t += dt;
    }
    float dist = depthW > 0.0 ? depthSum / depthW : t0;
    float haze = 1.0 - exp(-dist / 55.0);
    S = mix(S, skyCol * (1.0 - T), haze);
    // Fade out near the far edge of the layer to hide the horizon cut-off.
    float fade = smoothstep(180.0, 120.0, t0);
    return vec4(S * fade, mix(1.0, T, fade));
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// ---------------------------------------------------------------- main
void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    vec4 pn = uInvViewProj * vec4(ndc, -1.0, 1.0);
    vec4 pf = uInvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 rd = normalize(pf.xyz / pf.w - pn.xyz / pn.w);

    vec3 ro = vec3(0.0, RG + uCamAlt, 0.0);
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

    vec3 trans;
    vec3 sky = skyRadiance(ro, sd, trans);
    float transAvg = dot(trans, vec3(0.3333));

    vec3 col = sky;
    col += (spaceRadiance(sd) + sunDisc(sd) + moonDisc(sd)) * trans;

    float cloudT = 1.0;
    if (uCloudsOn == 1) {
        vec4 cl = clouds(ro, sd, sky);
        col = col * cl.a + cl.rgb;
        cloudT = cl.a;
    }

    // Overcast: desaturate and darken.
    col = mix(col, vec3(luma(col)) * 0.6, uRain * 0.8);

    float starVis = cloudT * transAvg * (1.0 - uRain);
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
    // Stars fade out as the sky brightens.
    starVis *= 1.0 - smoothstep(0.03, 0.3, luma(ldr));
    fragColor = vec4(ldr, clamp(starVis, 0.0, 1.0));
}
