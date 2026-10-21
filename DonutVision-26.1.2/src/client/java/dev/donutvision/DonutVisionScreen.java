package dev.donutvision;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Black/orange animated GUI with entirely custom-drawn controls and MacBook trackpad support. */
public final class DonutVisionScreen extends Screen {
    private enum Page { MODULES, FILTERS, SETTINGS, COLORS }
    private enum Kind { TOGGLE, ACTION, SLIDER }

    private static final int BACKDROP = 0xF409090C;
    private static final int PANEL = 0xFF101014;
    private static final int HEADER = 0xFF181719;
    private static final int CARD = 0xFF1B1B21;
    private static final int CARD_HOVER = 0xFF312922;
    private static final int TRACK = 0xFF353039;
    private static final int BORDER = 0xFF393137;
    private static final int ORANGE = 0xFFFF8A25;
    private static final int ORANGE_BRIGHT = 0xFFFFB357;
    private static final int TEXT = 0xFFF8F0E9;
    private static final int DIM = 0xFF9D9290;
    private static final int DISABLED = 0xFF70666B;

    private static final class Control {
        final String label;
        final String description;
        final Kind kind;
        final int x, y, w, h;
        final Supplier<Boolean> enabled;
        final Supplier<String> dynamicLabel;
        final Runnable press;
        final DoubleSupplier sliderValue;
        final DoubleConsumer sliderChange;
        float hover;
        float active;
        float progress;

        Control(String label, String description, Kind kind, int x, int y, int w, int h,
                Supplier<Boolean> enabled, Supplier<String> dynamicLabel, Runnable press,
                DoubleSupplier sliderValue, DoubleConsumer sliderChange) {
            this.label = label;
            this.description = description;
            this.kind = kind;
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.enabled = enabled; this.dynamicLabel = dynamicLabel; this.press = press;
            this.sliderValue = sliderValue; this.sliderChange = sliderChange;
            this.active = enabled != null && enabled.get() ? 1f : 0f;
            this.progress = sliderValue != null ? (float)sliderValue.getAsDouble() : 0f;
        }
    }

    private Page page = Page.MODULES;
    private final List<Control> controls = new ArrayList<>();
    private int left, top, panelWidth, panelHeight, colWidth, contentBottom;
    private double scrollGoal, scrollDraw;
    private boolean draggingScrollbar;
    private boolean draggingSlider;
    private double scrollbarGrab;
    private Control selectedSlider;
    private final float[] tabHover = new float[Page.values().length];
    private float animatedTabX;
    private float intro = 0f;
    private long lastFrame = System.nanoTime();
    private int pointerX, pointerY;

    public DonutVisionScreen() { super(Component.literal("DonutVision")); }

    @Override
    protected void init() {
        panelWidth = Math.min(516, width - 14);
        panelHeight = Math.min(404, height - 10);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        colWidth = (panelWidth - 48) / 2;
        animatedTabX = tabX(page.ordinal());
        buildControls();
    }

    private int viewportTop() { return top + 105; }
    private int viewportBottom() { return top + panelHeight - 54; }
    private int scrollBarX() { return left + panelWidth - 14; }
    private double maxScroll() { return Math.max(0, contentBottom - viewportBottom() + 12.0); }
    private int tabWidth() { return Math.max(40, (panelWidth - 43) / 4); }
    private int tabX(int index) { return left + 14 + index * (tabWidth() + 5); }

