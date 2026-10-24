package dev.norrmi.ingameblocks.client.gui;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** A small "enter a text" dialog (rename, name of a new block). */
public class TextPromptScreen extends Screen {
	private final Screen parent;
	private final Component label;
	private final String initial;
	private final int maxLength;
	private final Consumer<String> onDone;
	private EditBox box;

	public TextPromptScreen(Screen parent, Component title, Component label, String initial, int maxLength, Consumer<String> onDone) {
		super(title);
		this.parent = parent;
		this.label = label;
		this.initial = initial == null ? "" : initial;
		this.maxLength = maxLength;
		this.onDone = onDone;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int cy = this.height / 2;
		String value = this.box != null ? this.box.getValue() : this.initial;
		this.box = new EditBox(this.font, cx - 100, cy - 10, 200, 20, this.label);
		this.box.setMaxLength(this.maxLength);
		this.box.setValue(value);
		this.addRenderableWidget(this.box);
		this.setInitialFocus(this.box);
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.done()).bounds(cx - 102, cy + 18, 100, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).bounds(cx + 2, cy + 18, 100, 20).build());
	}

	private void done() {
		String value = this.box.getValue().strip();
		this.minecraft.gui.setScreen(this.parent);
		if (!value.isEmpty()) {
			this.onDone.accept(value);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation() && this.box.isFocused()) {
			this.done();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
		graphics.text(this.font, this.label, this.width / 2 - 100, this.height / 2 - 22, 0xFFA0A0A0);
	}
}
