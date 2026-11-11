package com.realsky;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * Generates a tileable 3D RGBA noise volume for the volumetric clouds (generated once at startup, off-thread).
 * R = Perlin-Worley (cloud base shape), G/B/A = Worley fBm at increasing frequencies (erosion detail).
 */
public final class CloudNoise {
    public static final int SIZE = 128;
    private static volatile CompletableFuture<byte[]> future;

    private CloudNoise() {}

    public static void generateAsync() {
        if (future == null) future = CompletableFuture.supplyAsync(CloudNoise::generate);
    }

    /** Returns the voxel data once ready, otherwise null. */
    public static byte[] poll() {
        CompletableFuture<byte[]> f = future;
        if (f == null || !f.isDone()) return null;
        try {
            return f.join();
        } catch (Throwable t) {
            RealSky.LOGGER.error("Cloud noise generation failed", t);
            future = null;
            return null;
        }
    }

    private static byte[] generate() {
        long start = System.currentTimeMillis();
        final int n = SIZE;
        byte[] out = new byte[n * n * n * 4];
        IntStream.range(0, n).parallel().forEach(z -> {
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    float fx = (x + 0.5f) / n, fy = (y + 0.5f) / n, fz = (z + 0.5f) / n;
                    float perlin = 0, amp = 1, norm = 0;
                    for (int o = 0; o < 4; o++) {
                        int period = 4 << o;
                        perlin += amp * perlin(fx * period, fy * period, fz * period, period);
                        norm += amp;
                        amp *= 0.5f;
                    }
                    perlin = clamp01(perlin / norm * 0.5f + 0.5f);
                    float w4 = worleyFbm(fx, fy, fz, 4);
                    float perlinWorley = remap(perlin, 0f, 1f, w4, 1f);
                    float g = worleyFbm(fx, fy, fz, 6);
                    float b = worleyFbm(fx, fy, fz, 12);
                    float a = worleyFbm(fx, fy, fz, 24);
                    int i = ((z * n + y) * n + x) * 4;
                    out[i] = toByte(perlinWorley);
                    out[i + 1] = toByte(g);
                    out[i + 2] = toByte(b);
                    out[i + 3] = toByte(a);
                }
            }
        });
        RealSky.LOGGER.info("Generated {}^3 cloud noise in {} ms", n, System.currentTimeMillis() - start);
        return out;
    }

    public static ByteBuffer toDirect(byte[] data, ByteBuffer buf) {
        buf.put(data).flip();
        return buf;
    }

    private static byte toByte(float v) {
        return (byte) Math.round(clamp01(v) * 255f);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static float remap(float v, float a, float b, float c, float d) {
        return c + (v - a) / (b - a) * (d - c);
    }

    private static float worleyFbm(float x, float y, float z, int cells) {
        return worley(x, y, z, cells) * 0.625f + worley(x, y, z, cells * 2) * 0.25f + worley(x, y, z, cells * 4) * 0.125f;
    }

    /** Inverted, tileable Worley (cellular) noise: 1 at feature points, falling to 0 between them. */
    private static float worley(float x, float y, float z, int cells) {
        float px = x * cells, py = y * cells, pz = z * cells;
        int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
        float best = 10f;
        for (int dz = -1; dz <= 1; dz++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    int ix = cx + dx, iy = cy + dy, iz = cz + dz;
                    int wx = Math.floorMod(ix, cells), wy = Math.floorMod(iy, cells), wz = Math.floorMod(iz, cells);
                    int h = hash(wx, wy, wz, cells);
                    float fx = ix + ((h & 0xFF) / 255f);
                    float fy = iy + (((h >>> 8) & 0xFF) / 255f);
                    float fz = iz + (((h >>> 16) & 0xFF) / 255f);
                    float ddx = fx - px, ddy = fy - py, ddz = fz - pz;
                    float d = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (d < best) best = d;
                }
        return clamp01(1f - (float) Math.sqrt(best));
    }

    /** Tileable 3D gradient noise in roughly [-1, 1]. */
    private static float perlin(float x, float y, float z, int period) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        float tx = x - x0, ty = y - y0, tz = z - z0;
        float u = fade(tx), v = fade(ty), w = fade(tz);
        float r = 0;
        float[] res = new float[8];
        int k = 0;
        for (int dz = 0; dz <= 1; dz++)
            for (int dy = 0; dy <= 1; dy++)
                for (int dx = 0; dx <= 1; dx++) {
                    int h = hash(Math.floorMod(x0 + dx, period), Math.floorMod(y0 + dy, period), Math.floorMod(z0 + dz, period), period * 7 + 3);
                    res[k++] = grad(h, tx - dx, ty - dy, tz - dz);
                }
        float x00 = lerp(res[0], res[1], u), x10 = lerp(res[2], res[3], u);
        float x01 = lerp(res[4], res[5], u), x11 = lerp(res[6], res[7], u);
        r = lerp(lerp(x00, x10, v), lerp(x01, x11, v), w);
        return r;
    }

    private static float grad(int h, float x, float y, float z) {
        switch (h & 15) {
            case 0: return x + y;
            case 1: return -x + y;
            case 2: return x - y;
            case 3: return -x - y;
            case 4: return x + z;
            case 5: return -x + z;
            case 6: return x - z;
            case 7: return -x - z;
            case 8: return y + z;
            case 9: return -y + z;
            case 10: return y - z;
            case 11: return -y - z;
            case 12: return x + y;
            case 13: return -x + y;
            case 14: return -y + z;
            default: return -y - z;
        }
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int hash(int x, int y, int z, int seed) {
        int h = seed * 0x27d4eb2d;
        h ^= x * 0x8da6b343;
        h = Integer.rotateLeft(h, 13) * 0x5bd1e995;
        h ^= y * 0xd8163841;
        h = Integer.rotateLeft(h, 15) * 0x5bd1e995;
        h ^= z * 0xcb1ab31f;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        h *= 0x297a2d39;
        h ^= h >>> 15;
        return h;
    }
}