    private void buildControls() {
        controls.clear();
        scrollGoal = scrollDraw = 0;
        intro = 0f;
        int startY = top + 148;
        int x1 = left + 17;
        int x2 = x1 + colWidth + 8;
        switch (page) {
            case MODULES -> {
                toggle("Freecam", "Detached camera", x1, startY,
                        Freecam::active, () -> Freecam.toggle(minecraft));
                toggle("Block ESP", "Ores and storage", x2, startY,
                        DonutVisionClient::espEnabled, DonutVisionClient::toggleEsp);
                toggle("Spawner ESP", "Mob / trial spawners", x1, startY + 62,
                        DonutVisionClient::spawnerEspEnabled, DonutVisionClient::toggleSpawnerEsp);
                toggle("Player ESP", "Visible players", x2, startY + 62,
                        PlayerEsp::enabled, PlayerEsp::toggle);
                toggle("Block tracers", "Lines to found blocks", x1, startY + 124,
                        DonutVisionClient::blockTracers, DonutVisionClient::toggleBlockTracers);
                toggle("Player tracers", "Lines to players", x2, startY + 124,
                        PlayerEsp::tracers, PlayerEsp::toggleTracers);
                toggle("Crystal Cluster ESP", "Amethyst crystal clusters", x1, startY + 186,
                        DonutVisionClient::crystalEspEnabled, DonutVisionClient::toggleCrystalEsp);
                contentBottom = startY + 330;
            }
            case FILTERS -> {
                BlockEsp.Filter[] filters = BlockEsp.Filter.values();
                for (int i = 0; i < filters.length; i++) {
                    BlockEsp.Filter filter = filters[i];
                    int row = i / 2;
                    String label = switch (filter) {
                        case DISPENSERS -> "Dispensers";
                        case REDSTONE -> "Redstone blocks";
                        default -> filter.label();
                    };
                    toggle(label, "Block filter", (i % 2 == 0) ? x1 : x2,
                            startY + row * 57,
                            () -> BlockEsp.filterEnabled(filter), () -> {
                                BlockEsp.toggleFilter(filter);
                                ClientSettings.save();
                            });
                }
                int y = startY + ((filters.length + 1) / 2) * 57 + 5;
                action(() -> "Mode: " + BlockEsp.modeLabel(), "Click to change category",
                        x1, y, panelWidth - 42, () -> {
                            BlockEsp.nextMode();
                            ClientSettings.save();
                        });
                contentBottom = y + 78;
            }
            case SETTINGS -> {
                slider("Block ESP range", "10 blocks to infinity", x1, startY, panelWidth - 42,
                        () -> BlockEsp.stepIndex(BlockEsp.radius()) / (double)(BlockEsp.RANGE_STEPS.length - 1),
                        value -> BlockEsp.setRadius(BlockEsp.stepValue((int)Math.round(value * (BlockEsp.RANGE_STEPS.length - 1)))));
                slider("Player ESP range", "10 blocks to infinity", x1, startY + 91, panelWidth - 42,
                        () -> BlockEsp.stepIndex(PlayerEsp.radius()) / (double)(BlockEsp.RANGE_STEPS.length - 1),
                        value -> PlayerEsp.setRadius(BlockEsp.stepValue((int)Math.round(value * (BlockEsp.RANGE_STEPS.length - 1)))));
                slider("Freecam speed", "0.20 - 1.50", x1, startY + 182, panelWidth - 42,
                        () -> (Freecam.speed() - 0.2) / 1.3,
                        value -> Freecam.setSpeed(0.2 + value * 1.3));
                contentBottom = startY + 338;
            }
            case COLORS -> contentBottom = startY + 389;
        }
    }

    private void toggle(String title, String detail, int x, int y,
                        Supplier<Boolean> state, Runnable action) {
        controls.add(new Control(title, detail, Kind.TOGGLE, x, y, colWidth, 50,
                state, null, action, null, null));
    }

    private void action(Supplier<String> title, String detail, int x, int y, int width, Runnable action) {
        controls.add(new Control("", detail, Kind.ACTION, x, y, width, 50,
                null, title, action, null, null));
    }

