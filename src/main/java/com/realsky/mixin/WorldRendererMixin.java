package com.realsky.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.realsky.RealSky;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.render.FrameGraphBuilder;
import net.minecraft.client.render.FramePass;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Shadow @Final private DefaultFramebufferSet framebufferSet;
    @Shadow @Nullable private ClientWorld world;

    @Inject(method = "render", at = @At("HEAD"))
    private void realsky$beginFrame(ObjectAllocator allocator, RenderTickCounter tickCounter, boolean renderBlockOutline,
                                    Camera camera, Matrix4f positionMatrix, Matrix4f basicProjectionMatrix,
                                    Matrix4f projectionMatrix, GpuBufferSlice fogBuffer, Vector4f fogColor,
                                    boolean renderSky, CallbackInfo ci) {
        RealSky.RENDERER.beginFrame(world, camera, positionMatrix, basicProjectionMatrix, tickCounter.getTickProgress(false));
    }

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void realsky$replaceSky(FrameGraphBuilder frameGraphBuilder, Camera camera, GpuBufferSlice fogBuffer, CallbackInfo ci) {
        if (!RealSky.RENDERER.isActive()) return;
        FramePass pass = frameGraphBuilder.createPass("realsky_sky");
        framebufferSet.mainFramebuffer = pass.transfer(framebufferSet.mainFramebuffer);
        pass.setRenderer(RealSky.RENDERER::render);
        ci.cancel();
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void realsky$hideVanillaClouds(CallbackInfo ci) {
        if (RealSky.RENDERER.isActive() && RealSky.config.clouds && RealSky.config.hideVanillaClouds) ci.cancel();
    }
}
