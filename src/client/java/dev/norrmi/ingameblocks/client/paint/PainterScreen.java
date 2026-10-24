package dev.norrmi.ingameblocks.client.paint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.norrmi.ingameblocks.client.gui.TextureDraw;
import dev.norrmi.ingameblocks.data.PixelImage;
import dev.norrmi.ingameblocks.data.TextureRules;

/**
 * A small pixel-art painter (own implementation, ideas from Blockbench's paint mode): brush with size, opacity,
 * softness, round/square tip and X mirror; eraser; flood fill; colour picker; line and rectangle; undo/redo; zoom
 * and pan; HSV colour picker with hex input and a palette.
 *
 * <p>Like in Blockbench, one stroke never paints over itself: every pixel remembers the strongest coverage it got
 * during the stroke, so a 50% brush gives an even 50% line even where the stroke crosses itself.</p>
 */
public class PainterScreen extends Screen {
	private enum Tool {
		BRUSH, ERASER, FILL, PICKER, LINE, RECT;

		String key() {
			return "ingameblocks.paint.tool." + this.name().toLowerCase(Locale.ROOT);
		}
	}

	private enum Drag {
		NONE, PAINT, PAN, SV, HUE, ALPHA
	}

	private static final int LEFT_PANEL = 104;
	private static final int RIGHT_PANEL = 134;
	private static final int SV_SIZE = 80;
	private static final int UNDO_LIMIT = 64;
	private static final int[] PALETTE = {
			0xFF000000, 0xFF3F3F3F, 0xFF7F7F7F, 0xFFBFBFBF, 0xFFFFFFFF, 0xFF7F3300, 0xFFB02E26, 0xFFF9801D,
			0xFFFED83D, 0xFF80C71F, 0xFF5E7C16, 0xFF169C9C, 0xFF3AB3DA, 0xFF3C44AA, 0xFF8932B8, 0xFFC74EBD,
	};

	private final Screen parent;
	private final boolean isNew;
	private final Consumer<PixelImage> onSave;

	private PixelImage image;
	private final Deque<PixelImage> undo = new ArrayDeque<>();
	private final Deque<PixelImage> redo = new ArrayDeque<>();
	private boolean dirty;
	private boolean discardWarned;

	private Tool tool = Tool.BRUSH;
	private int brushSize = 1;
	private int opacity = 100;
	private int softness = 0;
	private boolean round = true;
	private boolean mirror;
	private boolean grid = true;

	private float hue;
	private float sat;
	private float val;
	private float alpha = 1F;
	private final List<Integer> recent = new ArrayList<>();

	private int zoom = 1;
	private int panX;
	private int panY;
	private int areaX0;
	private int areaY0;
	private int areaX1;
	private int areaY1;

	private Drag drag = Drag.NONE;
	private PixelImage strokeBase;
	private float[] strokeMask;
	private float lastX;
	private float lastY;
	private float startX;
	private float startY;
	private boolean shapeFilled;
	private double panStartMouseX;
	private double panStartMouseY;
	private int panStartX;
	private int panStartY;

	private final List<Button> toolButtons = new ArrayList<>();
	private Button sizeButton;
	private Button opacityButton;
	private Button softButton;
	private Button shapeButton;
	private Button mirrorButton;
	private Button gridButton;
	private Button undoButton;
	private Button redoButton;
	private Button canvasButton;
	private EditBox hexBox;
	private boolean updatingHex;
	private Component status = Component.empty();
	private boolean initialized;

