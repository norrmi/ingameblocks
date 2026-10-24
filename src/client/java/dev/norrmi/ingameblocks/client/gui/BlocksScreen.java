package dev.norrmi.ingameblocks.client.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.Blaze3D;

import net.minecraft.ChatFormatting;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.block.Designs;
import dev.norrmi.ingameblocks.client.ClientActions;
import dev.norrmi.ingameblocks.client.ClientState;
import dev.norrmi.ingameblocks.client.library.LocalLibrary;
import dev.norrmi.ingameblocks.client.paint.PainterScreen;
import dev.norrmi.ingameblocks.data.BlockInfo;
import dev.norrmi.ingameblocks.data.PixelImage;
import dev.norrmi.ingameblocks.data.PngCodec;
import dev.norrmi.ingameblocks.data.TextureRules;

/**
 * The InGameBlocks menu (key B or /igb). Two tabs: "My blocks" (your library + your blocks in this world) and
 * "Shared blocks" (blocks other players added to this world). Right click one of your blocks to rename it,
 * double click it to edit it. Drop pictures or exported .zip files on the window to add them.
 */
public class BlocksScreen extends Screen {
	private static final int COLUMNS = 9;
	private static final int ROWS = 3;
	private static final int PER_PAGE = COLUMNS * ROWS;
	private static final int CELL = 22;
	private static final int BUTTON = 20;
	/** Background of own blocks that are shared. */
	private static final int SHARED_HIGHLIGHT = 0x903A78FF;
	private static boolean sharedTabRemembered;

	/** One cell: a block that is in the world's registry and/or in the player's library. */
	private record Row(BlockInfo info, LocalLibrary.Entry library) {
		String key() {
			return this.info != null ? "slot:" + this.info.slotKey() : "lib:" + this.library.id();
		}

		String name() {
			return this.library != null ? this.library.name() : this.info.name();
		}

		boolean mine() {
			return this.library != null || ClientState.isMine(this.info);
		}

		boolean sharedByMe() {
			return this.mine() && (this.library != null ? this.library.shared() : this.info.shared());
		}
	}

	private final List<Row> rows = new ArrayList<>();
	private final List<Button> cells = new ArrayList<>();
	private boolean sharedTab = sharedTabRemembered;
	private int page;
	private String selectedKey;
	private int seenVersion = -1;
	private Component status = Component.empty();

	private int gridX;
	private int gridY;
	private int infoY;

	private Button myTab;
	private Button sharedTabButton;
	private Button takeOne;
	private Button takeStack;
	private Button editButton;
	private Button exportButton;
	private Button previousPage;
	private Button nextPage;

	public BlocksScreen() {
		super(Component.translatable("ingameblocks.screen.title"));
		// Pick up files that were added or changed while the game was running.
		ClientActions.reloadLibrary();
	}

