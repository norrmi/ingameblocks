package dev.norrmi.ingameblocks.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A small, dependency-free PNG reader/writer. Reads every standard PNG (all colour types and bit depths,
 * palette transparency, interlacing); writes 8-bit RGBA.
 */
public final class PngCodec {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
	private static final int MAX_SIDE = 4096;

	private PngCodec() {
	}

	// ------------------------------------------------------------------ writing

	public static byte[] encode(PixelImage image) {
		int w = image.width();
		int h = image.height();
		byte[] raw = new byte[h * (1 + w * 4)];
		int p = 0;
		int[] px = image.pixels();
		for (int y = 0; y < h; y++) {
			raw[p++] = 0; // filter: none
			for (int x = 0; x < w; x++) {
				int c = px[y * w + x];
				raw[p++] = (byte) (c >> 16);
				raw[p++] = (byte) (c >> 8);
				raw[p++] = (byte) c;
				raw[p++] = (byte) (c >>> 24);
			}
		}
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream zipped = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		while (!deflater.finished()) {
			int n = deflater.deflate(buffer);
			zipped.write(buffer, 0, n);
		}
		deflater.end();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(SIGNATURE);
		byte[] ihdr = new byte[13];
		putInt(ihdr, 0, w);
		putInt(ihdr, 4, h);
		ihdr[8] = 8;  // bit depth
		ihdr[9] = 6;  // RGBA
		ihdr[10] = 0; // deflate
		ihdr[11] = 0; // standard filters
		ihdr[12] = 0; // no interlace
		chunk(out, "IHDR", ihdr);
		chunk(out, "IDAT", zipped.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		byte[] len = new byte[4];
		putInt(len, 0, data.length);
		out.writeBytes(len);
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		out.writeBytes(typeBytes);
		out.writeBytes(data);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		byte[] c = new byte[4];
		putInt(c, 0, (int) crc.getValue());
		out.writeBytes(c);
	}

	private static void putInt(byte[] b, int o, int v) {
		b[o] = (byte) (v >>> 24);
		b[o + 1] = (byte) (v >>> 16);
		b[o + 2] = (byte) (v >>> 8);
		b[o + 3] = (byte) v;
	}

	// ------------------------------------------------------------------ reading

	public static PixelImage decode(byte[] png) throws IOException {
		if (png == null || png.length < 33) {
			throw new IOException("not_png");
		}
		for (int i = 0; i < SIGNATURE.length; i++) {
			if (png[i] != SIGNATURE[i]) {
				throw new IOException("not_png");
			}
		}
		int pos = 8;
		int width = -1, height = -1, depth = 0, colorType = 0, interlace = 0;
		int[] palette = null;
		int[] paletteAlpha = null;
		int[] transparentRgb = null; // gray or rgb key colour (in sample units)
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		while (pos + 8 <= png.length) {
			int length = readInt(png, pos);
			String type = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
			int data = pos + 8;
			if (length < 0 || data + length > png.length) {
				throw new IOException("corrupt");
			}
			switch (type) {
				case "IHDR" -> {
					width = readInt(png, data);
					height = readInt(png, data + 4);
					depth = png[data + 8] & 0xFF;
					colorType = png[data + 9] & 0xFF;
					interlace = png[data + 12] & 0xFF;
				}
				case "PLTE" -> {
					palette = new int[length / 3];
					for (int i = 0; i < palette.length; i++) {
						int o = data + i * 3;
						palette[i] = 0xFF000000 | ((png[o] & 0xFF) << 16) | ((png[o + 1] & 0xFF) << 8) | (png[o + 2] & 0xFF);
					}
				}
				case "tRNS" -> {
					if (colorType == 3) {
						paletteAlpha = new int[length];
						for (int i = 0; i < length; i++) {
							paletteAlpha[i] = png[data + i] & 0xFF;
						}
					} else if (colorType == 0 && length >= 2) {
						transparentRgb = new int[] {readShort(png, data)};
					} else if (colorType == 2 && length >= 6) {
						transparentRgb = new int[] {readShort(png, data), readShort(png, data + 2), readShort(png, data + 4)};
					}
				}
				case "IDAT" -> idat.write(png, data, length);
				case "IEND" -> pos = png.length;
				default -> {
				}
			}
			pos = data + length + 4;
		}
		if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) {
			throw new IOException("corrupt");
		}
		int channels = switch (colorType) {
			case 0 -> 1;
			case 2 -> 3;
			case 3 -> 1;
			case 4 -> 2;
			case 6 -> 4;
			default -> throw new IOException("corrupt");
		};
		if (colorType == 3 && palette == null) {
			throw new IOException("corrupt");
		}
		int bitsPerPixel = channels * depth;
		byte[] raw = inflate(idat.toByteArray());
		PixelImage image = new PixelImage(width, height);
		if (interlace == 0) {
			unfilter(raw, 0, width, height, bitsPerPixel, image, 0, 0, 1, 1, colorType, depth, palette, paletteAlpha, transparentRgb);
		} else {
			int[][] passes = {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
			int offset = 0;
			for (int[] pass : passes) {
				int pw = (width - pass[0] + pass[2] - 1) / pass[2];
				int ph = (height - pass[1] + pass[3] - 1) / pass[3];
				if (pw <= 0 || ph <= 0) {
					continue;
				}
				offset = unfilter(raw, offset, pw, ph, bitsPerPixel, image, pass[0], pass[1], pass[2], pass[3],
						colorType, depth, palette, paletteAlpha, transparentRgb);
			}
		}
		return image;
	}

	private static byte[] inflate(byte[] data) throws IOException {
		Inflater inflater = new Inflater();
		inflater.setInput(data);
		ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
		byte[] buffer = new byte[16384];
		try {
			while (!inflater.finished()) {
				int n = inflater.inflate(buffer);
				if (n == 0) {
					if (inflater.needsInput() || inflater.needsDictionary()) {
						break;
					}
				}
				out.write(buffer, 0, n);
				if (out.size() > 64 * 1024 * 1024) {
					throw new IOException("too_large");
				}
			}
		} catch (DataFormatException e) {
			throw new IOException("corrupt", e);
		} finally {
			inflater.end();
		}
		return out.toByteArray();
	}

	private static int unfilter(byte[] raw, int offset, int w, int h, int bitsPerPixel, PixelImage image,
								int x0, int y0, int dx, int dy, int colorType, int depth,
								int[] palette, int[] paletteAlpha, int[] transparent) throws IOException {
		int bpp = Math.max(1, bitsPerPixel / 8);
		int stride = (w * bitsPerPixel + 7) / 8;
		byte[] prev = new byte[stride];
		byte[] cur = new byte[stride];
		for (int y = 0; y < h; y++) {
			if (offset + 1 + stride > raw.length) {
				throw new IOException("corrupt");
			}
			int filter = raw[offset] & 0xFF;
			System.arraycopy(raw, offset + 1, cur, 0, stride);
			offset += 1 + stride;
			for (int i = 0; i < stride; i++) {
				int a = i >= bpp ? cur[i - bpp] & 0xFF : 0;
				int b = prev[i] & 0xFF;
				int c = i >= bpp ? prev[i - bpp] & 0xFF : 0;
				int x = cur[i] & 0xFF;
				int value = switch (filter) {
					case 0 -> x;
					case 1 -> x + a;
					case 2 -> x + b;
					case 3 -> x + ((a + b) >> 1);
					case 4 -> x + paeth(a, b, c);
					default -> throw new IOException("corrupt");
				};
				cur[i] = (byte) value;
			}
			for (int x = 0; x < w; x++) {
				image.set(x0 + x * dx, y0 + y * dy, pixel(cur, x, colorType, depth, palette, paletteAlpha, transparent));
			}
			byte[] t = prev;
			prev = cur;
			cur = t;
		}
		return offset;
	}

	private static int paeth(int a, int b, int c) {
		int p = a + b - c;
		int pa = Math.abs(p - a);
		int pb = Math.abs(p - b);
		int pc = Math.abs(p - c);
		if (pa <= pb && pa <= pc) {
			return a;
		}
		return pb <= pc ? b : c;
	}

	private static int sample(byte[] row, int index, int depth) {
		return switch (depth) {
			case 16 -> ((row[index * 2] & 0xFF) << 8) | (row[index * 2 + 1] & 0xFF);
			case 8 -> row[index] & 0xFF;
			default -> {
				int perByte = 8 / depth;
				int b = row[index / perByte] & 0xFF;
				int shift = 8 - depth * (index % perByte + 1);
				yield (b >> shift) & ((1 << depth) - 1);
			}
		};
	}

	private static int to8(int value, int depth) {
		return switch (depth) {
			case 16 -> value >> 8;
			case 8 -> value;
			case 4 -> value * 17;
			case 2 -> value * 85;
			case 1 -> value * 255;
			default -> value;
		};
	}

	private static int pixel(byte[] row, int x, int colorType, int depth, int[] palette, int[] paletteAlpha, int[] transparent) {
		switch (colorType) {
			case 0 -> {
				int s = sample(row, x, depth);
				int g = to8(s, depth);
				int alpha = transparent != null && s == transparent[0] ? 0 : 255;
				return (alpha << 24) | (g << 16) | (g << 8) | g;
			}
			case 2 -> {
				int rs = sample(row, x * 3, depth);
				int gs = sample(row, x * 3 + 1, depth);
				int bs = sample(row, x * 3 + 2, depth);
				int alpha = transparent != null && transparent.length == 3 && rs == transparent[0] && gs == transparent[1] && bs == transparent[2] ? 0 : 255;
				return (alpha << 24) | (to8(rs, depth) << 16) | (to8(gs, depth) << 8) | to8(bs, depth);
			}
			case 3 -> {
				int i = sample(row, x, depth);
				int rgb = i < palette.length ? palette[i] & 0xFFFFFF : 0;
				int alpha = paletteAlpha != null && i < paletteAlpha.length ? paletteAlpha[i] : 255;
				return (alpha << 24) | rgb;
			}
			case 4 -> {
				int g = to8(sample(row, x * 2, depth), depth);
				int a = to8(sample(row, x * 2 + 1, depth), depth);
				return (a << 24) | (g << 16) | (g << 8) | g;
			}
			default -> {
				int r = to8(sample(row, x * 4, depth), depth);
				int g = to8(sample(row, x * 4 + 1, depth), depth);
				int b = to8(sample(row, x * 4 + 2, depth), depth);
				int a = to8(sample(row, x * 4 + 3, depth), depth);
				return (a << 24) | (r << 16) | (g << 8) | b;
			}
		}
	}

	private static int readInt(byte[] b, int o) {
		return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
	}

	private static int readShort(byte[] b, int o) {
		return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
	}
}
