package dev.donutvision;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Tiny client-only settings file: .minecraft/config/donutvision.properties */
public final class ClientSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("donutvision.properties");

    private ClientSettings() { }

    public static void load() {
        if (!Files.isRegularFile(FILE)) return;
        Properties data = new Properties();
        try (InputStream input = Files.newInputStream(FILE)) {
            data.load(input);
            DonutVisionClient.setEspEnabled(Boolean.parseBoolean(data.getProperty("espEnabled", "false")));
            BlockEsp.setRadius(parseInt(data.getProperty("radius"), 12));
            BlockEsp.setMode(parseInt(data.getProperty("mode"), 0));
            Freecam.setSpeed(parseDouble(data.getProperty("speed"), 0.42));
            for (BlockEsp.Filter filter : BlockEsp.Filter.values()) {
                BlockEsp.setFilter(filter, Boolean.parseBoolean(data.getProperty("block." + filter.name(), "true")));
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[DonutVision] Unable to load settings: " + e.getMessage());
        }
    }

    public static void save() {
        Properties data = new Properties();
        data.setProperty("espEnabled", Boolean.toString(DonutVisionClient.espEnabled()));
        data.setProperty("radius", Integer.toString(BlockEsp.radius()));
        data.setProperty("mode", Integer.toString(BlockEsp.mode()));
        data.setProperty("speed", Double.toString(Freecam.speed()));
        for (BlockEsp.Filter filter : BlockEsp.Filter.values()) {
            data.setProperty("block." + filter.name(), Boolean.toString(BlockEsp.filterEnabled(filter)));
        }
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                data.store(out, "DonutVision client configuration");
            }
        } catch (IOException e) {
            System.err.println("[DonutVision] Unable to save settings: " + e.getMessage());
        }
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static double parseDouble(String s, double fallback) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; }
    }
}
