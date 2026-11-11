package com.realsky;

/**
 * CPU copy of the atmosphere model in sky.fsh, used for a handful of per-frame values
 * (exposure, cloud lighting, fog colour). Keep the constants in sync with the shader.
 */
public final class Atmosphere {
    public static final float RG = 6360f, RT = 6460f;
    private static final float[] BETA_R = {5.802e-3f, 13.558e-3f, 33.1e-3f};
    private static final float H_R = 8f;
    private static final float BETA_M = 3.996e-3f, BETA_M_EXT = 4.40e-3f, H_M = 1.2f;
    private static final float[] BETA_O = {0.650e-3f, 1.881e-3f, 0.085e-3f};

    private Atmosphere() {}

    /** Ray-sphere intersection for a sphere centred at the origin; returns {near, far} or {-1, -1}. */
    static float[] raySphere(float[] ro, float[] rd, float r) {
        float b = dot(ro, rd);
        float c = dot(ro, ro) - r * r;
        float d = b * b - c;
        if (d < 0) return new float[]{-1, -1};
        d = (float) Math.sqrt(d);
        return new float[]{-b - d, -b + d};
    }

    private static void extinction(float h, float[] out) {
        float dr = (float) Math.exp(-h / H_R);
        float dm = (float) Math.exp(-h / H_M);
        float dO = Math.max(0f, 1f - Math.abs(h - 25f) / 15f);
        for (int i = 0; i < 3; i++) out[i] = BETA_R[i] * dr + BETA_M_EXT * dm + BETA_O[i] * dO;
    }

    /** Transmittance from point p (km, planet-centred) toward direction L, to the top of the atmosphere. */
    public static float[] lightTransmittance(float[] p, float[] L) {
        if (raySphere(p, L, RG)[0] > 0) return new float[]{0, 0, 0};
        float t = raySphere(p, L, RT)[1];
        float dt = t / 6f;
        float[] od = new float[3], e = new float[3], q = new float[3];
        for (int i = 0; i < 6; i++) {
            float s = (i + 0.5f) * dt;
            q[0] = p[0] + L[0] * s; q[1] = p[1] + L[1] * s; q[2] = p[2] + L[2] * s;
            extinction(len(q) - RG, e);
            for (int k = 0; k < 3; k++) od[k] += e[k] * dt;
        }
        return new float[]{(float) Math.exp(-od[0]), (float) Math.exp(-od[1]), (float) Math.exp(-od[2])};
    }

    private static float miePhase(float mu) {
        float g = 0.76f, g2 = g * g;
        return (float) (3.0 / (8.0 * Math.PI) * ((1 - g2) * (1 + mu * mu)) / ((2 + g2) * Math.pow(1 + g2 - 2 * g * mu, 1.5)));
    }

    /** In-scattered radiance along rd for a unit-intensity light from direction L. */
    public static float[] scatter(float[] ro, float[] rd, float[] L) {
        float tmax = raySphere(ro, rd, RT)[1];
        float[] g = raySphere(ro, rd, RG);
        if (g[0] > 0) tmax = g[0];
        final int n = 16;
        float mu = dot(rd, L);
        float pR = (float) (3.0 / (16.0 * Math.PI) * (1 + mu * mu));
        float pM = miePhase(mu);
        float ms = 0.012f * smoothstep(-0.25f, 0.1f, L[1]);
        float[] T = {1, 1, 1}, sum = new float[3], p = new float[3];
        float prev = 0;
        for (int i = 0; i < n; i++) {
            float f = (i + 1f) / n;
            float t = tmax * f * f;
            float dt = t - prev;
            float tm = prev + dt * 0.5f;
            prev = t;
            p[0] = ro[0] + rd[0] * tm; p[1] = ro[1] + rd[1] * tm; p[2] = ro[2] + rd[2] * tm;
            float h = len(p) - RG;
            float dr = (float) Math.exp(-h / H_R);
            float dm = (float) Math.exp(-h / H_M);
            float dO = Math.max(0f, 1f - Math.abs(h - 25f) / 15f);
            float[] sT = lightTransmittance(p, L);
            for (int k = 0; k < 3; k++) {
                float ext = BETA_R[k] * dr + BETA_M_EXT * dm + BETA_O[k] * dO;
                float sR = BETA_R[k] * dr, sM = BETA_M * dm;
                float s = (sR * pR + sM * pM) * sT[k] + (sR + sM) * (0.08f * sT[k] + ms) / (float) (4 * Math.PI);
                float st = (float) Math.exp(-ext * dt);
                sum[k] += T[k] * s * (1 - st) / Math.max(ext, 1e-7f);
                T[k] *= st;
            }
        }
        return sum;
    }

    static float smoothstep(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float len(float[] a) {
        return (float) Math.sqrt(dot(a, a));
    }

    public static float aces(float x) {
        float v = (x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f);
        return Math.max(0f, Math.min(1f, v));
    }
}
