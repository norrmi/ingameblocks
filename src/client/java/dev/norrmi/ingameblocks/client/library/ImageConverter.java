package dev.norrmi.ingameblocks.client.library;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;

import com.mojang.blaze3d.platform.NativeImage;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.data.PixelImage;
import dev.norrmi.ingameblocks.data.PngCodec;
import dev.norrmi.ingameblocks.data.TextureRules;

/**
 * Turns any picture the player gives us (PNG, WebP, JPEG, BMP, GIF ...) into a valid block texture:
 * square, 16 / 32 / 64 / 128 or 256 pixels, PNG, at most 256 KiB.
 */
public final class ImageConverter {
	/** Exactly one of the two fields is set. */
	public record Result(byte[] png, PixelImage image, String error, boolean changed) {
		static Result fail(String error) {
			return new Result(null, null, error, false);
		}
	}

	private static boolean webpRegistered;

	private ImageConverter() {
	}

	/**
	 * @param data the file contents
	 * @return the normalised PNG, or an error code (see ingameblocks.error.* translations)
	 */
	public static Result toTexture(byte[] data) {
		if (data == null || data.length == 0) {
			return Result.fail("empty");
		}
		String unsupported = TextureRules.detectUnsupportedFormat(data);
		if (unsupported != null) {
			return Result.fail(unsupported);
		}
		PixelImage image;
		boolean converted = false;
		if (TextureRules.hasPngSignature(data)) {
			try {
				image = PngCodec.decode(data);
			} catch (IOException e) {
				return Result.fail("corrupt");
			}
		} else {
			image = decodeOther(data);
			if (image == null) {
				return Result.fail(TextureRules.isWebp(data) ? "is_webp" : "not_png");
			}
			converted = true;
		}
		return normalize(image, converted ? null : data);
	}

	/** Makes a picture square and of an allowed size; returns the PNG to store. */
	public static Result normalize(PixelImage image, byte[] originalPng) {
		PixelImage square = image.centerSquare();
		int side = square.width();
		int target = allowedSize(side);
		PixelImage fixed = square;
		if (target != side) {
			fixed = side > target ? square.scaledSmooth(target) : square.resized(target, target);
		}
		boolean changed = fixed != image || originalPng == null;
		byte[] png = changed ? PngCodec.encode(fixed) : originalPng;
		while (png.length > InGameBlocks.MAX_TEXTURE_BYTES && fixed.width() > 16) {
			fixed = fixed.scaledSmooth(fixed.width() / 2);
			png = PngCodec.encode(fixed);
			changed = true;
		}
		if (png.length > InGameBlocks.MAX_TEXTURE_BYTES) {
			return Result.fail("too_large");
		}
		return new Result(png, fixed, null, changed);
	}

	/** The allowed side length closest to {@code side} (16, 32, 64, 128, 256). */
	public static int allowedSize(int side) {
		int best = 16;
		for (int size : TextureRules.ALLOWED_SIZES) {
			if (size <= side) {
				best = size;
			}
		}
		return best;
	}

	private static PixelImage decodeOther(byte[] data) {
		PixelImage image = viaImageIo(data);
		if (image == null) {
			image = viaNativeImage(data);
		}
		return image;
	}

	/** Java ImageIO (JPEG, BMP, GIF) plus the bundled WebP reader. */
	private static PixelImage viaImageIo(byte[] data) {
		try {
			registerWebp();
			BufferedImage source = ImageIO.read(new ByteArrayInputStream(data));
			if (source == null) {
				return null;
			}
			int w = source.getWidth();
			int h = source.getHeight();
			if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
				return null;
			}
			int[] argb = source.getRGB(0, 0, w, h, null, 0, w);
			return new PixelImage(w, h, argb);
		} catch (Exception | LinkageError e) {
			InGameBlocks.LOGGER.debug("ImageIO could not read the picture: {}", e.toString());
			return null;
		}
	}

	/** Registers the TwelveMonkeys WebP reader (bundled with the mod) with ImageIO, found by name so it stays optional. */
	private static synchronized void registerWebp() {
		if (webpRegistered) {
			return;
		}
		webpRegistered = true;
		try {
			Class<?> spiClass = Class.forName("com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi", true, ImageConverter.class.getClassLoader());
			ImageReaderSpi spi = (ImageReaderSpi) spiClass.getDeclaredConstructor().newInstance();
			IIORegistry.getDefaultInstance().registerServiceProvider(spi);
			InGameBlocks.LOGGER.info("WebP support enabled");
		} catch (Exception | LinkageError e) {
			InGameBlocks.LOGGER.warn("WebP support is not available: {}", e.toString());
		}
	}

	/** The game's own image reader (stb_image: JPEG, BMP, GIF, TGA, PSD ...). */
	private static PixelImage viaNativeImage(byte[] data) {
		try (NativeImage image = NativeImage.read(data)) {
			int w = image.getWidth();
			int h = image.getHeight();
			if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
				return null;
			}
			PixelImage out = new PixelImage(w, h);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					out.set(x, y, image.getPixel(x, y));
				}
			}
			return out;
		} catch (Exception | LinkageError e) {
			return null;
		}
	}
}
