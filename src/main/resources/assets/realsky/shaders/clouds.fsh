#version 330 core
// Pass 2: volumetric clouds at reduced resolution. Output: rgb = in-scattered light, a = transmittance.
#include "common.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform mat4 uInvViewProj;
uniform sampler2D uSkyLut;
uniform vec3 uSunDir;
uniform vec3 uMoonDir;
uniform vec3 uSunColor;
uniform vec3 uMoonColor;
uniform vec3 uAmbient;
uniform float uCamAlt;
uniform int uSteps;
uniform int uReflect;

float lightMarch(vec3 p, vec3 L) {
    float od = 0.0, hf, t = 0.0, stepLen = 0.08;
    for (int i = 0; i < 4; i++) {
        t += stepLen;
        od += cloudDensity(p + L * t, false, hf) * stepLen;
        stepLen *= 2.0;
    }
    return od * CLOUD_SIGMA;
}

void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    vec4 pn = uInvViewProj * vec4(ndc, -1.0, 1.0);
    vec4 pf = uInvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 rd = normalize(pf.xyz / pf.w - pn.xyz / pn.w);
    if (rd.y < 0.0) {
        if (uReflect == 0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
        rd.y = -rd.y;
    }

    vec3 ro = vec3(0.0, RG + uCamAlt, 0.0);
    float t0 = raySphere(ro, rd, RG + CLOUD_BOTTOM).y;
    float t1 = raySphere(ro, rd, RG + CLOUD_TOP).y;
    const float FAR = 140.0;
    if (t0 <= 0.0 || t0 > FAR) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    t1 = min(t1, t0 + 24.0);

    bool sunLit = uSunDir.y > -0.1;
    vec3 L = sunLit ? uSunDir : uMoonDir;
    vec3 Lc = sunLit ? uSunColor : uMoonColor;
    float mu = dot(rd, L);
    float phase = mix(hgPhase(mu, 0.75), hgPhase(mu, -0.2), 0.35) + 0.05 * hgPhase(mu, 0.99);

    // Adaptive march: big steps through empty air, small steps inside clouds.
    float len = t1 - t0;
    float fine = len / float(uSteps);
    float coarse = fine * 3.0;
    float t = t0 + fine * ign(gl_FragCoord.xy);
    vec3 S = vec3(0.0);
    float T = 1.0;
    float depthSum = 0.0, depthW = 0.0;
    int empty = 0;
    for (int i = 0; i < 256; i++) {
        if (t >= t1 || i >= uSteps * 2) break;
        vec3 p = ro + rd * t;
        float hf;
        float coarseD = cloudDensity(p, false, hf);
        if (coarseD <= 0.0) {
            empty++;
            t += empty > 2 ? coarse : fine;
            continue;
        }
        empty = 0;
        float d = cloudDensity(p, true, hf);
        if (d > 0.003) {
            float od = lightMarch(p, L);
            float beer = max(exp(-od), 0.3 * exp(-od * 0.25));
            float powder = 1.0 - 0.7 * exp(-d * CLOUD_SIGMA * 0.08);
            vec3 direct = Lc * beer * powder * phase * PI;
            vec3 ambient = uAmbient * mix(0.35, 1.1, hf);
            float st = exp(-d * CLOUD_SIGMA * fine);
            S += T * (direct + ambient) * (1.0 - st);
            depthSum += t * T * (1.0 - st);
            depthW += T * (1.0 - st);
            T *= st;
            if (T < 0.02) break;
        }
        t += fine;
    }

    // Aerial perspective: distant clouds fade into the sky behind them.
    vec3 skyCol = textureLod(uSkyLut, skyLutUv(rd), 0.0).rgb;
    float dist = depthW > 0.0 ? depthSum / depthW : t0;
    float haze = 1.0 - exp(-dist / 50.0);
    S = mix(S, skyCol * (1.0 - T), haze);
    float fade = smoothstep(FAR, FAR * 0.65, t0);
    fragColor = vec4(S * fade, mix(1.0, T, fade));
}
