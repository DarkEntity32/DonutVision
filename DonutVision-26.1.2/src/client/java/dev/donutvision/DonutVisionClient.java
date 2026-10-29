package dev.donutvision;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class DonutVisionClient implements ClientModInitializer {
    private static boolean espEnabled;
    private static boolean spawnerEspEnabled;
    private static boolean crystalEspEnabled;
    private static boolean blockTracers = true;
    private static KeyMapping freecamKey;
    private static KeyMapping espKey;
    private static KeyMapping modeKey;
    private static KeyMapping radiusKey;
    private static KeyMapping menuKey;
    private static KeyMapping backupMenuKey;
    private boolean welcomeShown = false;

    public static boolean espEnabled() {
        return espEnabled;
    }

    public static void setEspEnabled(boolean enabled) {
        if (espEnabled != enabled) {
            espEnabled = enabled;
            BlockEsp.clear(); // Rebuild immediately when ore/storage scanning changes.
        }
    }

    public static boolean spawnerEspEnabled() { return spawnerEspEnabled; }

    public static void setSpawnerEspEnabled(boolean enabled) {
        if (spawnerEspEnabled != enabled) {
            spawnerEspEnabled = enabled;
            BlockEsp.clear(); // Immediately scan spawners after the menu toggle.
        }
    }

    public static void toggleSpawnerEsp() {
        setSpawnerEspEnabled(!spawnerEspEnabled);
        ClientSettings.save();
    }

    public static boolean crystalEspEnabled() { return crystalEspEnabled; }

    public static void setCrystalEspEnabled(boolean enabled) {
        if (crystalEspEnabled != enabled) {
            crystalEspEnabled = enabled;
            BlockEsp.clear(); // Refresh crystal targets without changing other settings.
        }
    }

    public static void toggleCrystalEsp() {
        setCrystalEspEnabled(!crystalEspEnabled);
        ClientSettings.save();
    }

    public static boolean blockTracers() { return blockTracers; }

    public static void setBlockTracers(boolean enabled) { blockTracers = enabled; }

    public static void toggleBlockTracers() {
        blockTracers = !blockTracers;
        ClientSettings.save();
    }

    public static void toggleEsp() {
        setEspEnabled(!espEnabled);
        ClientSettings.save();
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("donutvision", "tools"));

        freecamKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutvision.freecam", InputConstants.Type.KEYSYM, InputConstants.KEY_F6, category));
        espKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutvision.esp", InputConstants.Type.KEYSYM, InputConstants.KEY_F7, category));
        modeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutvision.mode", InputConstants.Type.KEYSYM, InputConstants.KEY_F8, category));
        radiusKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutvision.radius", InputConstants.Type.KEYSYM, InputConstants.KEY_F9, category));

        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutvision.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));

        backupMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutvision.menu_backup", InputConstants.Type.KEYSYM, InputConstants.KEY_F10, category));

        System.out.println("[DonutVision] 1.9.2 client initializer loaded. Menu keys: Right Shift or F10.");
        ClientSettings.load();
        EspOverlay.install();
        BaseFinderHud.install();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        boolean openMenu = false;
        while (menuKey.consumeClick()) openMenu = true;
        while (backupMenuKey.consumeClick()) openMenu = true;
        if (openMenu && mc.screen == null && mc.player != null) {
            mc.setScreen(new DonutVisionScreen());
        }
        if (mc.player == null || mc.level == null) {
            welcomeShown = false;
            Freecam.disable();
            BlockEsp.clear();
            BaseFinder.onWorldGone();
            return;
        }
        if (!welcomeShown) {
            welcomeShown = true;
            notify(mc, "v1.9.2 loaded! Base Finder: Modules / Bases tab.");
        }
        while (freecamKey.consumeClick()) {
            Freecam.toggle(mc);
            notify(mc, "Freecam: " + (Freecam.active() ? "ON" : "OFF"));
        }
        while (espKey.consumeClick()) {
            toggleEsp();
            notify(mc, "Block ESP: " + (espEnabled ? "ON" : "OFF"));
        }
        while (modeKey.consumeClick()) {
            BlockEsp.nextMode();
            ClientSettings.save();
            notify(mc, "ESP targets: " + BlockEsp.modeLabel());
        }
        while (radiusKey.consumeClick()) {
            BlockEsp.nextRadius();
            ClientSettings.save();
            notify(mc, "ESP radius: " + BlockEsp.rangeLabel(BlockEsp.radius()));
        }
        Freecam.tick(mc);
        BlockEsp.tick(mc, espEnabled, spawnerEspEnabled, crystalEspEnabled);
        BaseFinder.tick(mc);
    }

    private static void notify(Minecraft mc, String text) {
        mc.player.sendSystemMessage(Component.literal("[DonutVision] " + text));
    }
}
