package dev.donutvision;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** In-game settings, open with Right Shift / F10. */
public final class DonutVisionScreen extends Screen {
    private enum Page { MODULES, BLOCKS }

    private final Page page;
    private int left, top, panelWidth, panelHeight;

    public DonutVisionScreen() {
        this(Page.MODULES);
    }

    private DonutVisionScreen(Page page) {
        super(Component.literal("DonutVision"));
        this.page = page;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(360, width - 12);
        panelHeight = 304;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;

        int pad = 12;
        int innerWidth = panelWidth - 2 * pad;
        int gap = 6;
        int colWidth = (innerWidth - gap) / 2;

        button(left + pad, top + 30, colWidth, "Modules", b -> open(Page.MODULES));
        button(left + pad + colWidth + gap, top + 30, colWidth, "Block filters", b -> open(Page.BLOCKS));

        if (page == Page.MODULES) {
            button(left + pad, top + 60, innerWidth, freecamLabel(), b -> {
                Freecam.toggle(minecraft);
                b.setMessage(Component.literal(freecamLabel()));
            });
            button(left + pad, top + 87, innerWidth, espLabel(), b -> {
                DonutVisionClient.toggleEsp();
                b.setMessage(Component.literal(espLabel()));
            });
            button(left + pad, top + 114, innerWidth, playerLabel(), b -> {
                PlayerEsp.toggle();
                b.setMessage(Component.literal(playerLabel()));
            });
            button(left + pad, top + 141, innerWidth, blockTracerLabel(), b -> {
                DonutVisionClient.toggleBlockTracers();
                b.setMessage(Component.literal(blockTracerLabel()));
            });
            button(left + pad, top + 168, innerWidth, playerTracerLabel(), b -> {
                PlayerEsp.toggleTracers();
                b.setMessage(Component.literal(playerTracerLabel()));
            });
            addRenderableWidget(new BlockRangeSlider(left + pad, top + 195, innerWidth));
            addRenderableWidget(new PlayerRangeSlider(left + pad, top + 222, innerWidth));
            addRenderableWidget(new SpeedSlider(left + pad, top + 249, innerWidth));
        } else {
            BlockEsp.Filter[] filters = BlockEsp.Filter.values();
            for (int i = 0; i < filters.length; i++) {
                BlockEsp.Filter filter = filters[i];
                int x = left + pad + (i % 2) * (colWidth + gap);
                int y = top + 61 + (i / 2) * 27;
                button(x, y, colWidth, filterLabel(filter), b -> {
                    BlockEsp.toggleFilter(filter);
                    ClientSettings.save();
                    b.setMessage(Component.literal(filterLabel(filter)));
                });
            }
            button(left + pad, top + 196, innerWidth, modeLabel(), b -> {
                BlockEsp.nextMode();
                ClientSettings.save();
                b.setMessage(Component.literal(modeLabel()));
            });
        }

        button(left + pad, top + 278, innerWidth, "Close [ESC]", b -> onClose());
    }

    private void open(Page target) {
        if (target != page) minecraft.setScreen(new DonutVisionScreen(target));
    }

    private void button(int x, int y, int w, String label, Button.OnPress action) {
        addRenderableWidget(Button.builder(Component.literal(label), action).bounds(x, y, w, 20).build());
    }

    // Called by Screen before the buttons are extracted, keeping the panel behind the controls.
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        drawPanel(g);
    }

    private void drawPanel(GuiGraphicsExtractor g) {
        // Opaque navy-charcoal panel, thin blue border, brighter header and separator.
        g.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, 0xFF49729C);
        g.fill(left, top, left + panelWidth, top + panelHeight, 0xF31A2030);
        g.fill(left, top, left + panelWidth, top + 25, 0xFF111724);
        g.fill(left + 1, top + 25, left + panelWidth - 1, top + 26, 0xFF354B6B);
        g.text(font, "DONUTVISION", left + 12, top + 9, 0xFF9ED6FF, true);
        g.text(font, "v1.4  |  RSHIFT / F10", left + panelWidth - 125, top + 9, 0xFF869AB5, false);
        if (page == Page.BLOCKS) {
            g.text(font, "Only loaded client-side chunks can be scanned", left + 12, top + 255, 0xFF95A3B8, false);
        }
    }

    private static String freecamLabel() {
        return "Freecam    " + (Freecam.active() ? "ON" : "OFF");
    }

    private static String espLabel() {
        return "Block ESP    " + (DonutVisionClient.espEnabled() ? "ON" : "OFF");
    }

    private static String playerLabel() {
        return "Player ESP    " + (PlayerEsp.enabled() ? "ON" : "OFF");
    }

    private static String blockTracerLabel() {
        return "Block tracer lines    " + (DonutVisionClient.blockTracers() ? "ON" : "OFF");
    }

    private static String playerTracerLabel() {
        return "Player tracer lines    " + (PlayerEsp.tracers() ? "ON" : "OFF");
    }

    private static String modeLabel() {
        return "ESP category: " + BlockEsp.modeLabel();
    }

    private static String filterLabel(BlockEsp.Filter filter) {
        return filter.label() + "  " + (BlockEsp.filterEnabled(filter) ? "ON" : "OFF");
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
            setMessage(Component.literal("Block ESP range: " + BlockEsp.rangeLabel(range)));
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
            setMessage(Component.literal("Player ESP range: " + BlockEsp.rangeLabel(range)));
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

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format(java.util.Locale.ROOT,
                    "Freecam speed: %.2f", 0.2 + value * 1.3)));
        }

        @Override
        protected void applyValue() {
            Freecam.setSpeed(0.2 + value * 1.3);
            ClientSettings.save();
        }
    }
}
