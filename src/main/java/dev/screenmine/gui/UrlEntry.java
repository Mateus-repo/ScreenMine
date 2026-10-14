package dev.screenmine.gui;

import dev.screenmine.Lang;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
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
        field = new EditBox(textRenderer, cx - 150, height / 2 - 10, 300, 20,
                Component.literal("URL"));
        field.setMaxLength(2000);
        field.setText(initial);
        addDrawableChild(field);
        setInitialFocus(field);

        addDrawableChild(Button.builder(Component.literal(Lang.t("url.open")),
                b -> accept()).dimensions(cx - 105, height / 2 + 18, 100, 20).build());
        addDrawableChild(Button.builder(Component.literal(Lang.t("url.cancel")),
                b -> close()).dimensions(cx + 5, height / 2 + 18, 100, 20).build());
    }

    private void accept() {
        String url = field.getText().trim();
        if (url.isEmpty()) {
            close();
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {   // enter / numpad enter
            accept();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Component.literal(Lang.t("url.hint")).formatted(ChatFormatting.GRAY),
                width / 2, height / 2 - 28, 0xFFFFFF);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
