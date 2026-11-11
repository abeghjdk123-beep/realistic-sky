package com.realsky;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.lwjgl.opengl.GL33.*;

/** Draws the sky with raw OpenGL inside a frame-graph pass that replaces the vanilla sky pass. */
public class SkyRenderer {
    private final SkyState state = new SkyState();
    private final Matrix4f viewRot = new Matrix4f();
    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f viewProj = new Matrix4f();
    private final Matrix4f invViewProj = new Matrix4f();
    private final Matrix3f worldToEq = new Matrix3f();

    private int skyProgram, starProgram, alphaProgram;
    private int emptyVao, starVao, starVbo, starCount;
    private int milkyWayTex, noiseTex;
    private final Map<Integer, Map<String, Integer>> uniformCache = new HashMap<>();

    private boolean initialized;
    private boolean reloadRequested;
    private boolean failed;
    private String error;
    private boolean errorReported;
    private boolean active;

    public void requestReload() {
        reloadRequested = true;
        failed = false;
        errorReported = false;
        error = null;
    }

    public boolean isActive() {
        return active;
    }

    public String status() {
        if (failed) return "Error: " + error;
        if (!RealSky.config.enabled) return "Disabled (/realsky toggle)";
        if (RealSky.irisShadersActive()) return "Inactive: an Iris shader pack is in use";
        return (active ? "Active" : "Idle (not in the overworld)") + ", exposure " + String.format("%.2f", state.exposure)
            + ", clouds " + (noiseTex != 0 ? "ready" : "generating") + ", stars " + starCount;
    }

    /** Called at the start of WorldRenderer.render with the frame's camera and matrices. */
    public void beginFrame(ClientWorld world, Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix, float tickDelta) {
        RealSkyConfig cfg = RealSky.config;
        active = false;
        if (!cfg.enabled || failed || world == null || camera == null) return;
        if (world.getRegistryKey() != World.OVERWORLD) return;
        if (RealSky.irisShadersActive()) return;

        try {
            float sunAngle = camera.getEnvironmentAttributeInterpolator().get(EnvironmentAttributes.SUN_ANGLE_VISUAL, tickDelta);
            double gameSeconds = (world.getTime() + tickDelta) / 20.0;
            var pos = camera.getCameraPos();
            state.update(cfg, sunAngle, world.getTimeOfDay(), pos.x, pos.y, pos.z, camera.getYaw(),
                world.getRainGradient(tickDelta), world.getThunderGradient(tickDelta), gameSeconds);
        } catch (Throwable t) {
            fail("Failed to compute sky state", t);
            return;
        }

        viewRot.set(positionMatrix);
        viewRot.m30(0).m31(0).m32(0);
        proj.set(projectionMatrix);
        proj.mul(viewRot, viewProj);
        viewProj.invert(invViewProj);
        state.eqToWorld.transpose(worldToEq);
        active = true;
    }

