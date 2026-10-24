package dev.norrmi.ingameblocks.data;

import java.util.Arrays;

/** A simple ARGB picture (row by row, {@code argb[y * width + x]}). */
public final class PixelImage {
	private final int width;
	private final int height;
	private final int[] argb;

	public PixelImage(int width, int height) {
		this(width, height, new int[width * height]);
	}

	public PixelImage(int width, int height, int[] argb) {
		if (width <= 0 || height <= 0 || argb.length != width * height) {
			throw new IllegalArgumentException("Bad image size " + width + "x" + height);
		}
		this.width = width;
		this.height = height;
		this.argb = argb;
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	/** The pixel array itself (not a copy). */
	public int[] pixels() {
		return this.argb;
	}

	public int get(int x, int y) {
		return this.argb[y * this.width + x];
	}

	public void set(int x, int y, int color) {
		this.argb[y * this.width + x] = color;
	}

	public boolean inside(int x, int y) {
		return x >= 0 && y >= 0 && x < this.width && y < this.height;
	}

	public PixelImage copy() {
		return new PixelImage(this.width, this.height, Arrays.copyOf(this.argb, this.argb.length));
	}

	/** Nearest-neighbour resize (keeps pixel art crisp). */
	public PixelImage resized(int newWidth, int newHeight) {
		PixelImage out = new PixelImage(newWidth, newHeight);
		for (int y = 0; y < newHeight; y++) {
			int sy = y * this.height / newHeight;
			for (int x = 0; x < newWidth; x++) {
				int sx = x * this.width / newWidth;
				out.argb[y * newWidth + x] = this.argb[sy * this.width + sx];
			}
		}
		return out;
	}

	/** Area-average downscale (for photos), or nearest-neighbour when growing. */
	public PixelImage scaledSmooth(int size) {
		if (size >= this.width || size >= this.height) {
			return this.resized(size, size);
		}
		PixelImage out = new PixelImage(size, size);
		for (int y = 0; y < size; y++) {
			int y0 = y * this.height / size;
			int y1 = Math.max(y0 + 1, (y + 1) * this.height / size);
			for (int x = 0; x < size; x++) {
				int x0 = x * this.width / size;
				int x1 = Math.max(x0 + 1, (x + 1) * this.width / size);
				long a = 0, r = 0, g = 0, b = 0;
				int n = 0;
				for (int yy = y0; yy < y1; yy++) {
					for (int xx = x0; xx < x1; xx++) {
						int c = this.argb[yy * this.width + xx];
						int ca = c >>> 24;
						a += ca;
						r += (long) ((c >> 16) & 0xFF) * ca;
						g += (long) ((c >> 8) & 0xFF) * ca;
						b += (long) (c & 0xFF) * ca;
						n++;
					}
				}
				int outA = (int) (a / n);
				int outR = a == 0 ? 0 : (int) (r / a);
				int outG = a == 0 ? 0 : (int) (g / a);
				int outB = a == 0 ? 0 : (int) (b / a);
				out.argb[y * size + x] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
			}
		}
		return out;
	}

	/** The square in the middle of the picture. */
	public PixelImage centerSquare() {
		if (this.width == this.height) {
			return this;
		}
		int size = Math.min(this.width, this.height);
		int ox = (this.width - size) / 2;
		int oy = (this.height - size) / 2;
		PixelImage out = new PixelImage(size, size);
		for (int y = 0; y < size; y++) {
			System.arraycopy(this.argb, (y + oy) * this.width + ox, out.argb, y * size, size);
		}
		return out;
	}
}
