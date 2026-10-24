package dev.norrmi.ingameblocks.client.gui;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import dev.norrmi.ingameblocks.data.PixelImage;
import dev.norrmi.ingameblocks.data.PngCodec;
import dev.norrmi.ingameblocks.data.TextureRules;

/** Draws small textures in GUIs with plain rectangles (no texture upload needed). */
public final class TextureDraw {
	private static final int CACHE_SIZE = 512;
	private static final Map<String, PixelImage> CACHE = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, PixelImage> eldest) {
			return this.size() > CACHE_SIZE;
		}
	};
	public static final int CHECKER_LIGHT = 0xFF9C9C9C;
	public static final int CHECKER_DARK = 0xFF6E6E6E;

	private TextureDraw() {
	}

	/** Decodes (and caches) a PNG. Returns null if it cannot be read. */
	public static PixelImage image(byte[] png) {
		if (png == null) {
			return null;
		}
		String key = TextureRules.sha256(png);
		PixelImage cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		try {
			PixelImage image = PngCodec.decode(png);
			CACHE.put(key, image);
			return image;
		} catch (IOException e) {
			return null;
		}
	}

	public static void cache(String key, PixelImage image) {
		CACHE.put(key, image);
	}

	public static PixelImage cached(String key) {
		return CACHE.get(key);
	}

	/** Draws {@code image} into the square (x, y, size, size). Transparent pixels show a checkerboard. */
	public static void draw(GuiGraphicsExtractor graphics, PixelImage image, int x, int y, int size) {
		if (image == null || size <= 0) {
			return;
		}
		int w = image.width();
		int h = image.height();
		for (int ty = 0; ty < size; ty++) {
			int sy = ty * h / size;
			int runStart = 0;
			int runColor = sample(image, 0, sy, 0, ty);
			for (int tx = 1; tx <= size; tx++) {
				int color = tx < size ? sample(image, tx * w / size, sy, tx, ty) : 0;
				if (tx == size || color != runColor) {
					graphics.fill(x + runStart, y + ty, x + tx, y + ty + 1, runColor);
					runStart = tx;
					runColor = color;
				}
			}
		}
	}

	/** Pixel colour blended over the checkerboard. */
	private static int sample(PixelImage image, int sx, int sy, int tx, int ty) {
		int c = image.get(Math.min(sx, image.width() - 1), sy);
		if (c >>> 24 == 255) {
			return c;
		}
		int checker = ((tx >> 2) + (ty >> 2)) % 2 == 0 ? CHECKER_LIGHT : CHECKER_DARK;
		return blend(checker, c);
	}

	/** Source-over blend of {@code top} onto an opaque {@code bottom}. */
	public static int blend(int bottom, int top) {
		int a = top >>> 24;
		if (a == 0) {
			return bottom | 0xFF000000;
		}
		int r = (((top >> 16) & 0xFF) * a + ((bottom >> 16) & 0xFF) * (255 - a)) / 255;
		int g = (((top >> 8) & 0xFF) * a + ((bottom >> 8) & 0xFF) * (255 - a)) / 255;
		int b = ((top & 0xFF) * a + (bottom & 0xFF) * (255 - a)) / 255;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}
