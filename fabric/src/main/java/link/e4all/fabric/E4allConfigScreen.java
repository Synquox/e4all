package link.e4all.fabric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import link.e4all.Config;
import link.e4all.E4allClient;
import link.e4all.Mirror;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class E4allConfigScreen extends Screen {
    private static final int PAGE_BASIC = 0;
    private static final int PAGE_ADVANCED = 1;

    private static final int COL_WIDTH = 152;
    private static final int BUTTON_HEIGHT = 20;
    private static final int FIELD_BLOCK_HEIGHT = 42;

    private static final String[] E4ALL_ADD_WIDGET_METHODS = {
            "addRenderableWidget", "method_37056", "m_142464_",
            "addWidget",
            "addButton", "method_25794", "func_230480_a_"
    };

    private static final String DISCORD_URL = "https://discord.gg/McAy9u56NC";
    private static final String GITHUB_URL = "https://github.com/Synquox/e4all";

    private final Screen parent;
    private final Map<String, String> pendingEdits = new HashMap<>();
    private final List<int[]> hoverAreas = new ArrayList<>();
    private final List<String> hoverKeys = new ArrayList<>();
    private boolean tooltipsAvailable;

    private int currentPage = PAGE_BASIC;

    public E4allConfigScreen(Screen parent) {
        super(Mirror.translatable("text.e4all_minecraft.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        hoverAreas.clear();
        hoverKeys.clear();
        int centerX = this.width / 2;
        int leftCol = centerX - COL_WIDTH - 5;
        int rightCol = centerX + 5;

        Button basicTab = makeButton(Mirror.translatable("text.e4all_minecraft.config.page.basic"),
                leftCol, 30, COL_WIDTH, BUTTON_HEIGHT, b -> switchPage(PAGE_BASIC));
        Button advancedTab = makeButton(Mirror.translatable("text.e4all_minecraft.config.page.advanced"),
                rightCol, 30, COL_WIDTH, BUTTON_HEIGHT, b -> switchPage(PAGE_ADVANCED));
        basicTab.active = currentPage != PAGE_BASIC;
        advancedTab.active = currentPage != PAGE_ADVANCED;
        applyTooltip(basicTab, Mirror.translatable("text.e4all_minecraft.config.page.basic.tooltip"));
        applyTooltip(advancedTab, Mirror.translatable("text.e4all_minecraft.config.page.advanced.tooltip"));
        e4all$addWidget(basicTab);
        e4all$addWidget(advancedTab);

        if (currentPage == PAGE_BASIC) {
            addToggle("hostEnabled", leftCol, 60, () -> Config.INSTANCE.hostEnabled.value(),
                    v -> Config.INSTANCE.hostEnabled.setValue(v, true));
            addToggle("restoreDedicatedCommands", rightCol, 60, () -> Config.INSTANCE.restoreDedicatedCommands.value(),
                    v -> Config.INSTANCE.restoreDedicatedCommands.setValue(v, true));
            addToggle("useWhiteList", leftCol, 84, () -> Config.INSTANCE.useWhiteList.value(),
                    v -> Config.INSTANCE.useWhiteList.setValue(v, true));
            addToggle("dialtoneHostEnabled", rightCol, 84, () -> Config.INSTANCE.dialtoneHostEnabled.value(),
                    v -> Config.INSTANCE.dialtoneHostEnabled.setValue(v, true));
            addToggle("dialtonePlayerEnabled", leftCol, 108, () -> Config.INSTANCE.dialtonePlayerEnabled.value(),
                    v -> Config.INSTANCE.dialtonePlayerEnabled.setValue(v, true));
            addToggle("dialtoneSanitizeTicket", rightCol, 108, () -> Config.INSTANCE.dialtoneSanitizeTicket.value(),
                    v -> Config.INSTANCE.dialtoneSanitizeTicket.setValue(v, true));
            addToggle("hideDomainInChat", leftCol, 132, () -> Config.INSTANCE.hideDomainInChat.value(),
                    v -> Config.INSTANCE.hideDomainInChat.setValue(v, true));
            addToggle("offlineMode", rightCol, 132, () -> Config.INSTANCE.offlineMode.value(),
                    v -> Config.INSTANCE.offlineMode.setValue(v, true));
        } else {
            addToggle("useBroker", leftCol, 86, () -> Config.INSTANCE.useBroker.value(),
                    v -> Config.INSTANCE.useBroker.setValue(v, true));

            addTextField("relayPort", rightCol, 86, COL_WIDTH, String.valueOf(Config.INSTANCE.relayPort.value()),
                    s -> s.matches("\\d*"));
            addTextField("brokerUrl", leftCol, 128, COL_WIDTH, Config.INSTANCE.brokerUrl.value(), s -> true);
            addTextField("relayHost", rightCol, 128, COL_WIDTH, Config.INSTANCE.relayHost.value(), s -> true);
            addTextField("dialtoneRelayMap", leftCol, 128 + FIELD_BLOCK_HEIGHT, COL_WIDTH * 2 + 10,
                    Config.INSTANCE.dialtoneRelayMap.value(), s -> true);
        }

        int footerY = this.height - 25;
        int footerX = centerX - 154;
        Button discordBtn = makeButton(Mirror.translatable("text.e4all_minecraft.config.discord"),
                footerX, footerY, 100, BUTTON_HEIGHT, b -> openUrl(DISCORD_URL));
        Button githubBtn = makeButton(Mirror.translatable("text.e4all_minecraft.config.github"),
                footerX + 108, footerY, 100, BUTTON_HEIGHT, b -> openUrl(GITHUB_URL));
        Button doneBtn = makeButton(Mirror.translatable("text.e4all_minecraft.config.done"),
                footerX + 216, footerY, 92, BUTTON_HEIGHT, b -> onClose());
        applyTooltip(discordBtn, Mirror.translatable("text.e4all_minecraft.config.discord.tooltip"));
        applyTooltip(githubBtn, Mirror.translatable("text.e4all_minecraft.config.github.tooltip"));
        applyTooltip(doneBtn, Mirror.translatable("text.e4all_minecraft.config.done.tooltip"));
        e4all$addWidget(discordBtn);
        e4all$addWidget(githubBtn);
        e4all$addWidget(doneBtn);
    }

    private void switchPage(int page) {
        if (page == currentPage) {
            return;
        }
        commitPendingEdits();
        currentPage = page;
        try {
            clearWidgets();
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all: could not clear config screen widgets", t);
            return;
        }
        init();
    }

    private Button makeButton(Component label, int x, int y, int width, int height, Button.OnPress onPress) {
        try {
            return Button.builder(label, onPress).bounds(x, y, width, height).build();
        } catch (Throwable ignored) {
        }
        try {
            Constructor<Button> legacy = Button.class.getConstructor(
                    Component.class, int.class, int.class, int.class, int.class, Button.OnPress.class);
            return legacy.newInstance(label, x, y, width, height, onPress);
        } catch (Throwable t) {
            throw new IllegalStateException("e4all: unable to construct Button on this MC version", t);
        }
    }

    private void addToggle(String key, int x, int y, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        boolean initial = getter.get();
        Button button = makeButton(toggleLabel(key, initial), x, y, COL_WIDTH, BUTTON_HEIGHT, b -> {
            boolean newValue = !getter.get();
            setter.accept(newValue);
            try {
                b.setMessage(toggleLabel(key, getter.get()));
            } catch (Throwable ignored) {
            }
        });
        applyOptionTooltip(button, key);
        e4all$addWidget(button);
        registerHoverArea(key, x, y, COL_WIDTH, BUTTON_HEIGHT);
    }

    private static Component toggleLabel(String key, boolean value) {
        Component state = Mirror.translatable(value ? "options.on" : "options.off");
        return Mirror.append(Mirror.translatable(optionKey(key)), Mirror.append(Mirror.literal(": "), state));
    }

    private void addTextField(String key, int x, int y, int width, String currentValue,
            Predicate<String> filter) {
        Component label;
        Button labelButton;
        EditBox box;
        try {
            label = Mirror.translatable(optionKey(key));
            labelButton = makeButton(label, x, y, width, 14, b -> {
            });
            box = new EditBox(this.font, x, y + 16, width, BUTTON_HEIGHT, label);
            box.setMaxLength(256);
            box.setValue(currentValue == null ? "" : currentValue);
            box.setFilter(filter);
            box.setResponder(text -> pendingEdits.put(key, text));
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all: could not create config text field '{}' on this MC version", key, t);
            return;
        }
        labelButton.active = false;
        applyOptionTooltip(box, key);
        e4all$addWidget(labelButton);
        e4all$addWidget(box);
        registerHoverArea(key, x, y, width, 16 + BUTTON_HEIGHT);
    }

    private static String optionKey(String key) {
        return "text.e4all_minecraft.config.option." + key;
    }

    private void commitPendingEdits() {
        if (pendingEdits.isEmpty()) {
            return;
        }
        String brokerUrl = pendingEdits.remove("brokerUrl");
        if (brokerUrl != null) {
            Config.INSTANCE.brokerUrl.setValue(brokerUrl.trim(), true);
        }
        String relayHost = pendingEdits.remove("relayHost");
        if (relayHost != null) {
            Config.INSTANCE.relayHost.setValue(relayHost.trim(), true);
        }
        String relayMap = pendingEdits.remove("dialtoneRelayMap");
        if (relayMap != null) {
            Config.INSTANCE.dialtoneRelayMap.setValue(relayMap.trim(), true);
        }
        String port = pendingEdits.remove("relayPort");
        if (port != null) {
            try {
                int value = Integer.parseInt(port.trim());
                if (value < 1 || value > 65535) {
                    throw new NumberFormatException();
                }
                Config.INSTANCE.relayPort.setValue(value, true);
            } catch (NumberFormatException e) {
                E4allClient.LOGGER.warn("e4all: invalid relay port '{}', keeping current value", port);
            }
        }
    }

    private void openUrl(String url) {
        try {
            Util.getPlatform().openUri(url);
            return;
        } catch (Throwable ignored) {
        }
        try {
            for (Method m : Util.class.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 1) continue;
                String name = m.getName();
                if (!name.equals("openUri") && !name.equals("openUrl") && !name.equals("openLink")) continue;
                Class<?> paramType = m.getParameterTypes()[0];
                if (paramType == String.class) {
                    m.invoke(null, url);
                    return;
                } else if (paramType == URI.class) {
                    m.invoke(null, new URI(url));
                    return;
                } else if (paramType == java.net.URL.class) {
                    m.invoke(null, new java.net.URL(url));
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
            return;
        } catch (Throwable ignored) {
        }
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all: failed to open link {}", url, t);
        }
    }

    private <W extends GuiEventListener & Renderable & NarratableEntry> W e4all$addWidget(W widget) {
        try {
            W result = this.addRenderableWidget(widget);
            try {
                if (!this.children().contains(widget)) {
                    this.addWidget(widget);
                }
            } catch (Throwable ignored) {}
            return result;
        } catch (Throwable ignored) {
        }
        for (Class<?> c = Screen.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!isAddWidgetCandidate(method, widget)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(this, widget);
                    return widget;
                } catch (Throwable ignored) {
                }
            }
        }
        E4allClient.LOGGER.warn("e4all: could not register config screen widget {}",
                widget.getClass().getName());
        return widget;
    }

    private static boolean isAddWidgetCandidate(Method method, Object widget) {
        boolean nameMatches = false;
        for (String candidate : E4ALL_ADD_WIDGET_METHODS) {
            if (method.getName().equals(candidate)) {
                nameMatches = true;
                break;
            }
        }
        if (!nameMatches) {
            return false;
        }
        if (method.getParameterCount() != 1 || !method.getParameterTypes()[0].isInstance(widget)) {
            return false;
        }
        return !method.getReturnType().equals(void.class);
    }

    private void registerHoverArea(String key, int x, int y, int width, int height) {
        hoverAreas.add(new int[]{x, y, width, height});
        hoverKeys.add(key);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        try {
            renderBackdrop(graphics, mouseX, mouseY, delta);
            graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
            if (currentPage == PAGE_ADVANCED) {
                drawWrapped(graphics, Mirror.translatable("text.e4all_minecraft.config.advancedWarning"),
                        this.width / 2 - 155, 54, 310, 3, 0xFFFFAA00);
            }
        } catch (Throwable ignored) {
        }
        super.render(graphics, mouseX, mouseY, delta);
        try {
            drawHoverHint(graphics, mouseX, mouseY);
        } catch (Throwable ignored) {
        }
    }

    private void renderBackdrop(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        try {
            this.renderBackground(graphics, mouseX, mouseY, delta);
            return;
        } catch (Throwable ignored) {
        }
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }

    private void drawWrapped(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int maxLines,
            int color) {
        var lines = this.font.split(text, maxWidth);
        int count = Math.min(lines.size(), maxLines);
        for (int i = 0; i < count; i++) {
            graphics.drawString(this.font, lines.get(i), x, y + i * 10, color);
        }
    }

    private void drawHoverHint(GuiGraphics graphics, int mouseX, int mouseY) {
        if (tooltipsAvailable) {
            return;
        }
        int hoveredIndex = -1;
        for (int i = 0; i < hoverAreas.size(); i++) {
            int[] area = hoverAreas.get(i);
            if (mouseX >= area[0] && mouseX < area[0] + area[2] && mouseY >= area[1] && mouseY < area[1] + area[3]) {
                hoveredIndex = i;
                break;
            }
        }
        if (hoveredIndex < 0) {
            return;
        }
        String key = hoverKeys.get(hoveredIndex);
        int panelWidth = 320;
        int panelHeight = 42;
        int panelX = this.width / 2 - panelWidth / 2;
        int panelY = this.height - 72;
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xF0101010);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xFF555555);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0xFF555555);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0xFF555555);
        graphics.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, 0xFF555555);
        graphics.drawString(this.font, Mirror.translatable(optionKey(key)), panelX + 6, panelY + 4, 0xFFFFFFA0);
        drawWrapped(graphics, Mirror.translatable(key + ".tooltip"), panelX + 6, panelY + 15,
                panelWidth - 12, 3, 0xFFC8C8C8);
    }

    private void applyOptionTooltip(AbstractWidget widget, String key) {
        applyTooltip(widget, Mirror.translatable(optionKey(key) + ".tooltip"));
    }

    private void applyTooltip(AbstractWidget widget, Component text) {
        try {
            widget.setTooltip(Tooltip.create(text));
            tooltipsAvailable = true;
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void setFocused(GuiEventListener listener) {
        GuiEventListener previous = this.getFocused();
        if (previous instanceof EditBox && listener != previous) {
            commitPendingEdits();
        }
        super.setFocused(listener);
    }

    @Override
    public void onClose() {
        commitPendingEdits();
        closeScreen();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            closeScreen();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void closeScreen() {
        try {
            this.minecraft.setScreen(parent);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Object mc = this.minecraft;
            if (mc == null) {
                for (Class<?> c = Screen.class; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getType() == net.minecraft.client.Minecraft.class) {
                            f.setAccessible(true);
                            mc = f.get(this);
                            break;
                        }
                    }
                    if (mc != null) break;
                }
            }
            if (mc != null) {
                String[] names = {"setScreen", "method_1507", "m_91152_"};
                Object[] targets = {mc};
                try {
                    Field guiField = mc.getClass().getField("gui");
                    Object gui = guiField.get(mc);
                    if (gui != null) targets = new Object[]{gui, mc};
                } catch (Throwable ignored) {}
                for (Object target : targets) {
                    for (String name : names) {
                        for (Method m : target.getClass().getMethods()) {
                            if (!m.getName().equals(name)) continue;
                            if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(Screen.class)
                                    && m.getReturnType() == void.class) {
                                m.invoke(target, parent);
                                return;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            this.minecraft.setScreen(null);
        } catch (Throwable t) {
            E4allClient.LOGGER.warn("e4all: could not close config screen", t);
        }
    }

    @Override
    public void removed() {
        commitPendingEdits();
        super.removed();
    }
}