    private void slider(String title, String detail, int x, int y, int width,
                        DoubleSupplier value, DoubleConsumer setter) {
        controls.add(new Control(title, detail, Kind.SLIDER, x, y, width, 77,
                null, null, null, value, setter));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        pointerX = mouseX;
        pointerY = mouseY;
        long now = System.nanoTime();
        float dt = Math.max(0.001f, Math.min(0.05f, (now - lastFrame) / 1_000_000_000f));
        lastFrame = now;
        float ease = 1f - (float)Math.exp(-dt * 14);
        intro = approach(intro, 1f, ease * 0.65f);
        scrollDraw += (scrollGoal - scrollDraw) * ease;
        animatedTabX = approach(animatedTabX, tabX(page.ordinal()), ease);
        for (int i = 0; i < Page.values().length; i++) {
            boolean hovered = in(mouseX, mouseY, tabX(i), top + 64, tabWidth(), 28);
            tabHover[i] = approach(tabHover[i], hovered ? 1f : 0f, ease);
        }
        for (Control control : controls) {
            int visibleY = control.y - (int)Math.round(scrollDraw);
            boolean hover = mouseX >= control.x && mouseX < control.x + control.w
                    && mouseY >= visibleY && mouseY < visibleY + control.h
                    && mouseY >= viewportTop() && mouseY < viewportBottom();
            control.hover = approach(control.hover, hover ? 1f : 0f, ease);
            if (control.enabled != null) control.active = approach(control.active,
                    control.enabled.get() ? 1f : 0f, ease);
            if (control.sliderValue != null) control.progress = approach(control.progress,
                    (float)clamp(control.sliderValue.getAsDouble(), 0, 1), ease);
        }

        drawShadow(g);
        rounded(g, left, top, panelWidth, panelHeight, 8, PANEL);
        rounded(g, left + 1, top + 1, panelWidth - 2, 51, 7, HEADER);
        g.fill(left + 9, top + 1, left + 15, top + 52, ORANGE);
        g.fill(left + 16, top + 1, left + 21, top + 52, 0x55FF8A25);
        g.text(font, "DONUT", left + 30, top + 18, TEXT, true);
        g.text(font, "VISION", left + 66, top + 18, ORANGE_BRIGHT, true);
        g.text(font, "v1.8.0  //  RSHIFT + F10", left + panelWidth - 167, top + 18, DIM, false);

        double pulse = (Math.sin(now / 1_000_000_000.0 * 2.5) + 1.0) / 2.0;
        int pulseW = 18 + (int)(pulse * 18);
        g.fill(left + panelWidth - 35 - pulseW, top + 47,
                left + panelWidth - 35, top + 49, ORANGE);
        drawTabs(g);
        rounded(g, left + 9, top + 100, panelWidth - 18, panelHeight - 151, 6, BACKDROP);

        g.enableScissor(left + 12, viewportTop(), left + panelWidth - 20, viewportBottom());
        g.pose().pushMatrix();
        g.pose().translate(0, (float)-scrollDraw);
        drawContent(g);
        g.pose().popMatrix();
        g.disableScissor();
        drawScrollbar(g);
        drawFooter(g);
    }

    private void drawShadow(GuiGraphicsExtractor g) {
        rounded(g, left - 7, top - 7, panelWidth + 14, panelHeight + 14, 10, 0x45000000);
        rounded(g, left - 3, top - 3, panelWidth + 6, panelHeight + 6, 9, BORDER);
        g.fill(left + 16, top - 3, left + panelWidth - 16, top - 1, ORANGE);
    }

    private void drawTabs(GuiGraphicsExtractor g) {
        Page[] pages = Page.values();
        for (int i = 0; i < pages.length; i++) {
            int tx = tabX(i);
            boolean selected = page == pages[i];
            int color = blend(CARD, CARD_HOVER, tabHover[i] * 0.6f + (selected ? 0.4f : 0));
            rounded(g, tx, top + 64, tabWidth(), 28, 5, color);
            String label = switch (pages[i]) {
                case MODULES -> "Modules";
                case FILTERS -> "Filters";
                case SETTINGS -> "Settings";
                case COLORS -> "Colors";
            };
            int tw = font.width(label);
            g.text(font, label, tx + (tabWidth() - tw) / 2, top + 74,
                    selected ? ORANGE_BRIGHT : TEXT, false);
        }
        g.fill((int)animatedTabX + 7, top + 94,
                (int)animatedTabX + tabWidth() - 7, top + 96, ORANGE);
    }

