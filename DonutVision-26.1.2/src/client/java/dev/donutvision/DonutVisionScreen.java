package dev.donutvision;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Compact four-tab configuration menu. Uses standard Minecraft widgets for input compatibility. */
public final class DonutVisionScreen extends Screen {
    private enum Page { MODULES, FILTERS, SETTINGS, COLORS }
    private static final int BG = 0xF50F1421;
    private static final int INSET = 0xFF1B2535;
    private static final int BORDER = 0xFF31465E;
    private static final int LIGHT = 0xFFDAECF9;
    private static final int MUTED = 0xFF90A6BC;
    private static final int ACCENT = 0xFF5FD7FB;
    private static final int ACTIVE = 0xFF6AEDB5;
    private static final int INACTIVE = 0xFF7E899B;

    private final Page page;
    private int left, top, panelWidth, panelHeight;
    private int pad, gap, colWidth, innerWidth;

    public DonutVisionScreen() { this(Page.MODULES); }

    private DonutVisionScreen(Page page) {
        super(Component.literal("DonutVision"));
        this.page = page;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(448, width - 12);
        panelHeight = Math.min(340, height - 8);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        pad = 16;
        gap = 10;
        innerWidth = panelWidth - 2 * pad;
        colWidth = (innerWidth - gap) / 2;

        // Four compact navigation tabs.
        int tabGap = 5;
        int tabWidth = (innerWidth - tabGap * 3) / 4;
        Page[] pages = Page.values();
        for (int i = 0; i < pages.length; i++) {
            Page next = pages[i];
            int tx = left + pad + i * (tabWidth + tabGap);
            addButton(tx, top + 46, tabWidth, 20,
                    (next == page ? "[" : "") + label(next) + (next == page ? "]" : ""),
                    b -> switchTo(next));
        }

        switch (page) {
            case MODULES -> initModules();
            case FILTERS -> initFilters();
            case SETTINGS -> initSettings();
            case COLORS -> { /* Palette is an illustrated reference, no controls needed. */ }
        }
        addButton(left + pad, top + panelHeight - 30, innerWidth, 20,
                "Done   [ESC]", b -> onClose());
    }

    private void initModules() {
        toggle(left + pad, top + 107, colWidth, "Freecam", Freecam.active(), b -> {
            Freecam.toggle(minecraft);
            refresh();
        });
        toggle(left + pad + colWidth + gap, top + 107, colWidth, "Block ESP", DonutVisionClient.espEnabled(), b -> {
            DonutVisionClient.toggleEsp();
            refresh();
        });
        toggle(left + pad, top + 135, colWidth, "Spawner ESP", DonutVisionClient.spawnerEspEnabled(), b -> {
            DonutVisionClient.toggleSpawnerEsp();
            refresh();
        });
        toggle(left + pad + colWidth + gap, top + 135, colWidth, "Player ESP", PlayerEsp.enabled(), b -> {
            PlayerEsp.toggle();
            refresh();
        });
        toggle(left + pad, top + 163, colWidth, "Block tracers", DonutVisionClient.blockTracers(), b -> {
            DonutVisionClient.toggleBlockTracers();
            refresh();
        });
        toggle(left + pad + colWidth + gap, top + 163, colWidth, "Player tracers", PlayerEsp.tracers(), b -> {
            PlayerEsp.toggleTracers();
            refresh();
        });
    }

    private void initFilters() {
        BlockEsp.Filter[] filters = BlockEsp.Filter.values();
        for (int i = 0; i < filters.length; i++) {
            BlockEsp.Filter filter = filters[i];
            int x = left + pad + (i % 2) * (colWidth + gap);
            int y = top + 98 + (i / 2) * 25;
            toggle(x, y, colWidth, filter.label(), BlockEsp.filterEnabled(filter), b -> {
                BlockEsp.toggleFilter(filter);
                ClientSettings.save();
                refresh();
            });
        }
        addButton(left + pad, top + 255, innerWidth, 20,
                "Mode: " + BlockEsp.modeLabel() + "   >", b -> {
                    BlockEsp.nextMode();
                    ClientSettings.save();
                    refresh();
                });
    }

    private void initSettings() {
        addRenderableWidget(new BlockRangeSlider(left + pad, top + 117, innerWidth));
        addRenderableWidget(new PlayerRangeSlider(left + pad, top + 164, innerWidth));
        addRenderableWidget(new SpeedSlider(left + pad, top + 211, innerWidth));
    }

