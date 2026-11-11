package com.realsky;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealSky implements ClientModInitializer {
    public static final String MOD_ID = "realsky";
    public static final Logger LOGGER = LoggerFactory.getLogger("Realistic Sky");

    public static RealSkyConfig config = new RealSkyConfig();
    public static final SkyRenderer RENDERER = new SkyRenderer();

    @Override
    public void onInitializeClient() {
        config = RealSkyConfig.load();
        CloudNoise.generateAsync();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("realsky")
                .then(ClientCommandManager.literal("reload").executes(ctx -> {
                    config = RealSkyConfig.load();
                    RENDERER.requestReload();
                    ctx.getSource().sendFeedback(Text.literal("[Realistic Sky] Config and shaders reloaded."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    config.enabled = !config.enabled;
                    config.save();
                    ctx.getSource().sendFeedback(Text.literal("[Realistic Sky] " + (config.enabled ? "Enabled" : "Disabled")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("clouds")
                    .then(ClientCommandManager.argument("coverage", FloatArgumentType.floatArg(0f, 1f)).executes(ctx -> {
                        config.cloudCoverage = FloatArgumentType.getFloat(ctx, "coverage");
                        config.save();
                        ctx.getSource().sendFeedback(Text.literal("[Realistic Sky] Cloud coverage set to " + config.cloudCoverage));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("[Realistic Sky] " + RENDERER.status()));
                    return 1;
                }))
        ));
        LOGGER.info("Realistic Sky initialised");
    }

    /** True when an Iris shader pack is active: the shader pack then owns the sky and we stay out of the way. */
    public static boolean irisShadersActive() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return false;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isShaderPackInUse").invoke(inst);
        } catch (Throwable t) {
            return false;
        }
    }
}