    private void drawContent(GuiGraphicsExtractor g) {
        String heading = switch (page) {
            case MODULES -> "MODULE CONTROL";
            case FILTERS -> "TARGET FILTERS";
            case SETTINGS -> "RANGE & MOVEMENT";
            case COLORS -> "TRACER PALETTE";
        };
        String subtitle = switch (page) {
            case MODULES -> "Real-time toggles  /  animated switches";
            case FILTERS -> "Select which targets to highlight";
            case SETTINGS -> "Custom ranges and movement speed";
            case COLORS -> "Each target keeps its own tracer color";
        };
        g.text(font, heading, left + 18, top + 115, blend(DIM, ORANGE_BRIGHT, intro), true);
        g.text(font, subtitle, left + 18, top + 129, DIM, false);
        if (page == Page.COLORS) {
            drawColorPalette(g);
            return;
        }
        for (Control control : controls) {
            switch (control.kind) {
                case TOGGLE -> drawToggle(g, control);
                case ACTION -> drawAction(g, control);
                case SLIDER -> drawSlider(g, control);
            }
        }
        if (page == Page.MODULES) {
            int y = top + 405;
            rounded(g, left + 17, y, panelWidth - 42, 49, 5, CARD);
            g.text(font, "TRACKED BLOCKS", left + 28, y + 11, DIM, false);
            g.text(font, Integer.toString(BlockEsp.targets().size()), left + 28, y + 29, ORANGE_BRIGHT, true);
            g.text(font, "  |  Nearby block changes are checked continuously",
                    left + 52, y + 29, TEXT, false);
        } else if (page == Page.SETTINGS) {
            rounded(g, left + 17, top + 425, panelWidth - 42, 42, 5, CARD);
            g.text(font, "Infinity = client-loaded chunks only", left + 29, top + 440, TEXT, false);
            g.text(font, "All changes are saved when closing the menu", left + 29, top + 454, DIM, false);
        }
    }

    private void drawToggle(GuiGraphicsExtractor g, Control c) {
        rounded(g, c.x, c.y + 2, c.w, c.h, 6, blend(CARD, CARD_HOVER, c.hover));
        int rail = blend(DISABLED, ORANGE, c.active);
        g.fill(c.x + 1, c.y + 9, c.x + 4, c.y + c.h - 6, rail);
        g.text(font, c.label, c.x + 13, c.y + 14, TEXT, true);
        g.text(font, c.description, c.x + 13, c.y + 30, DIM, false);
        int tx = c.x + c.w - 48;
        int ty = c.y + 16;
        rounded(g, tx, ty, 35, 18, 9, blend(TRACK, ORANGE, c.active));
        int knob = tx + 3 + Math.round(c.active * 17);
        rounded(g, knob, ty + 3, 12, 12, 6, c.active > 0.45f ? 0xFFFFFFFF : 0xFFD2C6C2);
        if (c.hover > 0.03f) {
            int lineW = Math.round((c.w - 22) * c.hover);
            g.fill(c.x + 11, c.y + c.h - 2, c.x + 11 + lineW, c.y + c.h - 1, ORANGE);
        }
    }

    private void drawAction(GuiGraphicsExtractor g, Control c) {
        rounded(g, c.x, c.y, c.w, c.h, 6, blend(CARD, CARD_HOVER, c.hover));
        g.fill(c.x + 1, c.y + 8, c.x + 4, c.y + c.h - 8, ORANGE);
        String title = c.dynamicLabel.get();
        g.text(font, title, c.x + 14, c.y + 15, TEXT, true);
        g.text(font, c.description, c.x + 14, c.y + 32, DIM, false);
        g.text(font, ">", c.x + c.w - 20, c.y + 20, ORANGE_BRIGHT, true);
    }

