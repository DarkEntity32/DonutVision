package dev.donutvision;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Optional, client-only timed attacks against the living entity currently under the crosshair.
 * No extended reach, no targeting through walls, no packet spoofing, and no background runner.
 */
public final class AutoAttack {
    private static final long MIN_INTERVAL_MS = 200L;
    private static final long MAX_INTERVAL_MS = 3000L;
    private static boolean enabled;
    private static long intervalMs = 800L;
    private static long nextAttackAt;

    private AutoAttack() { }

    public static boolean enabled() { return enabled; }
    public static int intervalMs() { return (int)intervalMs; }
    public static String intervalLabel() {
        return String.format(java.util.Locale.ROOT, "%.2f s", intervalMs / 1000.0);
    }
    public static void setEnabled(boolean on) {
        enabled = on;
        nextAttackAt = 0L;
    }
    public static void toggle() {
        setEnabled(!enabled);
        ClientSettings.save();
    }
    public static void setInterval(int milliseconds) {
        intervalMs = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, milliseconds));
        nextAttackAt = 0L;
    }

    /** Tick precision is about 50ms at 20 TPS; timings cannot bypass server cooldowns. */
    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null || mc.gameMode == null
                || mc.screen != null || Freecam.active() || mc.gameMode.isSpectator()) {
            nextAttackAt = 0L;
            return;
        }
        // Only an entity that vanilla ray-picking has already put under the crosshair.
        if (!(mc.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target)
                || !target.isAlive() || target == mc.player
                || mc.player.distanceToSqr(target) > 9.0D) {
            nextAttackAt = 0L;
            return;
        }
        long now = System.nanoTime();
        if (nextAttackAt != 0L && now < nextAttackAt) return;
        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        nextAttackAt = now + intervalMs * 1_000_000L;
    }
}
