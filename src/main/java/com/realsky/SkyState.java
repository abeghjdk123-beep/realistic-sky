package com.realsky;

import org.joml.Matrix3f;

/**
 * Everything the sky shader needs for one frame: celestial geometry, light colours and exposure.
 * World axes follow Minecraft: +X east, +Y up, +Z south.
 */
public class SkyState {
    public static final float SUN_I = 20f;
    /** Exposure at full night; space objects are authored relative to this. */
    public static final float NIGHT_EXPOSURE = 400f;
    private static final float MOON_LIGHT = 6e-5f;

    public final float[] sunDir = new float[3];
    public final float[] moonDir = new float[3];
    public final float[] moonLightDir = new float[3];
    public final float[] poleDir = new float[3];
    public final float[] sunColor = new float[3];
    public final float[] moonColor = new float[3];
    public final float[] ambient = new float[3];
    public final float[] fogColor = new float[3];
    public final float[] cloudOffset = new float[3];
    /** Equatorial (J2000) -> world. Its transpose maps world -> equatorial. */
    public final Matrix3f eqToWorld = new Matrix3f();
    public float moonI;
    public float exposure = 1f;
    public float camAltKm;
    public float rain;
    public float timeSeconds;
    public boolean valid;

    private long lastNanos;
    private boolean exposureInit;

