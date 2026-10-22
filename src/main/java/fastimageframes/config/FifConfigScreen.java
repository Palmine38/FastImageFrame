package fastimageframes.config;

import fastimageframes.FifConfig;
import fastimageframes.hd.HdLod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;
import java.util.function.IntFunction;

public class FifConfigScreen extends Screen {

    private static final String KEY = "fastimageframes.config.";
    private static final int WIDTH = 200;
    private static final int SPACING = 4;

    private static final int[] MEMORY_MB = {64, 128, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384};
    private static final int[] UNLOAD_SECONDS = {5, 10, 15, 30, 45, 60, 90, 120, 180, 300, 600, 900, 1200, 1800, 3600};
    private static final int[] RAM_MB = {16, 32, 64, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096};
    private static final int[] DISK_MB = {32, 64, 128, 256, 512, 1024, 2048, 3072, 4096, 8192, 16384, 32768, 65536};
    private static final int[] REQUESTS = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64};
    private static final int[] DISTANCES = range(2, 64);
    private static final int[] DOWNGRADE_SECONDS = range(0, 30);

    private static final String[] RENDER_OPTIONS = {"mapAtlas", "atlasSize", "batchFrames", "optimizedFrameModel", "skipRedundantMapUpdates", "bakeFrames", "bakeItemFrames", "occlusionCulling"};
    private static final String[] LOD_OPTIONS = {"hdLod", "hdLodQuality", "hdLodCustomDistance", "hdLodCustomDowngradeSeconds", "hdLodMinResolution",
            "hdTextureMemoryMB", "hdUnloadUnusedAfterSeconds"};
    private static final String[] CACHE_OPTIONS = {"hdRamCacheMB", "hdDiskCache", "hdDiskCacheMaxMB", "hdTrustValidatedDiskCache", "hdMaxConcurrentRequests"};

    private enum Page {
        MAIN(null),
        RENDER(RENDER_OPTIONS),
        LOD(LOD_OPTIONS),
        CACHE(CACHE_OPTIONS);

        private final String[] options;

        Page(String[] options) {
            this.options = options;
        }
    }

    private final Screen parent;
    private final ConfigValues values;
    private Page page = Page.MAIN;
    private HeaderAndFooterLayout layout;

    public FifConfigScreen(Screen parent) {
        this(parent, null);
    }

    public FifConfigScreen(Screen parent, String page) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        this.values = ConfigValues.load();
        if (page != null) {
            this.page = Page.valueOf(page.toUpperCase(Locale.ROOT));
        }
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(page == Page.MAIN ? title : Component.translatable(KEY + "page." + pageName(page) + ".title"), font);

        GridLayout grid = new GridLayout().spacing(SPACING);
        GridLayout.RowHelper rows = grid.createRowHelper(2);
        switch (page) {
            case MAIN -> main(rows);
            case RENDER -> render(rows);
            case LOD -> lod(rows);
            case CACHE -> cache(rows);
        }
        layout.addToContents(grid);

        LinearLayout footer = LinearLayout.horizontal().spacing(8);
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> close()).width(120).build());
        footer.addChild(Button.builder(Component.translatable(KEY + "apply"), button -> values.apply()).width(120).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
            values.apply();
            close();
        }).width(120).build());
        layout.addToFooter(footer);

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        if (page != Page.MAIN) {
            open(Page.MAIN);
            return;
        }
        values.apply();
        close();
    }

    private void close() {
        //#if MC >= 26.2
        minecraft.gui.setScreen(parent);
        //#else
        minecraft.setScreen(parent);
        //#endif
    }

    private void open(Page next) {
        page = next;
        rebuildWidgets();
    }

    private void main(GridLayout.RowHelper rows) {
        rows.addChild(toggle("enabled", WIDTH * 2 + SPACING), 2);
        rows.addChild(pageButton(Page.RENDER));
        rows.addChild(pageButton(Page.LOD));
        rows.addChild(pageButton(Page.CACHE), 2);
        rows.addChild(button(Component.translatable(KEY + "resetAll"), KEY + "resetAll.tooltip", () -> {
            values.reset(RENDER_OPTIONS);
            values.reset(LOD_OPTIONS);
            values.reset(CACHE_OPTIONS);
            values.reset("enabled");
            rebuildWidgets();
        }), 2);
    }

    private void render(GridLayout.RowHelper rows) {
        navigation(rows);
        rows.addChild(toggle("mapAtlas", WIDTH));
        rows.addChild(cycle("atlasSize", FifConfig.AtlasSize.values(), values.bool("mapAtlas")));
        rows.addChild(toggle("batchFrames", WIDTH));
        rows.addChild(toggle("optimizedFrameModel", WIDTH));
        rows.addChild(toggle("skipRedundantMapUpdates", WIDTH));
        rows.addChild(activeIf(toggle("bakeFrames", WIDTH), values.bool("mapAtlas")));
        rows.addChild(activeIf(toggle("bakeItemFrames", WIDTH), values.bool("mapAtlas") && values.bool("bakeFrames")));
        rows.addChild(toggle("occlusionCulling", WIDTH));
    }

    private void lod(GridLayout.RowHelper rows) {
        navigation(rows);
        boolean lod = values.bool("hdLod");
        FifConfig.LodQuality quality = values.enumValue("hdLodQuality");
        boolean custom = quality == FifConfig.LodQuality.CUSTOM;
        rows.addChild(toggle("hdLod", WIDTH));
        rows.addChild(activeIf(presetButton(quality), lod));
        if (!custom) {
            values.set("hdLodCustomDistance", (int) Math.round(Math.max(2, Math.min(64, lowQualityDistance(quality)))));
        }
        rows.addChild(activeIf(slider("hdLodCustomDistance", DISTANCES, value -> Component.translatable(KEY + "unit.blocks", value)), lod && custom));
        rows.addChild(activeIf(slider("hdLodCustomDowngradeSeconds", DOWNGRADE_SECONDS, FifConfigScreen::duration), lod && custom));
        rows.addChild(activeIf(cycle("hdLodMinResolution", FifConfig.MinResolution.values(), true), lod));
        rows.addChild(activeIf(slider("hdTextureMemoryMB", MEMORY_MB, FifConfigScreen::megabytes), lod));
        rows.addChild(activeIf(slider("hdUnloadUnusedAfterSeconds", UNLOAD_SECONDS, FifConfigScreen::duration), lod));
    }

    private void cache(GridLayout.RowHelper rows) {
        navigation(rows);
        rows.addChild(slider("hdRamCacheMB", RAM_MB, FifConfigScreen::megabytes));
        rows.addChild(slider("hdMaxConcurrentRequests", REQUESTS, value -> Component.literal(Integer.toString(value))));
        rows.addChild(toggle("hdDiskCache", WIDTH));
        rows.addChild(activeIf(slider("hdDiskCacheMaxMB", DISK_MB, FifConfigScreen::megabytes), values.bool("hdDiskCache")));
        rows.addChild(activeIf(toggle("hdTrustValidatedDiskCache", WIDTH), values.bool("hdDiskCache")), 2);
    }

    private void navigation(GridLayout.RowHelper rows) {
        rows.addChild(button(Component.translatable(KEY + "back"), null, () -> open(Page.MAIN)));
        rows.addChild(button(Component.translatable(KEY + "resetPage"), KEY + "resetPage.tooltip", () -> {
            values.reset(page.options);
            rebuildWidgets();
        }));
    }

    private Button pageButton(Page target) {
        String key = KEY + "page." + pageName(target);
        return button(Component.translatable(key), key + ".tooltip", () -> open(target));
    }

    private Button button(Component message, String tooltip, Runnable action) {
        Button.Builder builder = Button.builder(message, button -> action.run()).width(WIDTH);
        if (tooltip != null) {
            builder.tooltip(Tooltip.create(Component.translatable(tooltip)));
        }
        return builder.build();
    }

    private Button toggle(String option, int width) {
        Button button = Button.builder(toggleMessage(option), b -> {
            values.set(option, !values.bool(option));
            rebuildWidgets();
        }).width(width).build();
        button.setTooltip(Tooltip.create(Component.translatable(KEY + option + ".tooltip")));
        return button;
    }

    private Component toggleMessage(String option) {
        boolean on = values.bool(option);
        Component value = Component.translatable(KEY + (on ? "yes" : "no")).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
        return named(option, value);
    }

    private <E extends Enum<E>> Button cycle(String option, E[] choices, boolean active) {
        E current = values.enumValue(option);
        Button button = Button.builder(named(option, enumName(current)), b -> {
            E value = values.enumValue(option);
            values.set(option, choices[(value.ordinal() + 1) % choices.length]);
            rebuildWidgets();
        }).width(WIDTH).build();
        button.setTooltip(Tooltip.create(Component.translatable(KEY + option + ".tooltip")));
        button.active = active;
        return button;
    }

    private Button presetButton(FifConfig.LodQuality quality) {
        FifConfig.LodQuality[] choices = FifConfig.LodQuality.values();
        Button button = Button.builder(named("hdLodQuality", enumName(quality)), b -> {
            FifConfig.LodQuality next = choices[(quality.ordinal() + 1) % choices.length];
            if (next == FifConfig.LodQuality.CUSTOM) {
                values.set("hdLodCustomDistance", (int) Math.round(Math.max(2, Math.min(64, lowQualityDistance(quality)))));
            }
            values.set("hdLodQuality", next);
            rebuildWidgets();
        }).width(WIDTH).build();
        String distance = String.format(Locale.ROOT, "%.0f", lowQualityDistance(quality));
        button.setTooltip(Tooltip.create(Component.translatable(KEY + "hdLodQuality.tooltip", distance)));
        return button;
    }

    private double lowQualityDistance(FifConfig.LodQuality quality) {
        return HdLod.lowQualityDistance(minecraft, quality, values.integer("hdLodCustomDistance"));
    }

    private AbstractWidget slider(String option, int[] steps, IntFunction<Component> format) {
        return new StepSlider(option, steps, format);
    }

    private static <T extends AbstractWidget> T activeIf(T widget, boolean active) {
        widget.active = widget.active && active;
        return widget;
    }

    private static MutableComponent named(String option, Component value) {
        return Component.translatable(KEY + "value", Component.translatable(KEY + option), value);
    }

    private static Component enumName(Enum<?> value) {
        return Component.translatable(KEY + "enum." + value.getDeclaringClass().getSimpleName() + "." + value.name());
    }

    private static String pageName(Page page) {
        return page.name().toLowerCase(Locale.ROOT);
    }

    private static Component megabytes(int value) {
        if (value >= 1024) {
            double gigabytes = value / 1024.0;
            String text = gigabytes == Math.floor(gigabytes) ? Integer.toString((int) gigabytes) : String.format(Locale.ROOT, "%.1f", gigabytes);
            return Component.translatable(KEY + "unit.gb", text);
        }
        return Component.translatable(KEY + "unit.mb", value);
    }

    private static Component duration(int seconds) {
        if (seconds >= 3600 && seconds % 3600 == 0) {
            return Component.translatable(KEY + "unit.hours", seconds / 3600);
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            return Component.translatable(KEY + "unit.minutes", seconds / 60);
        }
        if (seconds > 60) {
            return Component.translatable(KEY + "unit.minutesSeconds", seconds / 60, seconds % 60);
        }
        return Component.translatable(KEY + "unit.seconds", seconds);
    }

    private static int[] range(int from, int to) {
        int[] values = new int[to - from + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = from + i;
        }
        return values;
    }

    private static int nearest(int[] steps, int value) {
        int best = 0;
        for (int i = 1; i < steps.length; i++) {
            if (Math.abs(steps[i] - value) < Math.abs(steps[best] - value)) {
                best = i;
            }
        }
        return best;
    }

    private final class StepSlider extends AbstractSliderButton {

        private final String option;
        private final int[] steps;
        private final IntFunction<Component> format;

        StepSlider(String option, int[] steps, IntFunction<Component> format) {
            super(0, 0, WIDTH, 20, Component.empty(), nearest(steps, values.integer(option)) / (double) (steps.length - 1));
            this.option = option;
            this.steps = steps;
            this.format = format;
            setTooltip(Tooltip.create(Component.translatable(KEY + option + ".tooltip")));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(named(option, format.apply(values.integer(option))));
        }

        @Override
        protected void applyValue() {
            values.set(option, steps[(int) Math.round(value * (steps.length - 1))]);
        }
    }

}