    private void toggle(int x, int y, int w, String title, boolean on, Button.OnPress action) {
        addButton(x, y, w, 20, title + "   " + (on ? "ON" : "OFF"), action);
    }

    private void addButton(int x, int y, int w, int h, String text, Button.OnPress action) {
        addRenderableWidget(Button.builder(Component.literal(text), action).bounds(x, y, w, h).build());
    }

    private void refresh() { minecraft.setScreen(new DonutVisionScreen(page)); }
    private void switchTo(Page target) {
        if (target != page) minecraft.setScreen(new DonutVisionScreen(target));
    }

    private static String label(Page page) {
        return switch (page) {
            case MODULES -> "Modules";
            case FILTERS -> "Filters";
            case SETTINGS -> "Settings";
            case COLORS -> "Colors";
        };
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        drawPanel(g);
    }

    private void drawPanel(GuiGraphicsExtractor g) {
        // A cleaner low-contrast background, cyan top accent and subtle card panels.
        g.fill(left - 2, top - 2, left + panelWidth + 2, top + panelHeight + 2, BORDER);
        g.fill(left, top, left + panelWidth, top + panelHeight, BG);
        g.fill(left, top, left + panelWidth, top + 37, 0xFF172235);
        g.fill(left, top, left + panelWidth, top + 3, ACCENT);
        g.text(font, "DONUT", left + pad, top + 15, LIGHT, true);
        g.text(font, "VISION", left + pad + 36, top + 15, ACCENT, true);
        g.text(font, "v1.6.0  |  RSHIFT / F10", left + panelWidth - 148, top + 16, MUTED, false);
        g.fill(left + pad, top + 74, left + panelWidth - pad, top + 75, BORDER);

        switch (page) {
            case MODULES -> drawModules(g);
            case FILTERS -> drawFilters(g);
            case SETTINGS -> drawSettings(g);
            case COLORS -> drawColors(g);
        }
        g.fill(left + pad, top + panelHeight - 37, left + panelWidth - pad, top + panelHeight - 36, BORDER);
    }

    private void heading(GuiGraphicsExtractor g, String title, String subtitle) {
        g.text(font, title, left + pad, top + 85, LIGHT, true);
        if (subtitle != null) {
            g.text(font, subtitle, left + pad, top + 96, MUTED, false);
        }
    }

    private void drawModules(GuiGraphicsExtractor g) {
        g.text(font, "MODULE SWITCHES", left + pad, top + 86, LIGHT, true);
        // Each indicator aligns with the button above it and represents its real state.
        boolean[] states = {Freecam.active(), DonutVisionClient.espEnabled(),
                DonutVisionClient.spawnerEspEnabled(), PlayerEsp.enabled(),
                DonutVisionClient.blockTracers(), PlayerEsp.tracers()};
        for (int i = 0; i < states.length; i++) {
            int x = left + pad + (i % 2) * (colWidth + gap);
            int y = top + 107 + (i / 2) * 28;
            g.fill(x - 3, y + 2, x - 1, y + 18, states[i] ? ACTIVE : INACTIVE);
        }
        g.fill(left + pad, top + 200, left + panelWidth - pad, top + 265, INSET);
        g.text(font, "ACTIVE TARGETS", left + pad + 10, top + 212, MUTED, false);
        g.text(font, Integer.toString(BlockEsp.targets().size()), left + pad + 10, top + 234, ACCENT, true);
        g.text(font, "blocks", left + pad + 41, top + 234, LIGHT, false);
        g.text(font, "Loaded chunks only", left + pad + 10, top + 249, MUTED, false);
        g.text(font, "TIP  Open Filters to choose storage + redstone", left + pad, top + 280, MUTED, false);
    }

    private void drawFilters(GuiGraphicsExtractor g) {
        g.text(font, "SELECT BLOCKS", left + pad, top + 84, LIGHT, true);
        BlockEsp.Filter[] filters = BlockEsp.Filter.values();
        for (int i = 0; i < filters.length; i++) {
            int x = left + pad + (i % 2) * (colWidth + gap);
            int y = top + 98 + (i / 2) * 25;
            g.fill(x - 3, y + 2, x - 1, y + 18,
                    BlockEsp.filterEnabled(filters[i]) ? ACTIVE : INACTIVE);
        }
        g.text(font, "Mode cycles: ores / storage / both / storage + redstone / all",
                left + pad, top + 282, MUTED, false);
    }

