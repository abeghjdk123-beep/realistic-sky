package com.realsky.mixin;

import com.realsky.RealSky;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Inject(method = "getFogColor", at = @At("RETURN"))
    private void realsky$matchFog(Camera camera, float tickProgress, ClientWorld world, int viewDistance, float skyDarkness,
                                  CallbackInfoReturnable<Vector4f> cir) {
        RealSky.RENDERER.adjustFog(camera, cir.getReturnValue());
    }
}
