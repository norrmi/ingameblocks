package dev.norrmi.ingameblocks.client.gui;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import dev.norrmi.ingameblocks.client.ClientState;
import dev.norrmi.ingameblocks.client.library.ImageConverter;
import dev.norrmi.ingameblocks.client.library.LocalLibrary;
import dev.norrmi.ingameblocks.data.BlockInfo;
import dev.norrmi.ingameblocks.data.PixelImage;
import dev.norrmi.ingameblocks.data.PngCodec;
import dev.norrmi.ingameblocks.data.TextureRules;

/**
 * Lets the player pick a picture: one of his own textures, any Minecraft block texture, or a texture another player
 * shared. The result is a ready-to-use PNG (square, allowed size).
 */
public class TexturePickerScreen extends Screen {
	private static final int COLUMNS = 10;
	private static final int ROWS = 4;
	private static final int PER_PAGE = COLUMNS * ROWS;
	private static final int CELL = 20;
	private static List<Identifier> vanillaCache;

	private enum Source { MINE, MINECRAFT, SHARED }

	/** One picture in the list. The PNG is loaded on demand. */
	private record Item(String key, String name, Supplier<byte[]> png) {
	}

	private final Screen parent;
	private final Consumer<byte[]> onPick;
	private Source source = Source.MINE;
	private final List<Item> all = new ArrayList<>();
	private final List<Item> filtered = new ArrayList<>();
	private int page;
	private int selected = -1;
	private EditBox search;
	private Button useButton;
	private Button previousPage;
	private Button nextPage;
	private final List<Button> sourceButtons = new ArrayList<>();
	private int gridX;
	private int gridY;

	public TexturePickerScreen(Screen parent, Component title, Consumer<byte[]> onPick) {
		super(title);
		this.parent = parent;
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int top = Math.max(4, (this.height - 200) / 2);
		this.sourceButtons.clear();
		Source[] sources = Source.values();
		for (int i = 0; i < sources.length; i++) {
			Source s = sources[i];
			Button button = Button.builder(Component.translatable("ingameblocks.picker.source." + s.name().toLowerCase(Locale.ROOT)), b -> this.setSource(s))
					.bounds(cx - 150 + i * 101, top + 14, 98, 20).build();
			this.sourceButtons.add(this.addRenderableWidget(button));
		}
		String query = this.search != null ? this.search.getValue() : "";
		this.search = new EditBox(this.font, cx - 100, top + 38, 200, 18, Component.translatable("ingameblocks.picker.search"));
		this.search.setHint(Component.translatable("ingameblocks.picker.search"));
		this.search.setValue(query);
		this.search.setResponder(text -> this.applyFilter());
		this.addRenderableWidget(this.search);

		this.gridX = cx - COLUMNS * CELL / 2;
		this.gridY = top + 62;
		int buttonsY = this.gridY + ROWS * CELL + 18;
		this.previousPage = this.addRenderableWidget(Button.builder(Component.literal("<"), b -> this.changePage(-1)).bounds(cx - 150, buttonsY, 20, 20).build());
		this.nextPage = this.addRenderableWidget(Button.builder(Component.literal(">"), b -> this.changePage(1)).bounds(cx - 128, buttonsY, 20, 20).build());
		this.useButton = this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.picker.use"), b -> this.use()).bounds(cx - 100, buttonsY, 98, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose()).bounds(cx + 2, buttonsY, 98, 20).build());
		this.loadSource();
	}

	private void setSource(Source source) {
		this.source = source;
		this.page = 0;
		this.selected = -1;
		this.loadSource();
	}

	private void loadSource() {
		this.all.clear();
		switch (this.source) {
			case MINE -> {
				for (LocalLibrary.Entry entry : LocalLibrary.entries()) {
					if (!entry.valid()) {
						continue;
					}
					this.all.add(new Item("hash:" + entry.hash(), entry.name(), entry::png));
					for (int face = 0; face < TextureRules.FACE_COUNT; face++) {
						byte[] png = entry.faces().get(face);
						if (png != null) {
							this.all.add(new Item("hash:" + TextureRules.sha256(png), entry.name() + " (" + TextureRules.FACE_NAMES[face] + ")", () -> png));
						}
					}
				}
			}
			case MINECRAFT -> {
				for (Identifier id : vanillaTextures()) {
					String path = id.getPath();
					String name = path.substring("textures/block/".length(), path.length() - 4);
					this.all.add(new Item("res:" + id, name, () -> loadResource(id)));
				}
			}
			case SHARED -> {
				for (BlockInfo info : ClientState.entries()) {
					if (ClientState.isMine(info)) {
						continue;
					}
					byte[] png = ClientState.displayTexture(info);
					if (png != null) {
						this.all.add(new Item("hash:" + info.hash(), info.name() + " (" + info.ownerName() + ")", () -> png));
					}
				}
			}
		}
		for (int i = 0; i < this.sourceButtons.size(); i++) {
			this.sourceButtons.get(i).active = i != this.source.ordinal();
		}
		this.applyFilter();
	}

