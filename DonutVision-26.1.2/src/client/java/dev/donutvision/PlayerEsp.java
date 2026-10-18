package dev.donutvision;

/** Client-side player highlighting and tracer settings. */
public final class PlayerEsp {
    private static boolean enabled = false;
    private static boolean tracers = true;
    private static int radius = BlockEsp.UNLIMITED;

    private PlayerEsp() { }

    public static int radius() { return radius; }
    public static void setRadius(int value) {
        radius = value == BlockEsp.UNLIMITED ? BlockEsp.UNLIMITED : Math.max(10, Math.min(512, value));
    }

    public static boolean enabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }
    public static void toggle() {
        setEnabled(!enabled);
        ClientSettings.save();
    }

    public static boolean tracers() { return tracers; }
    public static void setTracers(boolean value) { tracers = value; }
    public static void toggleTracers() {
        setTracers(!tracers);
        ClientSettings.save();
    }
}
