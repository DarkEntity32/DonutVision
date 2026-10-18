package dev.donutvision;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Camera-only positional offset. The player entity is never teleported. */
public final class Freecam {
    private static boolean active;
    private static Vec3 position = Vec3.ZERO;
    private static double speed = 0.42;

    private Freecam() { }

    public static boolean active() {
        return active;
    }

    public static Vec3 position() {
        return position;
    }

    public static void toggle(Minecraft mc) {
        if (mc.player == null) return;
        active = !active;
        if (active) {
            position = mc.player.getEyePosition();
        }
    }

    public static double speed() {
        return speed;
    }

    public static void setSpeed(double newSpeed) {
        if (Double.isFinite(newSpeed)) speed = Math.max(0.2, Math.min(1.5, newSpeed));
    }

    public static void disable() {
        active = false;
    }

    /** Camera WASD/Q/E motion with no player position packets. */
    public static void tick(Minecraft mc) {
        if (!active || mc.player == null || mc.screen != null) return;

        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double strafe = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
        double rise = (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);

        double angle = Math.toRadians(mc.player.getYRot());
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double dx = -sin * forward + cos * strafe;
        double dz = cos * forward + sin * strafe;
        double length = Math.sqrt(dx * dx + dz * dz + rise * rise);
        if (length < 0.01) return;

        double movementSpeed = speed * (mc.options.keySprint.isDown() ? 2.8 : 1.0);
        position = position.add(dx / length * movementSpeed, rise / length * movementSpeed, dz / length * movementSpeed);
    }
}