    /**
     * @param sunAngleDeg vanilla sun angle in degrees, 0 = noon
     * @param timeOfDay   world time of day in ticks (for moon phase and star drift)
     */
    public void update(RealSkyConfig cfg, float sunAngleDeg, long timeOfDay, double camX, double camY, double camZ,
                       float yawDeg, float rainGradient, float thunderGradient, double gameSeconds) {
        double phi = Math.toRadians(cfg.latitude);
        double a = Math.toRadians(sunAngleDeg);
        double day = timeOfDay / 24000.0;
        double dec = 0;
        if (cfg.seasons) dec = Math.toRadians(23.44) * Math.sin(2 * Math.PI * day / Math.max(1f, cfg.seasonLengthDays));

        float cp = (float) Math.cos(phi), sp = (float) Math.sin(phi);
        // L maps the rotating equatorial frame (x toward the meridian, z = celestial pole) into world space.
        hourVec(a, dec, cp, sp, sunDir);
        hourVec(a + Math.PI, -dec, cp, sp, moonDir);
        poleDir[0] = 0; poleDir[1] = sp; poleDir[2] = -cp;

        double lst = a + Math.toRadians(90) + 2 * Math.PI * (day / 96.0);
        float c = (float) Math.cos(lst), s = (float) Math.sin(lst);
        // eqToWorld = L * Rz(-lst). JOML Matrix3f.set takes column-major arguments.
        Matrix3f Lm = new Matrix3f(0, cp, sp, 1, 0, 0, 0, sp, -cp);
        Matrix3f Rz = new Matrix3f(c, -s, 0, s, c, 0, 0, 0, 1);
        Lm.mul(Rz, eqToWorld);

        int phase = (int) Math.floorMod(Math.floorDiv(timeOfDay, 24000L), 8L);
        double psi = Math.toRadians(phase * 45.0);
        float[] tang = cross(poleDir, moonDir);
        normalize(tang);
        for (int i = 0; i < 3; i++) moonLightDir[i] = (float) (Math.cos(psi) * -moonDir[i] + Math.sin(psi) * tang[i]);
        float illum = (float) ((1 + Math.cos(psi)) / 2);
        moonI = SUN_I * MOON_LIGHT * illum;

        rain = Math.max(rainGradient, thunderGradient);
        camAltKm = (float) Math.max(0.0, (camY - 63.0) * 0.001);

        float[] ro = {0, Atmosphere.RG + camAltKm, 0};
        float[] zen = sky(ro, new float[]{0, 1, 0});
        float[] hor = new float[3];
        for (int az = 0; az < 360; az += 90) {
            float[] d = {(float) Math.cos(Math.toRadians(az)), 0.1f, (float) Math.sin(Math.toRadians(az))};
            normalize(d);
            float[] r = sky(ro, d);
            for (int k = 0; k < 3; k++) hor[k] += r[k] * 0.25f;
        }
        for (int k = 0; k < 3; k++) ambient[k] = zen[k] * 0.6f + hor[k] * 0.6f;

        float[] cloudPt = {0, Atmosphere.RG + 2.0f, 0};
        float[] st = Atmosphere.lightTransmittance(cloudPt, sunDir);
        float[] mt = Atmosphere.lightTransmittance(cloudPt, moonDir);
        for (int k = 0; k < 3; k++) {
            sunColor[k] = st[k] * SUN_I;
            moonColor[k] = mt[k] * moonI;
        }

        // Auto exposure, smoothed over time.
        float lum = luma(zen) * 0.5f + luma(hor) * 0.5f;
        lum *= 1f - rain * 0.4f;
        float maxE = NIGHT_EXPOSURE * Math.max(0.05f, cfg.nightBrightness);
        float target = Math.min(Math.max(0.22f / Math.max(lum, 1e-9f), 0.2f), maxE) * Math.max(0.05f, cfg.exposure);
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1f : Math.min(1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        if (!exposureInit) {
            exposure = target;
            exposureInit = true;
        } else {
            float k = 1f - (float) Math.exp(-dt * 2.5f);
            exposure = (float) Math.exp(Math.log(exposure) + (Math.log(target) - Math.log(exposure)) * k);
        }

        // Fog colour = what the sky looks like just above the horizon in the view direction.
        double yaw = Math.toRadians(yawDeg);
        float[] fd = {(float) -Math.sin(yaw), 0.04f, (float) Math.cos(yaw)};
        normalize(fd);
        float[] fc = sky(ro, fd);
        float l = luma(fc);
        float cloudDim = cfg.clouds ? 1f - 0.25f * cfg.cloudCoverage : 1f;
        for (int k = 0; k < 3; k++) {
            float v = fc[k] * cloudDim;
            v = v + (l * 0.6f - v) * rain * 0.8f;
            fogColor[k] = (float) Math.pow(Atmosphere.aces(v * exposure), 1.0 / 2.2);
        }

        // Clouds drift with the wind and stay fixed in the world as the camera moves (1 block = 1 m).
        double wind = 0.012 * cfg.cloudWindSpeed;
        double wrap = 5040.0;
        cloudOffset[0] = (float) (((camX / 1000.0) + wind * gameSeconds) % wrap);
        cloudOffset[1] = 0f;
        cloudOffset[2] = (float) (((camZ / 1000.0) + wind * 0.35 * gameSeconds) % wrap);
        timeSeconds = (float) (gameSeconds % 3600.0);
        valid = true;
    }

    private float[] sky(float[] ro, float[] rd) {
        float[] col = new float[3];
        if (sunDir[1] > -0.3f) {
            float[] s = Atmosphere.scatter(ro, rd, sunDir);
            for (int k = 0; k < 3; k++) col[k] += s[k] * SUN_I;
        }
        if (moonI > 0 && moonDir[1] > -0.2f) {
            float[] s = Atmosphere.scatter(ro, rd, moonDir);
            for (int k = 0; k < 3; k++) col[k] += s[k] * moonI;
        }
        return col;
    }

    private static void hourVec(double H, double d, float cp, float sp, float[] out) {
        float p = (float) (Math.cos(d) * Math.cos(H));
        float q = (float) (-Math.cos(d) * Math.sin(H));
        float r = (float) Math.sin(d);
        out[0] = q;
        out[1] = p * cp + r * sp;
        out[2] = p * sp - r * cp;
    }

    private static float luma(float[] c) {
        return c[0] * 0.2126f + c[1] * 0.7152f + c[2] * 0.0722f;
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static void normalize(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l > 1e-6f) {
            v[0] /= l; v[1] /= l; v[2] /= l;
        }
    }
}
