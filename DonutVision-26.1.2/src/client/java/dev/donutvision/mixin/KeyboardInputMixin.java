package dev.donutvision.mixin;

import dev.donutvision.Freecam;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent WASD/space/shift from moving the real player while flying the view. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void donutvision$freezeInput(CallbackInfo ci) {
        if (Freecam.active()) {
            this.keyPresses = Input.EMPTY;
            this.moveVector = Vec2.ZERO;
        }
    }
}
