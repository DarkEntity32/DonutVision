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
        espEnabled = enabled;
        if (!enabled) BlockEsp.clear();
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

        System.out.println("[DonutVision] 1.2.0 client initializer loaded. Menu keys: Right Shift or F10.");
        ClientSettings.load();
        BlockEsp.install();
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
            return;
        }
        if (!welcomeShown) {
            welcomeShown = true;
            notify(mc, "Loaded! Menu: Right Shift or F10. Check Controls > Key Binds if neither works.");
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
            notify(mc, "ESP radius: " + BlockEsp.radius() + " blocks");
        }
        Freecam.tick(mc);
        BlockEsp.tick(mc, espEnabled);
    }

    private static void notify(Minecraft mc, String text) {
        mc.player.sendSystemMessage(Component.literal("[DonutVision] " + text));
    }
}
