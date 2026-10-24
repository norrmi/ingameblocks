package dev.norrmi.ingameblocks.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.SoundType;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.block.BlockKind;
import dev.norrmi.ingameblocks.client.ClientActions;
import dev.norrmi.ingameblocks.client.library.LocalLibrary;
import dev.norrmi.ingameblocks.client.paint.PainterScreen;
import dev.norrmi.ingameblocks.data.PixelImage;
import dev.norrmi.ingameblocks.data.PngCodec;
import dev.norrmi.ingameblocks.data.SoundOptions;
import dev.norrmi.ingameblocks.data.TextureRules;

/**
 * Edits one of the player's own blocks: name, id, shape, sound, sharing, the main picture and a different picture for
 * every face. Nothing is written until "Save" is pressed.
 */
public class EditBlockScreen extends Screen {
	private static final int TILE = 28;
	private static final int STEP = TILE + 3;
	private static final int MAIN = -1;
	/** Types offered in the editor. Pillar is no longer offered (old pillar blocks show as a cube). */
	private static final BlockKind[] EDITABLE_KINDS = {
			BlockKind.CUBE, BlockKind.GLASS, BlockKind.SLAB, BlockKind.STAIRS, BlockKind.FENCE, BlockKind.CARPET,
			BlockKind.PLANT, BlockKind.HEAD, BlockKind.CHEST, BlockKind.ANVIL, BlockKind.PISTON,
	};
	/** Face index -> position in the unfolded cube (column, row). */
	private static final int[][] LAYOUT = {
			{1, 2}, // down
			{1, 0}, // up
			{1, 1}, // north
			{3, 1}, // south
			{0, 1}, // west
			{2, 1}, // east
	};

	private final Screen parent;
	private final LocalLibrary.Entry original;

	private String name;
	private String id;
	private BlockKind kind;
	private String sound;
	private boolean shared;
	private byte[] main;
	private boolean mainChanged;
	private final List<byte[]> faces;
	private final boolean[] faceChanged = new boolean[TextureRules.FACE_COUNT];
	private int selected = MAIN;
	private Component status = Component.empty();

	private EditBox nameBox;
	private EditBox idBox;
	private Button kindButton;
	private Button soundButton;
	private Button shareButton;
	private Button resetFaceButton;
	private int cubeX;
	private int cubeY;

	public EditBlockScreen(Screen parent, LocalLibrary.Entry entry) {
		super(Component.translatable("ingameblocks.edit.title", entry.name()));
		this.parent = parent;
		this.original = entry;
		this.name = entry.name();
		this.id = entry.id();
		this.kind = entry.kind() == BlockKind.PILLAR ? BlockKind.CUBE : entry.kind();
		this.sound = entry.sound();
		this.shared = entry.shared();
		this.main = entry.png();
		this.faces = new ArrayList<>(entry.faces());
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int top = Math.max(4, (this.height - 220) / 2);
		this.cubeX = cx - 165;
		this.cubeY = top + 22;

		int fx = this.cubeX;
		int fy = this.cubeY + 3 * STEP + 6;
		this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.edit.paint"), b -> this.paintSelected())
				.bounds(fx, fy + STEP + 2, 60, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.edit.choose"), b -> this.chooseSelected())
				.bounds(fx + 62, fy + STEP + 2, 60, 20).build());
		this.resetFaceButton = this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.edit.reset_face"), b -> this.resetSelected())
				.bounds(fx, fy + STEP + 24, 122, 20)
				.tooltip(Tooltip.create(Component.translatable("ingameblocks.edit.reset_face_tooltip")))
				.build());

		int rx = cx + 5;
		int rw = 160;
		int y = top + 22;
		this.nameBox = new EditBox(this.font, rx, y + 10, rw, 18, Component.translatable("ingameblocks.screen.name"));
		this.nameBox.setMaxLength(InGameBlocks.MAX_NAME_LENGTH);
		this.nameBox.setValue(this.name);
		this.nameBox.setResponder(text -> this.name = text);
		this.addRenderableWidget(this.nameBox);

		this.idBox = new EditBox(this.font, rx, y + 40, rw, 18, Component.translatable("ingameblocks.edit.id"));
		this.idBox.setMaxLength(InGameBlocks.MAX_ID_LENGTH);
		this.idBox.setValue(this.id);
		this.idBox.setResponder(text -> this.id = text);
		this.idBox.setTooltip(Tooltip.create(Component.translatable("ingameblocks.edit.id_tooltip")));
		this.addRenderableWidget(this.idBox);

