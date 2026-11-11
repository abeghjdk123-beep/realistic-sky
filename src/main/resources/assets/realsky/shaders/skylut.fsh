#version 330 core
// Pass 1: physically based atmosphere, evaluated into a small sky-view table (cheap to sample later).
#include "common.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform vec3 uSunDir;
uniform vec3 uMoonDir;
uniform float uSunI;
uniform float uMoonI;
uniform float uCamAlt;
uniform float uNightScale;

vec3 densities(float h) {
    return vec3(exp(-h / H_R), exp(-h / H_M), max(0.0, 1.0 - abs(h - 25.0) / 15.0));
}

vec3 extinctionAt(vec3 d) {
    return BETA_R * d.x + vec3(BETA_M_EXT * d.y) + BETA_O * d.z;
}

vec3 lightTransmittance(vec3 p, vec3 L) {
    if (raySphere(p, L, RG).x > 0.0) return vec3(0.0);
    float t = raySphere(p, L, RT).y;
    float dt = t / 6.0;
    vec3 od = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        vec3 q = p + L * ((float(i) + 0.5) * dt);
        od += extinctionAt(densities(length(q) - RG)) * dt;
    }
    return exp(-od);
}

float miePhase(float mu) {
    const float g = 0.76;
    float g2 = g * g;
    return 3.0 / (8.0 * PI) * ((1.0 - g2) * (1.0 + mu * mu)) / ((2.0 + g2) * pow(1.0 + g2 - 2.0 * g * mu, 1.5));
}

// Integrates in-scattering for two lights (sun, moon) in a single march.
void atmosphere(vec3 ro, vec3 rd, out vec3 radiance, out vec3 trans) {
    float tmax = raySphere(ro, rd, RT).y;
    vec2 g = raySphere(ro, rd, RG);
    if (g.x > 0.0) tmax = g.x;
    const int N = 20;
    bool sunOn = uSunDir.y > -0.3;
    bool moonOn = uMoonI > 0.0 && uMoonDir.y > -0.2;
    float muS = dot(rd, uSunDir), muM = dot(rd, uMoonDir);
    float pRS = 3.0 / (16.0 * PI) * (1.0 + muS * muS), pMS = miePhase(muS);
    float pRM = 3.0 / (16.0 * PI) * (1.0 + muM * muM), pMM = miePhase(muM);
    float msS = 0.012 * smoothstep(-0.25, 0.1, uSunDir.y);
    float msM = 0.012 * smoothstep(-0.25, 0.1, uMoonDir.y);
    vec3 T = vec3(1.0), sum = vec3(0.0);
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
        vec3 sR = BETA_R * d.x;
        float sM = BETA_M * d.y;
        vec3 s = vec3(0.0);
        if (sunOn) {
            vec3 lt = lightTransmittance(p, uSunDir);
            s += ((sR * pRS + sM * pMS) * lt + (sR + sM) * (0.08 * lt + msS) / (4.0 * PI)) * uSunI;
        }
        if (moonOn) {
            vec3 lt = lightTransmittance(p, uMoonDir);
            s += ((sR * pRM + sM * pMM) * lt + (sR + sM) * (0.08 * lt + msM) / (4.0 * PI)) * uMoonI;
        }
        vec3 stepT = exp(-ext * dt);
        sum += T * s * (1.0 - stepT) / max(ext, vec3(1e-7));
        T *= stepT;
    }
    radiance = sum;
    trans = T;
}

void main() {
    vec3 rd = skyLutDir(vUv);
    rd.y = max(rd.y, 0.0005);
    rd = normalize(rd);
    vec3 ro = vec3(0.0, RG + uCamAlt, 0.0);
    vec3 rad, trans;
    atmosphere(ro, rd, rad, trans);
    // Airglow: faint greenish night-sky glow, strongest near the horizon.
    float horizon = exp(-rd.y * 6.0);
    rad += vec3(0.30, 0.42, 0.33) * (0.3 + 0.7 * horizon) * uNightScale * 0.012;
    fragColor = vec4(rad, dot(trans, vec3(0.3333)));
}