    /** Overrides vanilla fog colour so distant terrain fades into the new horizon. */
    public void adjustFog(Camera camera, Vector4f color) {
        if (!active || !RealSky.config.matchFog || !state.valid || color == null) return;
        if (camera.getSubmersionType() != CameraSubmersionType.NONE) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && (mc.player.hasStatusEffect(StatusEffects.BLINDNESS) || mc.player.hasStatusEffect(StatusEffects.DARKNESS)))
            return;
        color.set(state.fogColor[0], state.fogColor[1], state.fogColor[2], color.w);
    }

    /** Runs inside our frame pass, on the render thread. */
    public void render() {
        if (!active) return;
        try {
            if (reloadRequested || !initialized) {
                destroy();
                init();
                reloadRequested = false;
            }
            uploadNoiseIfReady();
            draw();
        } catch (Throwable t) {
            fail("Sky rendering failed", t);
        }
    }

    private void fail(String what, Throwable t) {
        failed = true;
        active = false;
        error = t.getMessage() != null ? t.getMessage() : t.toString();
        RealSky.LOGGER.error(what, t);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!errorReported && mc.player != null) {
            errorReported = true;
            String msg = error.length() > 300 ? error.substring(0, 300) + "..." : error;
            mc.player.sendMessage(Text.literal("[Realistic Sky] " + what + ", using vanilla sky. " + msg
                + " (see logs/latest.log, then /realsky reload)"), false);
        }
    }

    // ------------------------------------------------------------------ drawing

    private void draw() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer fb = mc.getFramebuffer();
        RealSkyConfig cfg = RealSky.config;

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "Realistic Sky", fb.getColorAttachmentView(), OptionalInt.empty(), fb.getDepthAttachmentView(), OptionalDouble.empty())) {

            int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
            int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            boolean[] prevMask = colorMask();
            GlStateManager._activeTexture(GL_TEXTURE0);
            int prevSampler0 = glGetInteger(GL_SAMPLER_BINDING);
            GlStateManager._activeTexture(GL_TEXTURE1);
            int prevSampler1 = glGetInteger(GL_SAMPLER_BINDING);

            GlStateManager._viewport(0, 0, fb.textureWidth, fb.textureHeight);
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disableCull();
            GlStateManager._disableBlend();
            glColorMask(true, true, true, true);

            // Textures: unit 0 = Milky Way map, unit 1 = 3D cloud noise.
            GlStateManager._activeTexture(GL_TEXTURE1);
            glBindSampler(1, 0);
            glBindTexture(GL_TEXTURE_3D, noiseTex);
            GlStateManager._activeTexture(GL_TEXTURE0);
            glBindSampler(0, 0);
            GlStateManager._bindTexture(milkyWayTex);

            // 1) Sky, sun, moon, Milky Way, clouds. Alpha = how visible stars are at each pixel.
            glUseProgram(skyProgram);
            setMat4(skyProgram, "uInvViewProj", invViewProj);
            setMat3(skyProgram, "uWorldToEq", worldToEq);
            setVec3(skyProgram, "uSunDir", state.sunDir);
            setVec3(skyProgram, "uMoonDir", state.moonDir);
            setVec3(skyProgram, "uMoonLightDir", state.moonLightDir);
            setVec3(skyProgram, "uPoleDir", state.poleDir);
            setVec3(skyProgram, "uSunColor", state.sunColor);
            setVec3(skyProgram, "uMoonColor", state.moonColor);
            setVec3(skyProgram, "uAmbient", state.ambient);
            setVec3(skyProgram, "uCloudOffset", state.cloudOffset);
            setFloat(skyProgram, "uSunI", SkyState.SUN_I);
            setFloat(skyProgram, "uMoonI", state.moonI);
            setFloat(skyProgram, "uExposure", state.exposure);
            setFloat(skyProgram, "uNightScale", 1f / SkyState.NIGHT_EXPOSURE);
            setFloat(skyProgram, "uCamAlt", state.camAltKm + 0.064f);
            setFloat(skyProgram, "uRain", state.rain);
            setFloat(skyProgram, "uCoverage", cfg.cloudCoverage);
            setFloat(skyProgram, "uSunRadius", (float) Math.toRadians(cfg.sunRadiusDeg));
            setFloat(skyProgram, "uMoonRadius", (float) Math.toRadians(cfg.moonRadiusDeg));
            setFloat(skyProgram, "uMilkyWay", cfg.milkyWayBrightness);
            setFloat(skyProgram, "uNebula", cfg.nebulaStrength);
            setFloat(skyProgram, "uTime", state.timeSeconds);
            setInt(skyProgram, "uSteps", cfg.cloudQuality);
            setInt(skyProgram, "uCloudsOn", cfg.clouds && noiseTex != 0 ? 1 : 0);
            setInt(skyProgram, "uReflect", cfg.reflectiveHorizon ? 1 : 0);
            setInt(skyProgram, "uMilkyWayTex", 0);
            setInt(skyProgram, "uNoise", 1);
            glBindVertexArray(emptyVao);
            glDrawArrays(GL_TRIANGLES, 0, 3);

            // 2) Stars, added on top and weighted by the alpha written above.
            if (starCount > 0 && cfg.starBrightness > 0) {
                GlStateManager._enableBlend();
                GlStateManager._blendFuncSeparate(GL_DST_ALPHA, GL_ONE, GL_ZERO, GL_ONE);
                glEnable(GL_PROGRAM_POINT_SIZE);
                glUseProgram(starProgram);
                setMat4(starProgram, "uViewProj", viewProj);
                setMat3(starProgram, "uEqToWorld", state.eqToWorld);
                setFloat(starProgram, "uExposure", state.exposure);
                setFloat(starProgram, "uStarScale", 20f / SkyState.NIGHT_EXPOSURE * cfg.starBrightness);
                setFloat(starProgram, "uTime", state.timeSeconds);
                setFloat(starProgram, "uPixelScale", Math.max(0.5f, fb.textureHeight / 1080f));
                glBindVertexArray(starVao);
                glDrawArrays(GL_POINTS, 0, starCount);
                glDisable(GL_PROGRAM_POINT_SIZE);
                GlStateManager._disableBlend();
            }

            // 3) Put alpha back to 1 so later passes see an opaque sky.
            glColorMask(false, false, false, true);
            glUseProgram(alphaProgram);
            glBindVertexArray(emptyVao);
            glDrawArrays(GL_TRIANGLES, 0, 3);

            // Restore state.
            glColorMask(prevMask[0], prevMask[1], prevMask[2], prevMask[3]);
            GlStateManager._activeTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, 0);
            glBindSampler(1, prevSampler1);
            GlStateManager._activeTexture(GL_TEXTURE0);
            GlStateManager._bindTexture(0);
            glBindSampler(0, prevSampler0);
            glBindVertexArray(prevVao);
            glUseProgram(prevProgram);
            GlStateManager._depthMask(true);
            GlStateManager._enableDepthTest();
            GlStateManager._enableCull();
        }
    }

    private static boolean[] colorMask() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer b = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, b);
            return new boolean[]{b.get(0) != 0, b.get(1) != 0, b.get(2) != 0, b.get(3) != 0};
        }
    }

    // ------------------------------------------------------------------ resources

    private void init() throws IOException {
        skyProgram = link("fullscreen.vsh", "sky.fsh");
        starProgram = link("stars.vsh", "stars.fsh");
        alphaProgram = link("fullscreen.vsh", "alpha.fsh");

        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevBuf = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        emptyVao = glGenVertexArrays();
        loadStars();
        glBindVertexArray(prevVao);
        glBindBuffer(GL_ARRAY_BUFFER, prevBuf);

        loadMilkyWay();
        initialized = true;
        RealSky.LOGGER.info("Realistic Sky GL resources ready ({} stars)", starCount);
    }

    private void destroy() {
        for (int p : new int[]{skyProgram, starProgram, alphaProgram}) if (p != 0) glDeleteProgram(p);
        skyProgram = starProgram = alphaProgram = 0;
        uniformCache.clear();
        if (emptyVao != 0) glDeleteVertexArrays(emptyVao);
        if (starVao != 0) glDeleteVertexArrays(starVao);
        if (starVbo != 0) glDeleteBuffers(starVbo);
        emptyVao = starVao = starVbo = 0;
        if (milkyWayTex != 0) GlStateManager._deleteTexture(milkyWayTex);
        milkyWayTex = 0;
        // The noise volume is kept: it never changes.
        initialized = false;
    }

    private void loadStars() throws IOException {
        float[] data;
        try (DataInputStream in = new DataInputStream(open("stars.bin"))) {
            int n = in.readInt();
            data = new float[n * 7];
            for (int i = 0; i < n; i++) {
                float x = in.readFloat(), y = in.readFloat(), z = in.readFloat(), mag = in.readFloat(), bv = in.readFloat();
                float[] rgb = bvToRgb(bv);
                int o = i * 7;
                data[o] = x; data[o + 1] = y; data[o + 2] = z;
                data[o + 3] = rgb[0]; data[o + 4] = rgb[1]; data[o + 5] = rgb[2];
                data[o + 6] = mag;
            }
            starCount = n;
        }
        FloatBuffer fbuf = MemoryUtil.memAllocFloat(data.length);
        try {
            fbuf.put(data).flip();
            starVao = glGenVertexArrays();
            glBindVertexArray(starVao);
            starVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, starVbo);
            glBufferData(GL_ARRAY_BUFFER, fbuf, GL_STATIC_DRAW);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 28, 0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 28, 12);
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 1, GL_FLOAT, false, 28, 24);
        } finally {
            MemoryUtil.memFree(fbuf);
        }
    }

    /** B-V colour index -> linear RGB normalised to unit luminance (Ballesteros temperature + blackbody fit). */
    private static float[] bvToRgb(float bv) {
        bv = Math.max(-0.4f, Math.min(2.0f, bv));
        double t = 4600.0 * (1.0 / (0.92 * bv + 1.7) + 1.0 / (0.92 * bv + 0.62)) / 100.0;
        double r = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
        double g = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661 : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        double b = t >= 66 ? 255 : (t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307);
        float[] c = {
            (float) Math.pow(Math.max(0, Math.min(1, r / 255.0)), 2.2),
            (float) Math.pow(Math.max(0, Math.min(1, g / 255.0)), 2.2),
            (float) Math.pow(Math.max(0, Math.min(1, b / 255.0)), 2.2)};
        float l = Math.max(1e-3f, c[0] * 0.2126f + c[1] * 0.7152f + c[2] * 0.0722f);
        return new float[]{c[0] / l, c[1] / l, c[2] / l};
    }

    private void loadMilkyWay() throws IOException {
        byte[] png;
        try (InputStream in = open("milkyway.png")) {
            png = in.readAllBytes();
        }
        ByteBuffer src = MemoryUtil.memAlloc(png.length);
        ByteBuffer pixels = null;
        try {
            src.put(png).flip();
            IntBuffer w = BufferUtils.createIntBuffer(1), h = BufferUtils.createIntBuffer(1), comp = BufferUtils.createIntBuffer(1);
            pixels = STBImage.stbi_load_from_memory(src, w, h, comp, 1);
            if (pixels == null) throw new IOException("Could not decode milkyway.png: " + STBImage.stbi_failure_reason());
            milkyWayTex = glGenTextures();
            GlStateManager._activeTexture(GL_TEXTURE0);
            GlStateManager._bindTexture(milkyWayTex);
            int prevAlign = glGetInteger(GL_UNPACK_ALIGNMENT);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, w.get(0), h.get(0), 0, GL_RED, GL_UNSIGNED_BYTE, pixels);
            glPixelStorei(GL_UNPACK_ALIGNMENT, prevAlign);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            GlStateManager._bindTexture(0);
        } finally {
            MemoryUtil.memFree(src);
            if (pixels != null) STBImage.stbi_image_free(pixels);
        }
    }

    private void uploadNoiseIfReady() {
        if (noiseTex != 0) return;
        byte[] data = CloudNoise.poll();
        if (data == null) return;
        int n = CloudNoise.SIZE;
        ByteBuffer buf = MemoryUtil.memAlloc(data.length);
        try {
            buf.put(data).flip();
            GlStateManager._activeTexture(GL_TEXTURE1);
            noiseTex = glGenTextures();
            glBindTexture(GL_TEXTURE_3D, noiseTex);
            int prevAlign = glGetInteger(GL_UNPACK_ALIGNMENT);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA8, n, n, n, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glPixelStorei(GL_UNPACK_ALIGNMENT, prevAlign);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glGenerateMipmap(GL_TEXTURE_3D);
            glBindTexture(GL_TEXTURE_3D, 0);
            GlStateManager._activeTexture(GL_TEXTURE0);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    // ------------------------------------------------------------------ shaders

    /** Shader files can be overridden by dropping edited copies into config/realsky/shaders/. */
    private static InputStream open(String name) throws IOException {
        InputStream in = SkyRenderer.class.getResourceAsStream("/assets/realsky/" + name);
        if (in == null) throw new IOException("Missing resource " + name);
        return in;
    }

    private static String shaderSource(String name) throws IOException {
        Path override = FabricLoader.getInstance().getConfigDir().resolve("realsky").resolve("shaders").resolve(name);
        if (Files.isRegularFile(override)) return Files.readString(override, StandardCharsets.UTF_8);
        try (InputStream in = open("shaders/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int compile(int type, String name) throws IOException {
        int id = glCreateShader(type);
        glShaderSource(id, shaderSource(name));
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(id, 32768);
            glDeleteShader(id);
            throw new IllegalStateException("Shader " + name + " failed to compile: " + log.trim());
        }
        return id;
    }

    private static int link(String vsh, String fsh) throws IOException {
        int v = compile(GL_VERTEX_SHADER, vsh);
        int f = compile(GL_FRAGMENT_SHADER, fsh);
        int p = glCreateProgram();
        glAttachShader(p, v);
        glAttachShader(p, f);
        glLinkProgram(p);
        glDeleteShader(v);
        glDeleteShader(f);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(p, 32768);
            glDeleteProgram(p);
            throw new IllegalStateException("Program " + vsh + "+" + fsh + " failed to link: " + log.trim());
        }
        return p;
    }

    private int loc(int program, String name) {
        return uniformCache.computeIfAbsent(program, k -> new HashMap<>())
            .computeIfAbsent(name, n -> glGetUniformLocation(program, n));
    }

    private void setFloat(int p, String n, float v) {
        int l = loc(p, n);
        if (l >= 0) glUniform1f(l, v);
    }

    private void setInt(int p, String n, int v) {
        int l = loc(p, n);
        if (l >= 0) glUniform1i(l, v);
    }

    private void setVec3(int p, String n, float[] v) {
        int l = loc(p, n);
        if (l >= 0) glUniform3f(l, v[0], v[1], v[2]);
    }

    private void setMat4(int p, String n, Matrix4f m) {
        int l = loc(p, n);
        if (l < 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer b = stack.mallocFloat(16);
            m.get(b);
            glUniformMatrix4fv(l, false, b);
        }
    }

    private void setMat3(int p, String n, Matrix3f m) {
        int l = loc(p, n);
        if (l < 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer b = stack.mallocFloat(9);
            m.get(b);
            glUniformMatrix3fv(l, false, b);
        }
    }
}