		this.addRenderableWidget(Button.builder(Component.literal("<"), b -> this.cycleKind(-1)).bounds(rx, y + 64, 20, 20).build());
		this.kindButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.cycleKind(1)).bounds(rx + 22, y + 64, rw - 44, 20)
				.tooltip(Tooltip.create(Component.translatable("ingameblocks.edit.kind_tooltip"))).build());
		this.addRenderableWidget(Button.builder(Component.literal(">"), b -> this.cycleKind(1)).bounds(rx + rw - 20, y + 64, 20, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("<"), b -> this.cycleSound(-1)).bounds(rx, y + 88, 20, 20).build());
		this.soundButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.cycleSound(1)).bounds(rx + 22, y + 88, rw - 44, 20)
				.tooltip(Tooltip.create(Component.translatable("ingameblocks.edit.sound_tooltip"))).build());
		this.addRenderableWidget(Button.builder(Component.literal(">"), b -> this.cycleSound(1)).bounds(rx + rw - 20, y + 88, 20, 20).build());

		this.shareButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.shared = !this.shared;
			this.updateWidgets();
		}).bounds(rx, y + 112, rw, 20).tooltip(Tooltip.create(Component.translatable("ingameblocks.screen.share_tooltip"))).build());

		int by = top + 196;
		this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.edit.save"), b -> this.save()).bounds(cx - 102, by, 100, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose()).bounds(cx + 2, by, 100, 20).build());
		this.updateWidgets();
	}

	private void updateWidgets() {
		this.kindButton.setMessage(Component.translatable("ingameblocks.edit.kind", Component.translatable(this.kind.translationKey())));
		this.soundButton.setMessage(Component.translatable("ingameblocks.edit.sound", Component.translatable(SoundOptions.translationKey(this.sound))));
		this.shareButton.setMessage(Component.translatable(this.shared ? "ingameblocks.screen.share_on" : "ingameblocks.screen.share_off"));
		this.resetFaceButton.active = this.selected != MAIN && this.faces.get(this.selected) != null;
	}

	private void cycleKind(int delta) {
		int current = 0;
		for (int i = 0; i < EDITABLE_KINDS.length; i++) {
			if (EDITABLE_KINDS[i] == this.kind) {
				current = i;
			}
		}
		this.kind = EDITABLE_KINDS[(current + delta + EDITABLE_KINDS.length) % EDITABLE_KINDS.length];
		this.updateWidgets();
	}

	private void cycleSound(int delta) {
		List<String> keys = new ArrayList<>();
		keys.add("");
		keys.addAll(SoundOptions.keys());
		int i = keys.indexOf(this.sound);
		i = (i + delta + keys.size()) % keys.size();
		this.sound = keys.get(i);
		SoundType type = SoundOptions.get(this.sound.isEmpty() ? this.kind.defaultSound() : this.sound);
		if (type != null) {
			this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(type.getPlaceSound(), type.getPitch()));
		}
		this.updateWidgets();
	}

	private byte[] selectedPng() {
		return this.selected == MAIN ? this.main : (this.faces.get(this.selected) != null ? this.faces.get(this.selected) : this.main);
	}

	private void setSelectedPng(byte[] png) {
		if (this.selected == MAIN) {
			this.main = png;
			this.mainChanged = true;
		} else {
			this.faces.set(this.selected, png);
			this.faceChanged[this.selected] = true;
		}
		this.updateWidgets();
	}

	private Component selectedLabel() {
		return this.selected == MAIN
				? Component.translatable("ingameblocks.edit.main")
				: Component.translatable("ingameblocks.face." + TextureRules.FACE_NAMES[this.selected]);
	}

	private void paintSelected() {
		PixelImage image = TextureDraw.image(this.selectedPng());
		if (image == null) {
			return;
		}
		Component title = Component.translatable("ingameblocks.paint.title", this.name, this.selectedLabel());
		this.minecraft.gui.setScreen(new PainterScreen(this, title, image.copy(), false, painted -> {
			this.minecraft.gui.setScreen(this);
			this.setSelectedPng(PngCodec.encode(painted));
		}));
	}

	private void chooseSelected() {
		this.minecraft.gui.setScreen(new TexturePickerScreen(this, Component.translatable("ingameblocks.picker.title", this.selectedLabel()),
				this::setSelectedPng));
	}

	private void resetSelected() {
		if (this.selected != MAIN) {
			this.faces.set(this.selected, null);
			this.faceChanged[this.selected] = true;
			this.updateWidgets();
		}
	}

	private void save() {
		String currentId = this.original.id();
		try {
			currentId = LocalLibrary.changeId(currentId, this.id);
		} catch (IOException e) {
			this.status = Component.translatable("ingameblocks.error." + LocalLibrary.errorCode(e));
			return;
		}
		try {
			LocalLibrary.update(currentId, this.name, this.kind, this.sound, this.shared);
			if (this.mainChanged && this.main != null) {
				LocalLibrary.setMainTexture(currentId, this.main);
			}
			for (int face = 0; face < TextureRules.FACE_COUNT; face++) {
				if (this.faceChanged[face]) {
					LocalLibrary.setFace(currentId, face, this.faces.get(face));
				}
			}
		} catch (IOException e) {
			this.status = Component.translatable("ingameblocks.error." + LocalLibrary.errorCode(e));
			return;
		}
		ClientActions.libraryChanged();
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	private int[] tilePos(int face) {
		if (face == MAIN) {
			return new int[] {this.cubeX, this.cubeY + 3 * STEP + 6};
		}
		return new int[] {this.cubeX + LAYOUT[face][0] * STEP, this.cubeY + LAYOUT[face][1] * STEP};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int face = MAIN; face < TextureRules.FACE_COUNT; face++) {
			int[] p = this.tilePos(face);
			if (event.x() >= p[0] && event.x() < p[0] + TILE && event.y() >= p[1] && event.y() < p[1] + TILE) {
				this.selected = face;
				this.updateWidgets();
				ClientActions.playClick();
				if (doubleClick) {
					this.paintSelected();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int cx = this.width / 2;
		int top = this.cubeY - 22;
		graphics.centeredText(this.font, this.title, cx, top + 4, 0xFFFFFFFF);

		graphics.text(this.font, Component.translatable("ingameblocks.edit.faces"), this.cubeX, this.cubeY - 11, 0xFFA0A0A0);
		for (int face = MAIN; face < TextureRules.FACE_COUNT; face++) {
			int[] p = this.tilePos(face);
			byte[] png = face == MAIN ? this.main : (this.faces.get(face) != null ? this.faces.get(face) : this.main);
			graphics.fill(p[0] - 1, p[1] - 1, p[0] + TILE + 1, p[1] + TILE + 1, 0xFF101010);
			TextureDraw.draw(graphics, TextureDraw.image(png), p[0], p[1], TILE);
			if (face != MAIN && this.faces.get(face) == null) {
				// Uses the main picture: dim it a little.
				graphics.fill(p[0], p[1], p[0] + TILE, p[1] + TILE, 0x40000000);
			}
			String label = face == MAIN ? "★" : TextureRules.FACE_NAMES[face].substring(0, 1).toUpperCase(java.util.Locale.ROOT);
			graphics.text(this.font, label, p[0] + 2, p[1] + 2, 0xFFFFFFFF, true);
			if (face == this.selected) {
				graphics.outline(p[0] - 2, p[1] - 2, TILE + 4, TILE + 4, 0xFFFFFF55);
			}
		}
		int[] mainPos = this.tilePos(MAIN);
		graphics.text(this.font, Component.translatable("ingameblocks.edit.main"), mainPos[0] + TILE + 6, mainPos[1] + 4, 0xFFA0A0A0);
		graphics.text(this.font, Component.translatable("ingameblocks.edit.selected", this.selectedLabel()), mainPos[0] + TILE + 6, mainPos[1] + 16, 0xFFFFFF55);

		int rx = cx + 5;
		graphics.text(this.font, Component.translatable("ingameblocks.screen.name"), rx, this.cubeY, 0xFFA0A0A0);
		graphics.text(this.font, Component.translatable("ingameblocks.edit.id"), rx, this.cubeY + 30, 0xFFA0A0A0);
		graphics.textWithWordWrap(this.font, Component.translatable("ingameblocks.kind_help." + this.kind.id()), rx, this.cubeY + 136, 160, 0xFF909090);
		if (!this.status.getString().isEmpty()) {
			graphics.centeredText(this.font, this.status, cx, top + 186, 0xFFFF5555);
		}
	}
}