    private void drawSlider(GuiGraphicsExtractor g, Control c) {
        rounded(g, c.x, c.y, c.w, c.h, 6, blend(CARD, CARD_HOVER, c.hover));
        g.text(font, c.label, c.x + 14, c.y + 14, TEXT, true);
        String reading = switch (c.label) {
            case "Block ESP range" -> BlockEsp.rangeLabel(BlockEsp.radius());
            case "Player ESP range" -> BlockEsp.rangeLabel(PlayerEsp.radius());
            default -> String.format(Locale.ROOT, "%.2f", Freecam.speed());
        };
        g.text(font, reading, c.x + c.w - 14 - font.width(reading), c.y + 14, ORANGE_BRIGHT, true);
        int sx = c.x + 18, sy = c.y + 48, sw = c.w - 36;
        rounded(g, sx, sy, sw, 6, 3, TRACK);
        int fillW = Math.max(0, Math.round(sw * c.progress));
        if (fillW > 0) rounded(g, sx, sy, fillW, 6, 3, ORANGE);
        int thumb = sx + fillW;
        rounded(g, thumb - 7, sy - 5, 14, 16, 6, ORANGE_BRIGHT);
        g.text(font, c.description, sx, c.y + 65, DIM, false);
    }

    private void drawColorPalette(GuiGraphicsExtractor g) {
        String[] names = {"Chest", "Trapped chest", "Shulker box", "Hopper",
                "Barrel", "Ender chest", "Redstone block", "Dispenser / dropper",
                "Furnaces", "Diamonds", "Spawner", "Trial spawner",
                "Ancient debris", "Gold ore", "Emerald ore", "Players", "Crystal clusters"};
        int[] colors = {0xFFE8CBA5, 0xFFE5A985, 0xFFFF8CCF, 0xFF9AA4B2,
                0xFFCB9B6C, 0xFFA996FF, 0xFFFF485F, 0xFF86A8C0,
                0xFF80A5A2, 0xFF35DEFF, 0xFFFF4A56, 0xFFFFBD43,
                0xFFFF943E, 0xFFFFD46B, 0xFF4FFFA0, 0xFF72FFAD, BlockEsp.CRYSTAL_COLOR};
        for (int i = 0; i < names.length; i++) {
            int x = left + 17 + (i % 2) * (colWidth + 8);
            int y = top + 151 + (i / 2) * 39;
            rounded(g, x, y, colWidth, 32, 5, CARD);
            rounded(g, x + 10, y + 8, 16, 16, 4, colors[i]);
            g.text(font, names[i], x + 34, y + 12, TEXT, false);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor g) {
        int x = scrollBarX(), y = viewportTop(), h = viewportBottom() - viewportTop();
        rounded(g, x, y, 5, h, 2, TRACK);
        if (maxScroll() < 1) return;
        int thumbHeight = Math.max(24, (int)(h * h / (h + maxScroll())));
        int travel = h - thumbHeight;
        int thumbY = y + (int)Math.round((scrollDraw / maxScroll()) * travel);
        rounded(g, x - 1, thumbY, 7, thumbHeight, 3, ORANGE);
    }

    private int scrollbarThumbHeight() {
        int h = viewportBottom() - viewportTop();
        return Math.max(24, (int)(h * h / (h + maxScroll())));
    }

    private void drawFooter(GuiGraphicsExtractor g) {
        int y = top + panelHeight - 46;
        g.fill(left + 17, y - 3, left + panelWidth - 17, y - 2, BORDER);
        g.text(font, "TRACKPAD  TWO FINGERS TO SCROLL", left + 19, y + 18, DIM, false);
        boolean hover = in(pointerX, pointerY, left + panelWidth - 112, y + 5, 91, 26);
        rounded(g, left + panelWidth - 112, y + 5, 91, 26, 6, hover ? ORANGE_BRIGHT : ORANGE);
        g.text(font, "DONE", left + panelWidth - 82, y + 14, 0xFF1A0F09, true);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (in(x, y, left + 10, viewportTop(), panelWidth - 19, viewportBottom() - viewportTop())
                && vertical != 0.0) {
            scrollGoal = clamp(scrollGoal - vertical * 28.0, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        double x = event.x(), y = event.y();
        if (in(x, y, left + panelWidth - 112, top + panelHeight - 41, 91, 26)) {
            onClose();
            return true;
        }
        for (int i = 0; i < Page.values().length; i++) {
            if (in(x, y, tabX(i), top + 64, tabWidth(), 28)) {
                Page next = Page.values()[i];
                if (page != next) {
                    page = next;
                    buildControls();
                }
                return true;
            }
        }
        if (in(x, y, scrollBarX() - 5, viewportTop(), 14, viewportBottom() - viewportTop())
                && maxScroll() > 0) {
            draggingScrollbar = true;
            int thumbHeight = scrollbarThumbHeight();
            int currentY = viewportTop() + (int)Math.round(scrollDraw / maxScroll()
                    * (viewportBottom() - viewportTop() - thumbHeight));
            scrollbarGrab = (y >= currentY && y < currentY + thumbHeight)
                    ? y - currentY : thumbHeight / 2.0;
            dragScrollbar(y);
            return true;
        }
        if (y >= viewportTop() && y < viewportBottom()) {
            double contentY = y + scrollDraw;
            for (Control control : controls) {
                if (!in(x, contentY, control.x, control.y, control.w, control.h)) continue;
                if (control.kind == Kind.SLIDER) {
                    draggingSlider = true;
                    selectedSlider = control;
                    dragSlider(x);
                } else if (control.press != null) {
                    control.press.run();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScrollbar) {
            dragScrollbar(event.y());
            return true;
        }
        if (draggingSlider && selectedSlider != null) {
            dragSlider(event.x());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && (draggingScrollbar || draggingSlider)) {
            draggingScrollbar = false;
            draggingSlider = false;
            selectedSlider = null;
            ClientSettings.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    private void dragScrollbar(double pointerY) {
        int travel = viewportBottom() - viewportTop() - scrollbarThumbHeight();
        if (travel <= 0) return;
        scrollGoal = clamp((pointerY - scrollbarGrab - viewportTop()) / travel * maxScroll(),
                0, maxScroll());
        scrollDraw = scrollGoal; // direct response while dragging the scrollbar
    }

    private void dragSlider(double pointerX) {
        if (selectedSlider == null) return;
        Control c = selectedSlider;
        double fraction = clamp((pointerX - c.x - 18) / (c.w - 36.0), 0, 1);
        c.sliderChange.accept(fraction);
        c.progress = (float)fraction;
    }

    @Override
    public void onClose() {
        ClientSettings.save();
        minecraft.setScreen(null);
    }

    private static boolean in(double x, double y, int bx, int by, int bw, int bh) {
        return x >= bx && x < bx + bw && y >= by && y < by + bh;
    }
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    private static float approach(float a, float b, float t) {
        return a + (b - a) * Math.max(0f, Math.min(1f, t));
    }
    private static int blend(int a, int b, float portion) {
        float t = Math.max(0f, Math.min(1f, portion));
        int aa = (int)(((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        int rr = (int)(((a >>> 16) & 255) * (1 - t) + ((b >>> 16) & 255) * t);
        int gg = (int)(((a >>> 8) & 255) * (1 - t) + ((b >>> 8) & 255) * t);
        int bb = (int)((a & 255) * (1 - t) + (b & 255) * t);
        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }
    private static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w / 2, h / 2)));
        g.fill(x + r, y, x + w - r, y + h, color);
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            // Approximate rounded corners using horizontal strips; no extra dependencies.
            int inset = (int)Math.ceil(r - Math.sqrt(r * r - (r - i - 0.5) * (r - i - 0.5)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }
}