	public PainterScreen(Screen parent, Component title, PixelImage start, boolean isNew, Consumer<PixelImage> onSave) {
		super(title);
		this.parent = parent;
		this.isNew = isNew;
		this.onSave = onSave;
		this.image = start.copy();
		this.setColor(0xFF000000);
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		this.toolButtons.clear();
		int x = 4;
		int y = 22;
		Tool[] tools = Tool.values();
		for (int i = 0; i < tools.length; i++) {
			Tool t = tools[i];
			Button b = Button.builder(Component.translatable(t.key()), button -> {
				this.tool = t;
				this.updateWidgets();
			}).bounds(x + (i % 2) * 49, y + (i / 2) * 22, 48, 20)
					.tooltip(Tooltip.create(Component.translatable(t.key() + ".tooltip"))).build();
			this.toolButtons.add(this.addRenderableWidget(b));
		}
		y += 3 * 22 + 4;
		this.sizeButton = this.stepper(x, y, () -> this.brushSize = Math.max(1, this.brushSize - 1), () -> this.brushSize = Math.min(32, this.brushSize + 1));
		y += 22;
		this.opacityButton = this.stepper(x, y, () -> this.opacity = Math.max(5, this.opacity - 5), () -> this.opacity = Math.min(100, this.opacity + 5));
		y += 22;
		this.softButton = this.stepper(x, y, () -> this.softness = Math.max(0, this.softness - 10), () -> this.softness = Math.min(100, this.softness + 10));
		y += 24;
		this.shapeButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.round = !this.round;
			this.updateWidgets();
		}).bounds(x, y, 48, 20).build());
		this.mirrorButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.mirror = !this.mirror;
			this.updateWidgets();
		}).bounds(x + 49, y, 48, 20).tooltip(Tooltip.create(Component.translatable("ingameblocks.paint.mirror.tooltip"))).build());
		y += 22;
		this.gridButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			this.grid = !this.grid;
			this.updateWidgets();
		}).bounds(x, y, 48, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.paint.clear"), b -> this.clear())
				.bounds(x + 49, y, 48, 20).tooltip(Tooltip.create(Component.translatable("ingameblocks.paint.clear.tooltip"))).build());

		int by = this.height - 24;
		this.undoButton = this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.paint.undo"), b -> this.undo())
				.bounds(x, by, 48, 20).build());
		this.redoButton = this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.paint.redo"), b -> this.redo())
				.bounds(x + 49, by, 48, 20).build());
		int cx = (LEFT_PANEL + this.width - RIGHT_PANEL) / 2;
		this.canvasButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.cycleCanvasSize())
				.bounds(cx - 126, by, 80, 20).tooltip(Tooltip.create(Component.translatable("ingameblocks.paint.canvas_size.tooltip"))).build());
		this.addRenderableWidget(Button.builder(Component.translatable("ingameblocks.paint.save"), b -> this.save())
				.bounds(cx - 42, by, 84, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
				.bounds(cx + 46, by, 80, 20).build());

		int rx = this.width - RIGHT_PANEL + 6;
		this.hexBox = new EditBox(this.font, rx + 30, 22 + SV_SIZE + 16, 66, 16, Component.translatable("ingameblocks.paint.hex"));
		this.hexBox.setMaxLength(9);
		this.hexBox.setResponder(this::hexChanged);
		this.addRenderableWidget(this.hexBox);
		this.updateHexBox();

		this.areaX0 = LEFT_PANEL + 4;
		this.areaY0 = 20;
		this.areaX1 = this.width - RIGHT_PANEL - 4;
		this.areaY1 = this.height - 30;
		if (!this.initialized) {
			this.fitCanvas();
			this.initialized = true;
		} else {
			this.clampPan();
		}
		this.updateWidgets();
	}

	private Button stepper(int x, int y, Runnable minus, Runnable plus) {
		this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			minus.run();
			this.updateWidgets();
		}).bounds(x, y, 16, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			plus.run();
			this.updateWidgets();
		}).bounds(x + 81, y, 16, 20).build());
		Button label = Button.builder(Component.empty(), b -> {
		}).bounds(x + 17, y, 63, 20).build();
		label.active = false;
		return this.addRenderableWidget(label);
	}

	private void updateWidgets() {
		for (int i = 0; i < this.toolButtons.size(); i++) {
			Tool t = Tool.values()[i];
			Component name = Component.translatable(t.key());
			this.toolButtons.get(i).setMessage(t == this.tool ? Component.literal("[").append(name).append("]") : name);
		}
		this.sizeButton.setMessage(Component.translatable("ingameblocks.paint.size", this.brushSize));
		this.opacityButton.setMessage(Component.translatable("ingameblocks.paint.opacity", this.opacity));
		this.softButton.setMessage(Component.translatable("ingameblocks.paint.softness", this.softness));
		this.shapeButton.setMessage(Component.translatable(this.round ? "ingameblocks.paint.shape.round" : "ingameblocks.paint.shape.square"));
		this.mirrorButton.setMessage(Component.translatable(this.mirror ? "ingameblocks.paint.mirror.on" : "ingameblocks.paint.mirror.off"));
		this.gridButton.setMessage(Component.translatable(this.grid ? "ingameblocks.paint.grid.on" : "ingameblocks.paint.grid.off"));
		this.undoButton.active = !this.undo.isEmpty();
		this.redoButton.active = !this.redo.isEmpty();
		this.canvasButton.setMessage(Component.translatable("ingameblocks.paint.canvas_size", this.image.width(), this.image.height()));
	}

	private void fitCanvas() {
		int areaW = Math.max(16, this.areaX1 - this.areaX0);
		int areaH = Math.max(16, this.areaY1 - this.areaY0);
		this.zoom = Math.max(1, Math.min(areaW / this.image.width(), areaH / this.image.height()));
		this.panX = (areaW - this.image.width() * this.zoom) / 2;
		this.panY = (areaH - this.image.height() * this.zoom) / 2;
	}

	private void clampPan() {
		int areaW = this.areaX1 - this.areaX0;
		int areaH = this.areaY1 - this.areaY0;
		int w = this.image.width() * this.zoom;
		int h = this.image.height() * this.zoom;
		// Keep at least a quarter of the canvas on screen.
		this.panX = Math.max(-w + w / 4, Math.min(areaW - w / 4, this.panX));
		this.panY = Math.max(-h + h / 4, Math.min(areaH - h / 4, this.panY));
	}

	private int canvasX() {
		return this.areaX0 + this.panX;
	}

	private int canvasY() {
		return this.areaY0 + this.panY;
	}

	private boolean inArea(double mx, double my) {
		return mx >= this.areaX0 && mx < this.areaX1 && my >= this.areaY0 && my < this.areaY1;
	}

	private float texX(double mx) {
		return (float) ((mx - this.canvasX()) / this.zoom);
	}

	private float texY(double my) {
		return (float) ((my - this.canvasY()) / this.zoom);
	}

	// ------------------------------------------------------------------ colours

	private int color() {
		int rgb = hsvToRgb(this.hue, this.sat, this.val);
		return (Math.round(this.alpha * 255F) << 24) | rgb;
	}

	private void setColor(int argb) {
		float[] hsv = rgbToHsv(argb);
		// Keep the hue when picking grey, so the square does not jump to red.
		if (hsv[1] > 0F && hsv[2] > 0F) {
			this.hue = hsv[0];
		}
		this.sat = hsv[1];
		this.val = hsv[2];
		this.alpha = (argb >>> 24) / 255F;
		this.updateHexBox();
	}

	private void updateHexBox() {
		if (this.hexBox == null) {
			return;
		}
		int c = this.color();
		String text = (c >>> 24) == 0xFF ? String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF) : String.format(Locale.ROOT, "#%08X", c);
		this.updatingHex = true;
		this.hexBox.setValue(text);
		this.updatingHex = false;
	}

	private void hexChanged(String text) {
		if (this.updatingHex) {
			return;
		}
		String t = text.trim();
		if (t.startsWith("#")) {
			t = t.substring(1);
		}
		try {
			if (t.length() == 6) {
				this.setColorKeepHex(0xFF000000 | Integer.parseInt(t, 16));
			} else if (t.length() == 8) {
				this.setColorKeepHex((int) Long.parseLong(t, 16));
			}
		} catch (NumberFormatException ignored) {
			// Not finished typing yet.
		}
	}

	private void setColorKeepHex(int argb) {
		EditBox box = this.hexBox;
		this.hexBox = null;
		this.setColor(argb);
		this.hexBox = box;
	}

	private void rememberColor(int argb) {
		this.recent.remove(Integer.valueOf(argb));
		this.recent.add(0, argb);
		while (this.recent.size() > 8) {
			this.recent.remove(this.recent.size() - 1);
		}
	}

	static int hsvToRgb(float h, float s, float v) {
		float r;
		float g;
		float b;
		float hh = (h - (float) Math.floor(h)) * 6F;
		int i = (int) Math.floor(hh) % 6;
		float f = hh - (float) Math.floor(hh);
		float p = v * (1F - s);
		float q = v * (1F - f * s);
		float t = v * (1F - (1F - f) * s);
		switch (i) {
			case 0 -> { r = v; g = t; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = t; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = t; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		return (Math.round(r * 255F) << 16) | (Math.round(g * 255F) << 8) | Math.round(b * 255F);
	}

	static float[] rgbToHsv(int argb) {
		float r = ((argb >> 16) & 0xFF) / 255F;
		float g = ((argb >> 8) & 0xFF) / 255F;
		float b = (argb & 0xFF) / 255F;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h = 0F;
		if (d > 0F) {
			if (max == r) {
				h = ((g - b) / d) % 6F;
			} else if (max == g) {
				h = (b - r) / d + 2F;
			} else {
				h = (r - g) / d + 4F;
			}
			h /= 6F;
			if (h < 0F) {
				h += 1F;
			}
		}
		return new float[] {h, max == 0F ? 0F : d / max, max};
	}

	/** Non-premultiplied "source over" blending. */
	static int blendOver(int dst, int src, float srcAlphaScale) {
		float sa = ((src >>> 24) / 255F) * srcAlphaScale;
		if (sa <= 0F) {
			return dst;
		}
		float da = (dst >>> 24) / 255F;
		float oa = sa + da * (1F - sa);
		if (oa <= 0F) {
			return 0;
		}
		int r = Math.round((((src >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * da * (1F - sa)) / oa);
		int g = Math.round((((src >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * da * (1F - sa)) / oa);
		int b = Math.round(((src & 0xFF) * sa + (dst & 0xFF) * da * (1F - sa)) / oa);
		return (Math.round(oa * 255F) << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
	}

	private static int clamp255(int v) {
		return Math.max(0, Math.min(255, v));
	}

	// ------------------------------------------------------------------ history

	private void pushUndo() {
		this.undo.push(this.image.copy());
		while (this.undo.size() > UNDO_LIMIT) {
			this.undo.removeLast();
		}
		this.redo.clear();
		this.dirty = true;
		this.discardWarned = false;
	}

	private void undo() {
		if (!this.undo.isEmpty()) {
			this.redo.push(this.image);
			this.replaceImage(this.undo.pop());
		}
	}

	private void redo() {
		if (!this.redo.isEmpty()) {
			this.undo.push(this.image);
			this.replaceImage(this.redo.pop());
		}
	}

	private void replaceImage(PixelImage next) {
		boolean sizeChanged = next.width() != this.image.width() || next.height() != this.image.height();
		this.image = next;
		this.dirty = true;
		if (sizeChanged) {
			this.fitCanvas();
		}
		this.updateWidgets();
	}

	private void clear() {
		this.pushUndo();
		Arrays.fill(this.image.pixels(), 0);
		this.updateWidgets();
	}

	private void cycleCanvasSize() {
		int[] sizes = TextureRules.ALLOWED_SIZES;
		int current = this.image.width();
		int next = sizes[0];
		for (int i = 0; i < sizes.length; i++) {
			if (sizes[i] == current) {
				next = sizes[(i + 1) % sizes.length];
				break;
			}
		}
		this.pushUndo();
		this.image = this.image.resized(next, next);
		this.fitCanvas();
		this.updateWidgets();
	}

	// ------------------------------------------------------------------ painting

	private void beginStroke(float tx, float ty, boolean shift) {
		this.pushUndo();
		this.strokeBase = this.image.copy();
		this.strokeMask = new float[this.image.width() * this.image.height()];
		this.startX = tx;
		this.startY = ty;
		this.lastX = tx;
		this.lastY = ty;
		this.shapeFilled = shift;
		if (this.tool == Tool.BRUSH || this.tool == Tool.ERASER) {
			this.stamp(tx, ty);
		} else {
			this.redrawShape(tx, ty);
		}
	}

	private void continueStroke(float tx, float ty) {
		if (this.tool == Tool.BRUSH || this.tool == Tool.ERASER) {
			this.strokeLine(this.lastX, this.lastY, tx, ty);
		} else {
			this.redrawShape(tx, ty);
		}
		this.lastX = tx;
		this.lastY = ty;
	}

	private void endStroke() {
		if (this.strokeBase != null && this.tool != Tool.ERASER) {
			this.rememberColor(this.color());
		}
		this.strokeBase = null;
		this.strokeMask = null;
	}

	private void redrawShape(float tx, float ty) {
		System.arraycopy(this.strokeBase.pixels(), 0, this.image.pixels(), 0, this.image.pixels().length);
		Arrays.fill(this.strokeMask, 0F);
		if (this.tool == Tool.LINE) {
			this.strokeLine(this.startX, this.startY, tx, ty);
			return;
		}
		// Rectangle on the pixel grid from the start pixel to the current pixel.
		int x0 = (int) Math.floor(Math.min(this.startX, tx));
		int y0 = (int) Math.floor(Math.min(this.startY, ty));
		int x1 = (int) Math.floor(Math.max(this.startX, tx));
		int y1 = (int) Math.floor(Math.max(this.startY, ty));
		if (this.shapeFilled) {
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					this.applyCoverage(x, y, this.opacity / 100F);
					if (this.mirror) {
						this.applyCoverage(this.image.width() - 1 - x, y, this.opacity / 100F);
					}
				}
			}
			return;
		}
		this.strokeLine(x0 + 0.5F, y0 + 0.5F, x1 + 0.5F, y0 + 0.5F);
		this.strokeLine(x1 + 0.5F, y0 + 0.5F, x1 + 0.5F, y1 + 0.5F);
		this.strokeLine(x1 + 0.5F, y1 + 0.5F, x0 + 0.5F, y1 + 0.5F);
		this.strokeLine(x0 + 0.5F, y1 + 0.5F, x0 + 0.5F, y0 + 0.5F);
	}

	private void strokeLine(float x0, float y0, float x1, float y1) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		int steps = Math.max(1, (int) Math.ceil(length * 4F));
		for (int i = 0; i <= steps; i++) {
			float t = i / (float) steps;
			this.stamp(x0 + dx * t, y0 + dy * t);
		}
	}

	/** The pixels a brush tip covers when centred at the texture point (tx, ty), with their coverage. */
	private void forEachBrushPixel(float tx, float ty, PixelVisitor visitor) {
		int size = this.brushSize;
		float cx = size % 2 == 1 ? (float) Math.floor(tx) + 0.5F : Math.round(tx);
		float cy = size % 2 == 1 ? (float) Math.floor(ty) + 0.5F : Math.round(ty);
		float r = size / 2F;
		float limit = size <= 2 ? r * r : r * r - 0.5F;
		float soft = this.softness / 100F;
		int minX = (int) Math.floor(cx - r);
		int minY = (int) Math.floor(cy - r);
		for (int y = minY; y < minY + size + 1; y++) {
			for (int x = minX; x < minX + size + 1; x++) {
				float px = x + 0.5F - cx;
				float py = y + 0.5F - cy;
				float distance;
				if (this.round) {
					float d2 = px * px + py * py;
					if (d2 > limit) {
						continue;
					}
					distance = (float) Math.sqrt(d2);
				} else {
					if (Math.abs(px) >= r || Math.abs(py) >= r) {
						continue;
					}
					distance = Math.max(Math.abs(px), Math.abs(py));
				}
				float cover = 1F;
				if (soft > 0F && size > 1) {
					cover = Math.min(1F, Math.max(0F, (1F - distance / r) / soft));
					cover = Math.max(cover, 0.08F);
				}
				visitor.visit(x, y, cover);
			}
		}
	}

	@FunctionalInterface
	private interface PixelVisitor {
		void visit(int x, int y, float cover);
	}

	private void stamp(float tx, float ty) {
		float strength = this.opacity / 100F;
		this.forEachBrushPixel(tx, ty, (x, y, cover) -> {
			this.applyCoverage(x, y, cover * strength);
			if (this.mirror) {
				this.applyCoverage(this.image.width() - 1 - x, y, cover * strength);
			}
		});
	}

	private void applyCoverage(int x, int y, float amount) {
		if (!this.image.inside(x, y)) {
			return;
		}
		int i = y * this.image.width() + x;
		if (amount <= this.strokeMask[i]) {
			return;
		}
		this.strokeMask[i] = amount;
		int base = this.strokeBase.pixels()[i];
		int result;
		if (this.tool == Tool.ERASER) {
			int a = Math.round((base >>> 24) * (1F - amount));
			result = a <= 0 ? 0 : (a << 24) | (base & 0xFFFFFF);
		} else {
			result = blendOver(base, this.color(), amount);
		}
		this.image.pixels()[i] = result;
	}

	private void fill(int x, int y, boolean everywhere) {
		if (!this.image.inside(x, y)) {
			return;
		}
		this.pushUndo();
		int w = this.image.width();
		int h = this.image.height();
		int[] px = this.image.pixels();
		int target = px[y * w + x];
		float strength = this.opacity / 100F;
		int paint = this.color();
		boolean[] hit = new boolean[w * h];
		if (everywhere) {
			for (int i = 0; i < px.length; i++) {
				hit[i] = sameColor(px[i], target);
			}
		} else {
			int[] queue = new int[w * h];
			int head = 0;
			int tail = 0;
			queue[tail++] = y * w + x;
			hit[y * w + x] = true;
			while (head < tail) {
				int i = queue[head++];
				int cx = i % w;
				int cy = i / w;
				int[] next = {cx > 0 ? i - 1 : -1, cx < w - 1 ? i + 1 : -1, cy > 0 ? i - w : -1, cy < h - 1 ? i + w : -1};
				for (int n : next) {
					if (n >= 0 && !hit[n] && sameColor(px[n], target)) {
						hit[n] = true;
						queue[tail++] = n;
					}
				}
			}
		}
		for (int i = 0; i < px.length; i++) {
			if (hit[i]) {
				px[i] = strength >= 1F && (paint >>> 24) == 0xFF ? paint : blendOver(px[i], paint, strength);
			}
		}
		this.rememberColor(paint);
		this.updateWidgets();
	}

	private static boolean sameColor(int a, int b) {
		// All fully transparent pixels count as the same colour.
		return a == b || ((a >>> 24) == 0 && (b >>> 24) == 0);
	}

	private void pick(float tx, float ty) {
		int x = (int) Math.floor(tx);
		int y = (int) Math.floor(ty);
		if (this.image.inside(x, y)) {
			int c = this.image.get(x, y);
			this.setColor((c >>> 24) == 0 ? 0x00000000 | (this.color() & 0xFFFFFF) : c);
		}
	}

	// ------------------------------------------------------------------ saving

	private void save() {
		this.endStroke();
		this.dirty = false;
		this.onSave.accept(this.image.copy());
	}

	@Override
	public void onClose() {
		if (this.dirty && !this.discardWarned) {
			this.discardWarned = true;
			this.status = Component.translatable("ingameblocks.paint.discard_hint");
			return;
		}
		this.minecraft.gui.setScreen(this.parent);
	}

	// ------------------------------------------------------------------ input

	private int svX() {
		return this.width - RIGHT_PANEL + 6;
	}

	private int svY() {
		return 22;
	}

	private void updateColorDrag(double mx, double my) {
		int sx = this.svX();
		int sy = this.svY();
		switch (this.drag) {
			case SV -> {
				this.sat = (float) Math.max(0, Math.min(1, (mx - sx) / (SV_SIZE - 1)));
				this.val = 1F - (float) Math.max(0, Math.min(1, (my - sy) / (SV_SIZE - 1)));
			}
			case HUE -> this.hue = (float) Math.max(0, Math.min(0.999, (my - sy) / SV_SIZE));
			case ALPHA -> this.alpha = (float) Math.max(0, Math.min(1, (mx - sx) / (SV_SIZE - 1)));
			default -> {
				return;
			}
		}
		this.updateHexBox();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		double mx = event.x();
		double my = event.y();
		int sx = this.svX();
		int sy = this.svY();
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			if (mx >= sx && mx < sx + SV_SIZE && my >= sy && my < sy + SV_SIZE) {
				this.drag = Drag.SV;
			} else if (mx >= sx + SV_SIZE + 4 && mx < sx + SV_SIZE + 16 && my >= sy && my < sy + SV_SIZE) {
				this.drag = Drag.HUE;
			} else if (mx >= sx && mx < sx + SV_SIZE && my >= sy + SV_SIZE + 3 && my < sy + SV_SIZE + 11) {
				this.drag = Drag.ALPHA;
			}
			if (this.drag != Drag.NONE) {
				this.updateColorDrag(mx, my);
				return true;
			}
			int swatch = this.swatchAt(mx, my);
			if (swatch != 0 || this.swatchHit) {
				this.setColor(swatch);
				return true;
			}
		}
		if (!this.inArea(mx, my)) {
			return false;
		}
		this.setFocused(null);
		float tx = this.texX(mx);
		float ty = this.texY(my);
		if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE || (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.hasAltDown())) {
			this.drag = Drag.PAN;
			this.panStartMouseX = mx;
			this.panStartMouseY = my;
			this.panStartX = this.panX;
			this.panStartY = this.panY;
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			this.pick(tx, ty);
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		switch (this.tool) {
			case PICKER -> this.pick(tx, ty);
			case FILL -> this.fill((int) Math.floor(tx), (int) Math.floor(ty), event.hasShiftDown());
			default -> {
				this.beginStroke(tx, ty, event.hasShiftDown());
				this.drag = Drag.PAINT;
				this.updateWidgets();
			}
		}
		return true;
	}

	private boolean swatchHit;

	/** Colour of the palette / recent swatch under the mouse; sets {@link #swatchHit}. */
	private int swatchAt(double mx, double my) {
		this.swatchHit = false;
		int x0 = this.svX();
		int y0 = this.paletteY();
		for (int i = 0; i < PALETTE.length; i++) {
			int x = x0 + (i % 8) * 12;
			int y = y0 + (i / 8) * 12;
			if (mx >= x && mx < x + 11 && my >= y && my < y + 11) {
				this.swatchHit = true;
				return PALETTE[i];
			}
		}
		int ry = y0 + 2 * 12 + 12;
		for (int i = 0; i < this.recent.size(); i++) {
			int x = x0 + i * 12;
			if (mx >= x && mx < x + 11 && my >= ry && my < ry + 11) {
				this.swatchHit = true;
				return this.recent.get(i);
			}
		}
		return 0;
	}

	private int paletteY() {
		return this.svY() + SV_SIZE + 38;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		switch (this.drag) {
			case PAINT -> {
				this.continueStroke(this.texX(event.x()), this.texY(event.y()));
				return true;
			}
			case PAN -> {
				this.panX = this.panStartX + (int) Math.round(event.x() - this.panStartMouseX);
				this.panY = this.panStartY + (int) Math.round(event.y() - this.panStartMouseY);
				this.clampPan();
				return true;
			}
			case SV, HUE, ALPHA -> {
				this.updateColorDrag(event.x(), event.y());
				return true;
			}
			default -> {
				return super.mouseDragged(event, dx, dy);
			}
		}
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.drag == Drag.PAINT) {
			this.endStroke();
		}
		boolean had = this.drag != Drag.NONE;
		this.drag = Drag.NONE;
		return super.mouseReleased(event) || had;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (!this.inArea(mx, my) || scrollY == 0) {
			return super.mouseScrolled(mx, my, scrollX, scrollY);
		}
		int maxZoom = Math.max(4, 512 / Math.max(this.image.width(), 1) * 2);
		int old = this.zoom;
		int next = scrollY > 0 ? Math.min(maxZoom, old + Math.max(1, old / 4)) : Math.max(1, old - Math.max(1, old / 5));
		if (next == old) {
			return true;
		}
		// Zoom around the mouse pointer.
		float tx = this.texX(mx);
		float ty = this.texY(my);
		this.zoom = next;
		this.panX = (int) Math.round(mx - this.areaX0 - tx * next);
		this.panY = (int) Math.round(my - this.areaY0 - ty * next);
		this.clampPan();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.hexBox != null && this.hexBox.isFocused() && !event.isEscape()) {
			return super.keyPressed(event);
		}
		int key = event.key();
		if (event.hasControlDown()) {
			if (key == InputConstants.KEY_Z) {
				if (event.hasShiftDown()) {
					this.redo();
				} else {
					this.undo();
				}
				return true;
			}
			if (key == InputConstants.KEY_Y) {
				this.redo();
				return true;
			}
			if (key == InputConstants.KEY_S) {
				this.save();
				return true;
			}
		} else {
			Tool picked = null;
			if (key == InputConstants.KEY_B) {
				picked = Tool.BRUSH;
			} else if (key == InputConstants.KEY_E) {
				picked = Tool.ERASER;
			} else if (key == InputConstants.KEY_G || key == InputConstants.KEY_F) {
				picked = Tool.FILL;
			} else if (key == InputConstants.KEY_I) {
				picked = Tool.PICKER;
			} else if (key == InputConstants.KEY_L) {
				picked = Tool.LINE;
			} else if (key == InputConstants.KEY_R) {
				picked = Tool.RECT;
			} else if (key == InputConstants.KEY_X) {
				this.mirror = !this.mirror;
				this.updateWidgets();
				return true;
			} else if (key == InputConstants.KEY_MINUS) {
				this.brushSize = Math.max(1, this.brushSize - 1);
				this.updateWidgets();
				return true;
			} else if (key == InputConstants.KEY_EQUALS) {
				this.brushSize = Math.min(32, this.brushSize + 1);
				this.updateWidgets();
				return true;
			}
			if (picked != null) {
				this.tool = picked;
				this.updateWidgets();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, (LEFT_PANEL + this.width - RIGHT_PANEL) / 2, 6, 0xFFFFFFFF);

		this.drawCanvas(graphics, mouseX, mouseY);
		this.drawColorPanel(graphics);

		int infoY = 22 + 3 * 22 + 4 + 3 * 22 + 2 + 2 * 22 + 4;
		graphics.textWithWordWrap(this.font, Component.translatable("ingameblocks.paint.help"), 4, infoY, LEFT_PANEL - 6, 0xFF909090);
		if (this.inArea(mouseX, mouseY)) {
			int x = (int) Math.floor(this.texX(mouseX));
			int y = (int) Math.floor(this.texY(mouseY));
			if (this.image.inside(x, y)) {
				graphics.text(this.font, x + ", " + y, this.areaX0 + 2, this.areaY1 - 10, 0xFFCCCCCC, true);
			}
		}
		if (!this.status.getString().isEmpty()) {
			graphics.centeredText(this.font, this.status, (LEFT_PANEL + this.width - RIGHT_PANEL) / 2, this.areaY1 - 10, 0xFFFFFF55);
		}
	}

	private void drawCanvas(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(this.areaX0, this.areaY0, this.areaX1, this.areaY1, 0xFF202024);
		graphics.enableScissor(this.areaX0, this.areaY0, this.areaX1, this.areaY1);
		int ox = this.canvasX();
		int oy = this.canvasY();
		int w = this.image.width();
		int h = this.image.height();
		int z = this.zoom;
		int x0 = Math.max(this.areaX0, ox);
		int y0 = Math.max(this.areaY0, oy);
		int x1 = Math.min(this.areaX1, ox + w * z);
		int y1 = Math.min(this.areaY1, oy + h * z);
		if (x1 > x0 && y1 > y0) {
			// Checkerboard behind transparent pixels (screen-space cells, fewer rectangles).
			int cell = 6;
			for (int cy = y0; cy < y1; cy += cell) {
				for (int cx = x0; cx < x1; cx += cell) {
					boolean light = (((cx - ox) / cell) + ((cy - oy) / cell)) % 2 == 0;
					graphics.fill(cx, cy, Math.min(cx + cell, x1), Math.min(cy + cell, y1),
							light ? TextureDraw.CHECKER_LIGHT : TextureDraw.CHECKER_DARK);
				}
			}
			// Pixels, merged into runs of the same colour per row.
			int firstRow = Math.max(0, (y0 - oy) / z);
			int lastRow = Math.min(h - 1, (y1 - oy - 1) / z);
			int firstCol = Math.max(0, (x0 - ox) / z);
			int lastCol = Math.min(w - 1, (x1 - ox - 1) / z);
			int[] px = this.image.pixels();
			for (int ty = firstRow; ty <= lastRow; ty++) {
				int runStart = firstCol;
				int runColor = px[ty * w + firstCol];
				for (int tx = firstCol + 1; tx <= lastCol + 1; tx++) {
					int c = tx <= lastCol ? px[ty * w + tx] : ~runColor;
					if (c != runColor) {
						if ((runColor >>> 24) != 0) {
							graphics.fill(ox + runStart * z, oy + ty * z, ox + tx * z, oy + (ty + 1) * z, runColor);
						}
						runStart = tx;
						runColor = c;
					}
				}
			}
			if (this.grid && z >= 6) {
				int lineColor = 0x30000000;
				for (int tx = firstCol; tx <= lastCol + 1; tx++) {
					graphics.fill(ox + tx * z, y0, ox + tx * z + 1, y1, lineColor);
				}
				for (int ty = firstRow; ty <= lastRow + 1; ty++) {
					graphics.fill(x0, oy + ty * z, x1, oy + ty * z + 1, lineColor);
				}
			}
			if (this.mirror) {
				int mid = ox + w * z / 2;
				graphics.fill(mid, y0, mid + 1, y1, 0x8055AAFF);
			}
		}
		// Brush preview.
		if (this.inArea(mouseX, mouseY) && this.drag != Drag.PAN && (this.tool == Tool.BRUSH || this.tool == Tool.ERASER
				|| this.tool == Tool.LINE || this.tool == Tool.RECT)) {
			int preview = this.tool == Tool.ERASER ? 0x60FFFFFF : (0x70 << 24) | (this.color() & 0xFFFFFF);
			this.forEachBrushPixel(this.texX(mouseX), this.texY(mouseY), (x, y, cover) -> {
				if (this.image.inside(x, y)) {
					graphics.fill(ox + x * z, oy + y * z, ox + (x + 1) * z, oy + (y + 1) * z, preview);
				}
			});
		} else if (this.inArea(mouseX, mouseY) && (this.tool == Tool.FILL || this.tool == Tool.PICKER)) {
			int x = (int) Math.floor(this.texX(mouseX));
			int y = (int) Math.floor(this.texY(mouseY));
			if (this.image.inside(x, y)) {
				graphics.outline(ox + x * z, oy + y * z, Math.max(1, z), Math.max(1, z), 0xFFFFFFFF);
			}
		}
		graphics.disableScissor();
		graphics.outline(ox - 1, oy - 1, w * z + 2, h * z + 2, 0xFF000000);
	}

	private void drawColorPanel(GuiGraphicsExtractor graphics) {
		int sx = this.svX();
		int sy = this.svY();
		// Saturation (x) / value (y) square: one vertical gradient per column.
		for (int i = 0; i < SV_SIZE; i++) {
			float s = i / (float) (SV_SIZE - 1);
			int top = 0xFF000000 | hsvToRgb(this.hue, s, 1F);
			graphics.fillGradient(sx + i, sy, sx + i + 1, sy + SV_SIZE, top, 0xFF000000);
		}
		int kx = sx + Math.round(this.sat * (SV_SIZE - 1));
		int ky = sy + Math.round((1F - this.val) * (SV_SIZE - 1));
		graphics.outline(kx - 2, ky - 2, 5, 5, this.val > 0.5F ? 0xFF000000 : 0xFFFFFFFF);

		// Hue bar.
		int hx = sx + SV_SIZE + 4;
		for (int i = 0; i < SV_SIZE; i++) {
			graphics.fill(hx, sy + i, hx + 12, sy + i + 1, 0xFF000000 | hsvToRgb(i / (float) SV_SIZE, 1F, 1F));
		}
		int hy = sy + Math.round(this.hue * SV_SIZE);
		graphics.outline(hx - 1, hy - 1, 14, 3, 0xFFFFFFFF);

		// Alpha bar.
		int ay = sy + SV_SIZE + 3;
		int rgb = this.color() & 0xFFFFFF;
		for (int i = 0; i < SV_SIZE; i += 4) {
			graphics.fill(sx + i, ay, sx + i + 4, ay + 4, (i / 4) % 2 == 0 ? TextureDraw.CHECKER_LIGHT : TextureDraw.CHECKER_DARK);
			graphics.fill(sx + i, ay + 4, sx + i + 4, ay + 8, (i / 4) % 2 == 1 ? TextureDraw.CHECKER_LIGHT : TextureDraw.CHECKER_DARK);
		}
		for (int i = 0; i < SV_SIZE; i++) {
			int a = Math.round(i / (float) (SV_SIZE - 1) * 255F);
			graphics.fill(sx + i, ay, sx + i + 1, ay + 8, (a << 24) | rgb);
		}
		int ax = sx + Math.round(this.alpha * (SV_SIZE - 1));
		graphics.outline(ax - 1, ay - 1, 3, 10, 0xFFFFFFFF);

		// Current colour.
		int cy = sy + SV_SIZE + 16;
		graphics.fill(sx, cy, sx + 8, cy + 8, TextureDraw.CHECKER_LIGHT);
		graphics.fill(sx + 8, cy, sx + 16, cy + 8, TextureDraw.CHECKER_DARK);
		graphics.fill(sx, cy + 8, sx + 8, cy + 16, TextureDraw.CHECKER_DARK);
		graphics.fill(sx + 8, cy + 8, sx + 16, cy + 16, TextureDraw.CHECKER_LIGHT);
		graphics.fill(sx, cy, sx + 16, cy + 16, this.color());
		graphics.outline(sx - 1, cy - 1, 18, 18, 0xFF000000);

		// Palette and recent colours.
		int py = this.paletteY();
		for (int i = 0; i < PALETTE.length; i++) {
			int x = sx + (i % 8) * 12;
			int y = py + (i / 8) * 12;
			graphics.fill(x, y, x + 11, y + 11, PALETTE[i]);
		}
		int ry = py + 2 * 12 + 12;
		graphics.text(this.font, Component.translatable("ingameblocks.paint.recent"), sx, ry - 10, 0xFFA0A0A0);
		for (int i = 0; i < this.recent.size(); i++) {
			int x = sx + i * 12;
			graphics.fill(x, ry, x + 11, ry + 11, TextureDraw.CHECKER_LIGHT);
			graphics.fill(x, ry, x + 11, ry + 11, this.recent.get(i));
		}

		// Small preview at real size and doubled.
		int vy = ry + 18;
		if (vy + 34 < this.height - 28) {
			graphics.text(this.font, Component.translatable("ingameblocks.paint.preview"), sx, vy, 0xFFA0A0A0);
			TextureDraw.draw(graphics, this.image, sx, vy + 10, Math.min(32, this.image.width()));
			TextureDraw.draw(graphics, this.image, sx + 40, vy + 10, Math.min(64, Math.max(32, this.image.width())));
		}
	}

	public boolean isNewTexture() {
		return this.isNew;
	}
}
