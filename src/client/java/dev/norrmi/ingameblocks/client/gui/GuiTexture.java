package dev.norrmi.ingameblocks.client.gui;

import java.util.Arrays;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.data.PixelImage;

/**
 * A {@link PixelImage} uploaded to the GPU, so a GUI can draw it with one textured quad instead of thousands of
 * rectangles. Transparent pixels are shown over a checkerboard. The upload only happens when the pixels changed.
 */
public final class GuiTexture {
	private static int counter;

	private final Identifier id;
	private DynamicTexture texture;
	private int textureWidth;
	private int textureHeight;
	private int[] uploadedArray;
	private int uploadedHash;

	public GuiTexture(String name) {
		this.id = InGameBlocks.id("gui/" + name + "_" + counter++);
	}

	/** Small pictures get two checker squares per pixel, so the checkerboard stays fine when zoomed in. */
	private static int factor(PixelImage image) {
		return Math.max(image.width(), image.height()) <= 64 ? 2 : 1;
	}

	/** Uploads the picture if it differs from what is on the GPU. Call every frame before {@link #blit}. */
	public void update(PixelImage image) {
		int f = factor(image);
		int w = image.width() * f;
		int h = image.height() * f;
		int[] px = image.pixels();
		int hash = Arrays.hashCode(px);
		if (this.texture != null && w == this.textureWidth && h == this.textureHeight && px == this.uploadedArray && hash == this.uploadedHash) {
			return;
		}
		if (this.texture == null || w != this.textureWidth || h != this.textureHeight) {
			this.release();
			this.texture = new DynamicTexture(this.id::toString, new NativeImage(w, h, false));
			Minecraft.getInstance().getTextureManager().register(this.id, this.texture);
			this.textureWidth = w;
			this.textureHeight = h;
		}
		NativeImage out = this.texture.getPixels();
		int iw = image.width();
		for (int y = 0; y < h; y++) {
			int sy = y / f;
			for (int x = 0; x < w; x++) {
				int c = px[sy * iw + x / f];
				if ((c >>> 24) != 0xFF) {
					int checker = ((x + y) & 1) == 0 ? TextureDraw.CHECKER_LIGHT : TextureDraw.CHECKER_DARK;
					c = TextureDraw.blend(checker, c);
				}
				out.setPixel(x, y, c);
			}
		}
		this.texture.upload();
		this.uploadedArray = px;
		this.uploadedHash = hash;
	}

	/** Draws the whole picture stretched to (x, y, width, height). */
	public void blit(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		if (this.texture == null) {
			return;
		}
		graphics.blit(RenderPipelines.GUI_TEXTURED, this.id, x, y, 0F, 0F, width, height,
				this.textureWidth, this.textureHeight, this.textureWidth, this.textureHeight);
	}

	/** Frees the GPU texture. The object can still be used; the next {@link #update} uploads again. */
	public void release() {
		if (this.texture != null) {
			Minecraft.getInstance().getTextureManager().release(this.id);
			this.texture = null;
			this.uploadedArray = null;
		}
	}
}
