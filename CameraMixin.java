package dev.donutvision.mixin;

import dev.donutvision.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Decouple viewpoint position from real player. Player orientation is retained. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setPosition(Vec3 position);

    @Inject(method = "update", at = @At("TAIL"))
    private void donutvision$moveView(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Freecam.active()) {
            setPosition(Freecam.position());
        }
    }
}
