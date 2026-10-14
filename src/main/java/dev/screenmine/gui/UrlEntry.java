package dev.screenmine.gui;

import dev.screenmine.Lang;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.function.Consumer;

/** Tiny address bar dialog. */
public final class UrlEntry extends Screen {

    private final String initial;
    private final Consumer<String> onAccept;
    private final Screen parent;
    private EditBox field;

    public UrlEntry(String initial, Consumer<String> onAccept, Screen parent) {
        super(Component.literal(Lang.t("url.title")));
        this.initial = initial == null ? "" : initial;
        this.onAccept = onAccept;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        field = new EditBox(font, cx - 150, height / 2 - 10, 300, 20,
                Component.literal("URL"));
        field.setMaxLength(2000);
        field.setValue(initial);
        addRenderableWidget(field);
        setInitialFocus(field);

        addRenderableWidget(Button.builder(Component.literal(Lang.t("url.open")),
                b -> accept()).bounds(cx - 105, height / 2 + 18, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("url.cancel")),
                b -> onClose()).bounds(cx + 5, height / 2 + 18, 100, 20).build());
    }

    private void accept() {
        String url = field.getValue().trim();
        if (url.isEmpty()) {
            onClose();
            return;
        }
        if (!url.contains("://")) {
            url = url.contains(".") && !url.contains(" ")
                    ? "https://" + url
                    : "https://duckduckgo.com/?q=" + url.replace(" ", "+");
        }
        onAccept.accept(url);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Enter or numpad enter. Uses the vanilla helper rather than a hardcoded
        // key code: the 26.3 backend is SDL and hardcoding is explicitly warned
        // against.
        if (event.isConfirmation()) {
            accept();
            return true;
        }
        return super.keyPressed(event);
    }
    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.centeredText(font,
                Component.literal(Lang.t("url.hint")).withStyle(ChatFormatting.GRAY),
                width / 2, height / 2 - 28, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
