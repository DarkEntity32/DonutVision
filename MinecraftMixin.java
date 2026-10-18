package dev.donutvision.mixin;

import dev.donutvision.Freecam;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Block mining/using from the stationary player while freecam is active. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void donutvision$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.active()) cir.setReturnValue(false);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void donutvision$blockUse(CallbackInfo ci) {
        if (Freecam.active()) ci.cancel();
    }
}
