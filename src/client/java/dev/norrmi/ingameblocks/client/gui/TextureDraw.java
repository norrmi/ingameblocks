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

	/** GPU copies of the pictures shown in menus (pictures from {@link #image} never change, so no re-upload is needed). */
	private static final Map<PixelImage, GuiTexture> TEXTURES = new LinkedHashMap<>(64, 0.75F, true);
	private static final Map<PixelImage, Long> LAST_USED = new java.util.HashMap<>();
	private static final int TEXTURE_LIMIT = 256;
	private static final long KEEP_NANOS = 3_000_000_000L;

	/** Draws {@code image} into the square (x, y, size, size). Transparent pixels show a checkerboard. */
	public static void draw(GuiGraphicsExtractor graphics, PixelImage image, int x, int y, int size) {
		if (image == null || size <= 0) {
			return;
		}
		long now = System.nanoTime();
		GuiTexture texture = TEXTURES.get(image);
		if (texture == null) {
			evictOld(now);
			texture = new GuiTexture("tile");
			texture.update(image);
			TEXTURES.put(image, texture);
		}
		LAST_USED.put(image, now);
		texture.blit(graphics, x, y, size, size);
	}

	/** Frees textures that were not drawn for a few seconds once there are many of them. */
	private static void evictOld(long now) {
		if (TEXTURES.size() < TEXTURE_LIMIT) {
			return;
		}
		var it = TEXTURES.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			Long used = LAST_USED.get(entry.getKey());
			if (used == null || now - used > KEEP_NANOS) {
				entry.getValue().release();
				LAST_USED.remove(entry.getKey());
				it.remove();
			}
		}
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
