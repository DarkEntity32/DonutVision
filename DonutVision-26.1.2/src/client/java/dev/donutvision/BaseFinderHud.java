package dev.donutvision;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Crosshair-only confidence tooltip for large client-side Base Finder cuboids. */
public final class BaseFinderHud {
    private static final int RED = 0xFFFF3535;
    private static final int WHITE = 0xFFF9F2EE;
    private static final int DARK = 0xE6101014;
    private static final double MAX_HOVER_DISTANCE = 256.0;

    private BaseFinderHud() { }

    public static void install() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("donutvision", "base_finder_hover"),
                (graphics, deltaTracker) -> render(graphics));
    }

    private static void render(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null || !BaseFinder.enabled()) return;
        if (BaseFinder.sites().isEmpty()) return;

        // Camera ray, not a server-side query. Freecam has its own camera position.
        Vec3 eye = Freecam.active() ? Freecam.position() : mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle().normalize();
        BaseFinder.Site hovered = findTarget(eye, look);
        if (hovered == null) return;

        String title = hovered.confidence();
        String info = "Score " + hovered.score() + "  |  " +
                hovered.pos().getX() + ", " + hovered.pos().getY() + ", " + hovered.pos().getZ();
        int width = Math.max(mc.font.width(title), mc.font.width(info)) + 22;
        int x = Math.max(5, (graphics.guiWidth() - width) / 2);
        int y = Math.min(graphics.guiHeight() - 41, graphics.guiHeight() / 2 + 19);
        graphics.fill(x, y, x + width, y + 36, DARK);
        graphics.fill(x, y, x + 3, y + 36, RED);
        graphics.text(mc.font, title, x + 10, y + 7, RED, true);
        graphics.text(mc.font, info, x + 10, y + 21, WHITE, false);
    }

    /** Nearest ray/box hit wins, so overlapping boxes are predictable. */
    static BaseFinder.Site findTarget(Vec3 origin, Vec3 direction) {
        BaseFinder.Site closest = null;
        double distance = MAX_HOVER_DISTANCE;
        for (BaseFinder.Site site : BaseFinder.sites()) {
            double hit = rayBoxDistance(origin, direction, site.region());
            if (hit >= 0 && hit < distance) {
                distance = hit;
                closest = site;
            }
        }
        return closest;
    }

    /** Slab intersection; -1 if the ray misses or the box is behind the camera. */
    static double rayBoxDistance(Vec3 ray, Vec3 dir, AABB box) {
        double tMin = 0.0, tMax = MAX_HOVER_DISTANCE;
        double[] p = {ray.x, ray.y, ray.z};
        double[] d = {dir.x, dir.y, dir.z};
        double[] lo = {box.minX, box.minY, box.minZ};
        double[] hi = {box.maxX, box.maxY, box.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0e-9) {
                if (p[axis] < lo[axis] || p[axis] > hi[axis]) return -1;
                continue;
            }
            double a = (lo[axis] - p[axis]) / d[axis];
            double b = (hi[axis] - p[axis]) / d[axis];
            if (a > b) { double temp = a; a = b; b = temp; }
            tMin = Math.max(tMin, a);
            tMax = Math.min(tMax, b);
            if (tMax < tMin) return -1;
        }
        return tMin;
    }
}