	private void applyFilter() {
		String query = this.search == null ? "" : this.search.getValue().toLowerCase(Locale.ROOT).trim();
		this.filtered.clear();
		for (Item item : this.all) {
			if (query.isEmpty() || item.name().toLowerCase(Locale.ROOT).contains(query)) {
				this.filtered.add(item);
			}
		}
		int pages = Math.max(1, (this.filtered.size() + PER_PAGE - 1) / PER_PAGE);
		this.page = Math.min(this.page, pages - 1);
		if (this.selected >= this.filtered.size()) {
			this.selected = -1;
		}
		this.updateButtons();
	}

	private void updateButtons() {
		if (this.useButton == null) {
			return;
		}
		this.useButton.active = this.selected >= 0;
		this.previousPage.active = this.page > 0;
		this.nextPage.active = (this.page + 1) * PER_PAGE < this.filtered.size();
	}

	private void changePage(int delta) {
		this.page += delta;
		this.updateButtons();
	}

	/** All block textures of the active resource packs. */
	private static List<Identifier> vanillaTextures() {
		if (vanillaCache == null) {
			Map<Identifier, Resource> found = Minecraft.getInstance().getResourceManager()
					.listResources("textures/block", id -> id.getPath().endsWith(".png"));
			List<Identifier> ids = new ArrayList<>(found.keySet());
			ids.removeIf(id -> id.getNamespace().equals(dev.norrmi.ingameblocks.InGameBlocks.MOD_ID));
			ids.sort((a, b) -> a.getPath().compareTo(b.getPath()));
			vanillaCache = ids;
		}
		return vanillaCache;
	}

	private static byte[] loadResource(Identifier id) {
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(id)) {
			byte[] data = in.readAllBytes();
			PixelImage image = PngCodec.decode(data);
			if (image.height() > image.width()) {
				// Animated textures are a vertical strip of frames: use the first one.
				PixelImage frame = new PixelImage(image.width(), image.width());
				System.arraycopy(image.pixels(), 0, frame.pixels(), 0, image.width() * image.width());
				image = frame;
			}
			ImageConverter.Result result = ImageConverter.normalize(image, null);
			return result.png();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private PixelImage thumbnail(Item item) {
		PixelImage cached = TextureDraw.cached(item.key());
		if (cached != null) {
			return cached;
		}
		byte[] png = item.png().get();
		PixelImage image = TextureDraw.image(png);
		if (image != null) {
			TextureDraw.cache(item.key(), image);
		}
		return image;
	}

	private void use() {
		if (this.selected < 0 || this.selected >= this.filtered.size()) {
			return;
		}
		byte[] png = this.filtered.get(this.selected).png().get();
		if (png == null) {
			return;
		}
		this.minecraft.gui.setScreen(this.parent);
		this.onPick.accept(png);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int col = (int) Math.floor((event.x() - this.gridX) / CELL);
		int line = (int) Math.floor((event.y() - this.gridY) / CELL);
		if (col >= 0 && col < COLUMNS && line >= 0 && line < ROWS) {
			int index = this.page * PER_PAGE + line * COLUMNS + col;
			if (index < this.filtered.size()) {
				this.selected = index;
				this.updateButtons();
				if (doubleClick) {
					this.use();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, this.width / 2, this.gridY - 60, 0xFFFFFFFF);
		int start = this.page * PER_PAGE;
		Item hovered = null;
		for (int i = 0; i < PER_PAGE && start + i < this.filtered.size(); i++) {
			Item item = this.filtered.get(start + i);
			int x = this.gridX + (i % COLUMNS) * CELL;
			int y = this.gridY + (i / COLUMNS) * CELL;
			graphics.fill(x, y, x + 18, y + 18, 0xFF202020);
			TextureDraw.draw(graphics, this.thumbnail(item), x + 1, y + 1, 16);
			if (start + i == this.selected) {
				graphics.outline(x - 1, y - 1, 20, 20, 0xFFFFFF55);
			}
			if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
				hovered = item;
			}
		}
		if (this.filtered.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("ingameblocks.picker.empty"), this.width / 2, this.gridY + 30, 0xFFAAAAAA);
		}
		int pages = Math.max(1, (this.filtered.size() + PER_PAGE - 1) / PER_PAGE);
		graphics.text(this.font, Component.literal((this.page + 1) + "/" + pages), this.gridX, this.gridY + ROWS * CELL + 4, 0xFFAAAAAA);
		if (this.selected >= 0 && this.selected < this.filtered.size()) {
			graphics.centeredText(this.font, Component.literal(this.filtered.get(this.selected).name()), this.width / 2,
					this.gridY + ROWS * CELL + 4, 0xFFFFFFFF);
		}
		if (hovered != null) {
			graphics.setTooltipForNextFrame(Component.literal(hovered.name()), mouseX, mouseY);
		}
	}
}