    private void drawSettings(GuiGraphicsExtractor g) {
        g.text(font, "MOVEMENT & SCAN RANGE", left + pad, top + 86, LIGHT, true);
        g.text(font, "BLOCK ESP DISTANCE", left + pad, top + 105, MUTED, false);
        g.text(font, "PLAYER ESP DISTANCE", left + pad, top + 152, MUTED, false);
        g.text(font, "FREECAM FLY SPEED", left + pad, top + 199, MUTED, false);
        g.fill(left + pad, top + 245, left + panelWidth - pad, top + 278, INSET);
        g.text(font, "Infinity scans client-loaded chunks only.", left + pad + 10, top + 255, LIGHT, false);
        g.text(font, "Changes save automatically.", left + pad, top + 290, MUTED, false);
    }

    private void drawColors(GuiGraphicsExtractor g) {
        g.text(font, "ESP COLOR LEGEND", left + pad, top + 86, LIGHT, true);
        legend(g, 0, 0, "Chest", 0xFFE8CBA5);
        legend(g, 1, 0, "Trapped chest", 0xFFE5A985);
        legend(g, 0, 1, "Shulker box", 0xFFFF8CCF);
        legend(g, 1, 1, "Hopper", 0xFF9AA4B2);
        legend(g, 0, 2, "Barrel", 0xFFCB9B6C);
        legend(g, 1, 2, "Ender chest", 0xFFA996FF);
        legend(g, 0, 3, "Redstone block", 0xFFFF485F);
        legend(g, 1, 3, "Dispenser / dropper", 0xFF86A8C0);
        legend(g, 0, 4, "Furnace", 0xFF80A5A2);
        legend(g, 1, 4, "Diamond", 0xFF35DEFF);
        legend(g, 0, 5, "Spawner", 0xFFFF4A56);
        legend(g, 1, 5, "Trial spawner", 0xFFFFBD43);
        legend(g, 0, 6, "Ancient debris", 0xFFFF943E);
        legend(g, 1, 6, "Gold ore", 0xFFFFD46B);
        g.text(font, "Lines and outlines share each block's color.", left + pad, top + 287, MUTED, false);
    }

    private void legend(GuiGraphicsExtractor g, int column, int row, String name, int color) {
        int x = left + pad + column * (colWidth + gap);
        int y = top + 108 + row * 23;
        g.fill(x, y, x + colWidth, y + 20, INSET);
        g.fill(x + 7, y + 4, x + 19, y + 16, color);
        g.text(font, name, x + 26, y + 6, LIGHT, false);
    }

    @Override
    public void onClose() {
        ClientSettings.save();
        minecraft.setScreen(null);
    }

    private static final class BlockRangeSlider extends AbstractSliderButton {
        BlockRangeSlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(),
                    BlockEsp.stepIndex(BlockEsp.radius()) / (double)(BlockEsp.RANGE_STEPS.length - 1));
            updateMessage();
        }
        @Override protected void updateMessage() {
            int range = BlockEsp.stepValue((int)Math.round(value * (BlockEsp.RANGE_STEPS.length - 1)));
            setMessage(Component.literal("Block ESP: " + BlockEsp.rangeLabel(range)));
        }
        @Override protected void applyValue() {
            BlockEsp.setRadius(BlockEsp.stepValue((int)Math.round(value * (BlockEsp.RANGE_STEPS.length - 1))));
            ClientSettings.save();
        }
    }

    private static final class PlayerRangeSlider extends AbstractSliderButton {
        PlayerRangeSlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(),
                    BlockEsp.stepIndex(PlayerEsp.radius()) / (double)(BlockEsp.RANGE_STEPS.length - 1));
            updateMessage();
        }
        @Override protected void updateMessage() {
            int range = BlockEsp.stepValue((int)Math.round(value * (BlockEsp.RANGE_STEPS.length - 1)));
            setMessage(Component.literal("Player ESP: " + BlockEsp.rangeLabel(range)));
        }
        @Override protected void applyValue() {
            PlayerEsp.setRadius(BlockEsp.stepValue((int)Math.round(value * (BlockEsp.RANGE_STEPS.length - 1))));
            ClientSettings.save();
        }
    }

    private static final class SpeedSlider extends AbstractSliderButton {
        SpeedSlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), (Freecam.speed() - 0.2) / 1.3);
            updateMessage();
        }
        @Override protected void updateMessage() {
            setMessage(Component.literal(String.format(Locale.ROOT,
                    "Freecam speed: %.2f", 0.2 + value * 1.3)));
        }
        @Override protected void applyValue() {
            Freecam.setSpeed(0.2 + value * 1.3);
            ClientSettings.save();
        }
    }
}