	@Override
	protected void init() {
		this.cells.clear();
		int top = Math.max(4, (this.height - 236) / 2);
		this.gridX = (this.width - COLUMNS * CELL) / 2;
		this.gridY = top + 40;
		this.infoY = this.gridY + ROWS * CELL + 4;
		int cx = this.width / 2;

		this.myTab = this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.screen.tab.mine"), button -> this.switchTab(false))
				.bounds(cx - 101, top + 14, 100, 20).build());
		this.sharedTabButton = this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.screen.tab.shared"), button -> this.switchTab(true))
				.bounds(cx + 1, top + 14, 100, 20).build());

		for (int i = 0; i < PER_PAGE; i++) {
			int cellIndex = i;
			int x = this.gridX + (i % COLUMNS) * CELL;
			int y = this.gridY + (i / COLUMNS) * CELL;
			Button cell = Button.builder(Component.empty(), button -> this.select(this.page * PER_PAGE + cellIndex))
					.bounds(x, y, BUTTON, BUTTON)
					.build();
			this.cells.add(this.addRenderableWidget(cell));
		}

		int buttonWidth = 74;
		int gap = 3;
		int x = cx - (4 * buttonWidth + 3 * gap) / 2;
		int rowA = this.infoY + 44;
		int rowB = rowA + 23;
		int rowC = rowB + 23;

		this.takeOne = this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.screen.take_one"), button -> this.take(1))
				.bounds(x, rowA, buttonWidth, 20).build());
		this.takeStack = this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.screen.take_stack"), button -> this.take(64))
				.bounds(x + (buttonWidth + gap), rowA, buttonWidth, 20).build());
		this.editButton = this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.screen.edit"), button -> this.editOrCopy())
				.bounds(x + 2 * (buttonWidth + gap), rowA, buttonWidth, 20).build());
		this.exportButton = this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.screen.export"), button -> this.export())
				.bounds(x + 3 * (buttonWidth + gap), rowA, buttonWidth, 20)
				.tooltip(Tooltip.create(Component.translatable("ingameblocks.screen.export_tooltip")))
				.build());

		this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.screen.new"), button -> this.newBlock())
				.bounds(x, rowB, buttonWidth, 20)
				.tooltip(Tooltip.create(Component.translatable("ingameblocks.screen.new_tooltip")))
				.build());
		this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.screen.folder"), button -> Blaze3D.openPath(LocalLibrary.blocksDir()))
				.bounds(x + (buttonWidth + gap), rowB, buttonWidth, 20)
				.tooltip(Tooltip.create(Component.translatable("ingameblocks.screen.folder_tooltip")))
				.build());
		this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.screen.rescan"), button -> this.rescan())
				.bounds(x + 2 * (buttonWidth + gap), rowB, buttonWidth, 20)
				.tooltip(Tooltip.create(Component.translatable("ingameblocks.screen.rescan_tooltip")))
				.build());
		this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.screen.close"), button -> this.onClose())
				.bounds(x + 3 * (buttonWidth + gap), rowB, buttonWidth, 20).build());

		this.previousPage = this.addRenderableWidget(Button.builder(Component.literal("<"), button -> this.changePage(-1))
				.bounds(cx - 50, rowC, 20, 20).build());
		this.nextPage = this.addRenderableWidget(Button.builder(Component.literal(">"), button -> this.changePage(1))
				.bounds(cx + 30, rowC, 20, 20).build());

		this.rebuildRows();
		this.updateWidgets();
	}

	// ------------------------------------------------------------------ data

	private void rebuildRows() {
		this.rows.clear();
		if (this.sharedTab) {
			for (BlockInfo info : ClientState.entries()) {
				if (!ClientState.isMine(info)) {
					this.rows.add(new Row(info, null));
				}
			}
		} else {
			Set<String> inWorld = new HashSet<>();
			for (LocalLibrary.Entry entry : LocalLibrary.entries()) {
				BlockInfo info = ClientState.findMine(entry.uid());
				this.rows.add(new Row(info, entry));
				inWorld.add(entry.uid());
			}
			for (BlockInfo info : ClientState.entries()) {
				if (ClientState.isMine(info) && !inWorld.contains(info.localId())) {
					this.rows.add(new Row(info, null));
				}
			}
		}
		if (this.selectedKey != null && this.selectedIndex() < 0) {
			this.selectedKey = null;
		}
		int pages = Math.max(1, (this.rows.size() + PER_PAGE - 1) / PER_PAGE);
		this.page = Math.max(0, Math.min(this.page, pages - 1));
		this.seenVersion = ClientState.version();
	}

	private int selectedIndex() {
		if (this.selectedKey == null) {
			return -1;
		}
		for (int i = 0; i < this.rows.size(); i++) {
			if (this.rows.get(i).key().equals(this.selectedKey)) {
				return i;
			}
		}
		return -1;
	}

	private Row selectedRow() {
		int index = this.selectedIndex();
		return index < 0 ? null : this.rows.get(index);
	}

	private void updateWidgets() {
		int start = this.page * PER_PAGE;
		for (int i = 0; i < this.cells.size(); i++) {
			Button cell = this.cells.get(i);
			int index = start + i;
			boolean present = index < this.rows.size();
			cell.visible = present;
			cell.active = present;
			if (present) {
				Row row = this.rows.get(index);
				cell.setTooltip(Tooltip.create(Component.literal(row.name())));
			}
		}
		Row row = this.selectedRow();
		boolean ownLibrary = row != null && row.library() != null;
		boolean canTake = row != null && (row.library() == null || row.library().valid());
		this.takeOne.active = canTake;
		this.takeStack.active = canTake;
		if (this.sharedTab) {
			this.editButton.setMessage(Component.translatable("ingameblocks.screen.copy"));
			this.editButton.setTooltip(Tooltip.create(Component.translatable("ingameblocks.screen.copy_tooltip")));
			this.editButton.active = row != null && ClientState.displayTexture(row.info()) != null;
		} else {
			this.editButton.setMessage(Component.translatable("ingameblocks.screen.edit"));
			this.editButton.setTooltip(Tooltip.create(Component.translatable("ingameblocks.screen.edit_tooltip")));
			this.editButton.active = ownLibrary && row.library().valid();
		}
		this.exportButton.active = ownLibrary && row.library().valid();
		this.exportButton.visible = !this.sharedTab;
		this.myTab.active = this.sharedTab;
		this.sharedTabButton.active = !this.sharedTab;
		this.previousPage.active = this.page > 0;
		this.nextPage.active = (this.page + 1) * PER_PAGE < this.rows.size();
	}

	// ------------------------------------------------------------------ actions

	private void switchTab(boolean shared) {
		this.sharedTab = shared;
		sharedTabRemembered = shared;
		this.page = 0;
		this.selectedKey = null;
		this.status = Component.empty();
		this.rebuildRows();
		this.updateWidgets();
	}

	private void select(int index) {
		if (index >= 0 && index < this.rows.size()) {
			this.selectedKey = this.rows.get(index).key();
			this.status = Component.empty();
			this.updateWidgets();
		}
	}

	private void changePage(int delta) {
		this.page += delta;
		this.updateWidgets();
	}

	private void take(int count) {
		Row row = this.selectedRow();
		if (row == null) {
			return;
		}
		Component error = row.library() != null
				? ClientActions.takeOwn(row.library(), count)
				: ClientActions.takeBlock(row.info(), count);
		this.status = error != null ? error : Component.empty();
	}

	private void editOrCopy() {
		Row row = this.selectedRow();
		if (row == null) {
			return;
		}
		if (this.sharedTab) {
			this.copyShared(row.info());
		} else if (row.library() != null) {
			this.minecraft.gui.setScreen(new EditBlockScreen(this, row.library()));
		}
	}

	/** Makes an own, editable copy of another player's shared block. */
	private void copyShared(BlockInfo info) {
		byte[] main = ClientState.displayTexture(info);
		if (main == null) {
			return;
		}
		List<byte[]> faces = new ArrayList<>();
		for (int face = 0; face < TextureRules.FACE_COUNT; face++) {
			faces.add(info.faces().get(face).isEmpty() ? null : ClientState.displayTexture(info, face));
		}
		try {
			LocalLibrary.Entry entry = LocalLibrary.createBlock(info.name(), main, info.kind(), info.sound(), faces);
			ClientActions.libraryChanged();
			this.status = Component.translatable("ingameblocks.screen.copied", entry.name());
		} catch (IOException e) {
			this.status = Component.translatable("ingameblocks.cmd.import_failed", Component.translatable("ingameblocks.error." + LocalLibrary.errorCode(e)));
		}
	}

	private void export() {
		Row row = this.selectedRow();
		if (row == null || row.library() == null) {
			return;
		}
		try {
			Path file = LocalLibrary.exportZip(row.library().id());
			this.status = Component.translatable("ingameblocks.cmd.exported", file.getFileName().toString());
			Blaze3D.openPath(LocalLibrary.exportsDir());
		} catch (IOException e) {
			this.status = Component.translatable("ingameblocks.cmd.export_failed", Component.translatable("ingameblocks.error." + LocalLibrary.errorCode(e)));
		}
	}

	private void newBlock() {
		PixelImage blank = new PixelImage(16, 16);
		java.util.Arrays.fill(blank.pixels(), 0xFFFFFFFF);
		this.minecraft.gui.setScreen(new PainterScreen(this, Component.translatable("ingameblocks.paint.new_title"), blank, true, image -> {
			byte[] png = PngCodec.encode(image);
			this.minecraft.gui.setScreen(new TextPromptScreen(this, Component.translatable("ingameblocks.screen.new_name_title"),
					Component.translatable("ingameblocks.screen.name"), "", InGameBlocks.MAX_NAME_LENGTH, name -> {
				try {
					LocalLibrary.Entry entry = LocalLibrary.createBlock(name, png, null, "", null);
					ClientActions.libraryChanged();
					this.selectedKey = "lib:" + entry.id();
					this.status = Component.translatable("ingameblocks.cmd.imported", entry.name(), entry.id());
				} catch (IOException e) {
					this.status = Component.translatable("ingameblocks.cmd.import_failed", Component.translatable("ingameblocks.error." + LocalLibrary.errorCode(e)));
				}
			}));
		}));
	}

	private void rescan() {
		ClientActions.reloadLibrary();
		this.rebuildRows();
		this.updateWidgets();
		this.status = Component.translatable("ingameblocks.cmd.reloaded", LocalLibrary.entries().size(),
				LocalLibrary.entries().stream().filter(entry -> !entry.valid()).count());
	}

	private void rename(Row row) {
		LocalLibrary.Entry entry = row.library();
		this.minecraft.gui.setScreen(new TextPromptScreen(this, Component.translatable("ingameblocks.screen.rename_title"),
				Component.translatable("ingameblocks.screen.name"), entry.name(), InGameBlocks.MAX_NAME_LENGTH, name -> {
			LocalLibrary.rename(entry.id(), name);
			ClientActions.libraryChanged();
			this.status = Component.translatable("ingameblocks.cmd.renamed", name);
		}));
	}

	/** Index of the row under the mouse, or -1. */
	private int rowAt(double mouseX, double mouseY) {
		int col = (int) Math.floor((mouseX - this.gridX) / CELL);
		int line = (int) Math.floor((mouseY - this.gridY) / CELL);
		if (col < 0 || col >= COLUMNS || line < 0 || line >= ROWS) {
			return -1;
		}
		if (mouseX - this.gridX - col * CELL >= BUTTON || mouseY - this.gridY - line * CELL >= BUTTON) {
			return -1;
		}
		int index = this.page * PER_PAGE + line * COLUMNS + col;
		return index < this.rows.size() ? index : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int index = this.rowAt(event.x(), event.y());
		if (index >= 0) {
			Row row = this.rows.get(index);
			if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && row.library() != null) {
				this.select(index);
				this.rename(row);
				return true;
			}
			if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
				this.select(index);
				if (doubleClick && row.library() != null) {
					// Two quick clicks rename one of our own blocks.
					this.rename(row);
				} else if (event.hasShiftDown()) {
					this.take(64);
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		int added = 0;
		int updated = 0;
		int unchanged = 0;
		String failedFile = null;
		Component failedReason = null;
		for (Path path : paths) {
			try {
				LocalLibrary.ImportResult result = LocalLibrary.importFile(path, null);
				if (result.created()) {
					added++;
				} else if (result.changed()) {
					updated++;
				} else {
					unchanged++;
				}
			} catch (IOException | RuntimeException e) {
				InGameBlocks.LOGGER.warn("Could not add the dropped file {}", path, e);
				if (failedFile == null) {
					failedFile = String.valueOf(path.getFileName());
					failedReason = Component.translatable("ingameblocks.error." + LocalLibrary.errorCode(e));
				}
			}
		}
		ClientActions.libraryChanged();
		this.sharedTab = false;
		this.rebuildRows();
		this.updateWidgets();
		if (failedFile != null) {
			this.status = Component.translatable("ingameblocks.screen.dropped_failed", failedFile, failedReason);
		} else {
			this.status = Component.translatable("ingameblocks.screen.dropped", added, updated, unchanged);
		}
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (this.seenVersion != ClientState.version()) {
			this.rebuildRows();
			this.updateWidgets();
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		int top = this.gridY - 40;
		graphics.centeredText(this.font, this.title, this.width / 2, top + 2, 0xFFFFFFFF);

		int start = this.page * PER_PAGE;
		int selected = this.selectedIndex();
		for (int i = 0; i < PER_PAGE; i++) {
			int index = start + i;
			if (index >= this.rows.size()) {
				break;
			}
			int x = this.gridX + (i % COLUMNS) * CELL;
			int y = this.gridY + (i / COLUMNS) * CELL;
			Row row = this.rows.get(index);
			if (row.sharedByMe()) {
				graphics.fill(x + 1, y + 1, x + BUTTON - 1, y + BUTTON - 1, SHARED_HIGHLIGHT);
			}
			// The 16x16 icon is centred in the 20x20 cell.
			int ix = x + (BUTTON - 16) / 2;
			int iy = y + (BUTTON - 16) / 2;
			if (row.library() != null && !row.library().valid()) {
				graphics.fill(x + 1, y + 1, x + BUTTON - 1, y + BUTTON - 1, 0x60FF3030);
				graphics.centeredText(this.font, Component.literal("!"), x + BUTTON / 2, y + 6, 0xFFFF5555);
			} else if (row.info() != null && ClientState.packActive()) {
				graphics.item(Designs.stack(row.info().kind(), row.info().index(), 1), ix, iy);
			} else if (row.library() != null) {
				TextureDraw.draw(graphics, TextureDraw.image(row.library().png()), ix, iy, 16);
			} else if (row.info() != null) {
				TextureDraw.draw(graphics, TextureDraw.image(ClientState.displayTexture(row.info())), ix, iy, 16);
			}
			if (row.library() != null && row.info() == null && row.library().valid()) {
				graphics.text(this.font, Component.literal("+"), x + BUTTON - 7, y + BUTTON - 9, 0xFF55FF55, true);
			}
			if (index == selected) {
				graphics.outline(x - 1, y - 1, BUTTON + 2, BUTTON + 2, 0xFFFFFF55);
			}
		}

		this.drawInfo(graphics);
	}

	private void drawInfo(GuiGraphicsExtractor graphics) {
		int center = this.width / 2;
		Row row = this.selectedRow();
		if (row == null) {
			Component hint;
			if (this.rows.isEmpty()) {
				hint = Component.translatable(this.sharedTab ? "ingameblocks.screen.empty_shared" : "ingameblocks.screen.empty");
			} else {
				hint = Component.translatable(this.sharedTab ? "ingameblocks.screen.hint_shared" : "ingameblocks.screen.hint");
			}
			graphics.textWithWordWrap(this.font, hint, center - 150, this.infoY + 4, 300, 0xFFAAAAAA);
		} else {
			graphics.centeredText(this.font, Component.literal(row.name()), center, this.infoY, 0xFFFFFFFF);
			graphics.centeredText(this.font, this.detailLine(row), center, this.infoY + 11, 0xFFAAAAAA);
			graphics.centeredText(this.font, this.statusLine(row), center, this.infoY + 22, 0xFFAAAAAA);
		}
		if (!this.status.getString().isEmpty()) {
			graphics.centeredText(this.font, this.status, center, this.infoY + 33, 0xFFFFFF55);
		}
		int pages = Math.max(1, (this.rows.size() + PER_PAGE - 1) / PER_PAGE);
		graphics.centeredText(this.font, Component.literal((this.page + 1) + " / " + pages), center, this.infoY + 44 + 46 + 6, 0xFFCCCCCC);
	}

	private Component detailLine(Row row) {
		Component kind = Component.translatable((row.library() != null ? row.library().kind() : row.info().kind()).translationKey());
		Component who = row.mine()
				? Component.translatable("ingameblocks.screen.by_you")
				: Component.translatable("ingameblocks.screen.by", row.info().ownerName());
		String id = row.library() != null ? row.library().id() : row.info().localId();
		return Component.empty().append(kind).append(Component.literal(" · ")).append(who).append(Component.literal(" · id: " + id));
	}

	private Component statusLine(Row row) {
		if (row.library() != null && !row.library().valid()) {
			return Component.translatable("ingameblocks.error." + row.library().error()).withStyle(ChatFormatting.RED);
		}
		if (row.info() == null) {
			return Component.translatable("ingameblocks.screen.status.new");
		}
		BlockInfo info = row.info();
		if (!info.shared()) {
			return Component.translatable(row.mine() ? "ingameblocks.screen.status.private_mine" : "ingameblocks.screen.status.private");
		}
		if (!info.available() && !row.mine()) {
			return Component.translatable("ingameblocks.screen.status.waiting");
		}
		return Component.translatable("ingameblocks.screen.status.shared");
	}
}
