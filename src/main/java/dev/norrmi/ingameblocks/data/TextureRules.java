package dev.norrmi.ingameblocks.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import dev.norrmi.ingameblocks.InGameBlocks;

/** Pure helpers (no Minecraft classes): validation of textures, ids, names and hashes. */
public final class TextureRules {
	/** Allowed side lengths of a (square) texture. */
	public static final int[] ALLOWED_SIZES = {16, 32, 64, 128, 256};

	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	/** Face order used everywhere (matches the texture keys of Minecraft's block/cube model). */
	public static final String[] FACE_NAMES = {"down", "up", "north", "south", "west", "east"};
	public static final int FACE_COUNT = 6;

	private TextureRules() {
	}

	public static boolean hasPngSignature(byte[] data) {
		if (data == null || data.length < PNG_SIGNATURE.length) {
			return false;
		}
		for (int i = 0; i < PNG_SIGNATURE.length; i++) {
			if (data[i] != PNG_SIGNATURE[i]) {
				return false;
			}
		}
		return true;
	}

	public static boolean isWebp(byte[] data) {
		return data != null && data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
				&& data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
	}

	/**
	 * Recognises files that can never be used as a picture and tells what they really are:
	 * is_ico (an icon file) or is_html (a web page / SVG saved instead of the picture). Otherwise null.
	 */
	public static String detectUnsupportedFormat(byte[] data) {
		if (data == null || data.length < 4) {
			return null;
		}
		if (data[0] == 0 && data[1] == 0 && data[2] == 1 && data[3] == 0) {
			return "is_ico";
		}
		int i = 0;
		if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
			i = 3;
		}
		while (i < data.length && i < 64 && (data[i] == ' ' || data[i] == '\n' || data[i] == '\r' || data[i] == '\t')) {
			i++;
		}
		if (i < data.length && data[i] == '<') {
			return "is_html";
		}
		return null;
	}

	/** A face texture is either empty ("same as the block's main texture") or a valid hash. */
	public static boolean isValidFaceHash(String hash) {
		return hash != null && (hash.isEmpty() || isValidHash(hash));
	}

	public static String sha256(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(data);
			char[] out = new char[hash.length * 2];
			for (int i = 0; i < hash.length; i++) {
				out[i * 2] = HEX[(hash[i] >> 4) & 0xF];
				out[i * 2 + 1] = HEX[hash[i] & 0xF];
			}
			return new String(out);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	public static String sha256(String text) {
		return sha256(text.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Cheap structural check of a PNG file (signature + IHDR). Returns {@code null} if the file is acceptable,
	 * otherwise a short error code: empty, too_large, not_png, not_square, bad_size.
	 */
	public static String validatePng(byte[] png, int maxBytes) {
		if (png == null || png.length == 0) {
			return "empty";
		}
		if (png.length > maxBytes) {
			return "too_large";
		}
		if (png.length < 33) {
			return "not_png";
		}
		for (int i = 0; i < PNG_SIGNATURE.length; i++) {
			if (png[i] != PNG_SIGNATURE[i]) {
				return "not_png";
			}
		}
		if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') {
			return "not_png";
		}
		int width = readInt(png, 16);
		int height = readInt(png, 20);
		if (width != height) {
			return "not_square";
		}
		for (int size : ALLOWED_SIZES) {
			if (size == width) {
				return null;
			}
		}
		return "bad_size";
	}

	/** Side length from the PNG header, or -1. */
	public static int pngSize(byte[] png) {
		if (png == null || png.length < 33) {
			return -1;
		}
		return readInt(png, 16);
	}

	private static int readInt(byte[] b, int offset) {
		return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16) | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
	}

	public static boolean isValidHash(String hash) {
		if (hash == null || hash.length() != 64) {
			return false;
		}
		for (int i = 0; i < hash.length(); i++) {
			char c = hash.charAt(i);
			boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	public static boolean isValidLocalId(String id) {
		if (id == null || id.isEmpty() || id.length() > InGameBlocks.MAX_ID_LENGTH) {
			return false;
		}
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/** Trims a display name, removes control characters and formatting codes, limits its length. */
	public static String cleanName(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < raw.length()) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isISOControl(cp) || cp == '\u00a7') {
				continue;
			}
			sb.appendCodePoint(cp);
		}
		String text = sb.toString().strip();
		if (text.codePointCount(0, text.length()) > InGameBlocks.MAX_NAME_LENGTH) {
			text = text.substring(0, text.offsetByCodePoints(0, InGameBlocks.MAX_NAME_LENGTH)).strip();
		}
		return text;
	}

	/**
	 * Turns any text (also Cyrillic) into a library id: lower-case latin letters, digits and underscores.
	 * Never returns an empty string.
	 */
	public static String slug(String text) {
		StringBuilder sb = new StringBuilder();
		String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				sb.append(c);
			} else {
				String tr = transliterate(c);
				if (tr != null) {
					sb.append(tr);
				} else {
					sb.append('_');
				}
			}
		}
		String result = sb.toString().replaceAll("_+", "_");
		result = trimUnderscores(result);
		if (result.length() > InGameBlocks.MAX_ID_LENGTH - 4) {
			result = trimUnderscores(result.substring(0, InGameBlocks.MAX_ID_LENGTH - 4));
		}
		if (result.isEmpty()) {
			result = "block_" + sha256(text == null ? "" : text).substring(0, 6);
		}
		return result;
	}

	private static String trimUnderscores(String s) {
		int start = 0;
		int end = s.length();
		while (start < end && s.charAt(start) == '_') {
			start++;
		}
		while (end > start && s.charAt(end - 1) == '_') {
			end--;
		}
		return s.substring(start, end);
	}

	private static String transliterate(char c) {
		return switch (c) {
			case '\u0430' -> "a";
			case '\u0431' -> "b";
			case '\u0432' -> "v";
			case '\u0433', '\u0491' -> "g";
			case '\u0434' -> "d";
			case '\u0435', '\u0451', '\u044d' -> "e";
			case '\u0454' -> "ye";
			case '\u0436' -> "zh";
			case '\u0437' -> "z";
			case '\u0438', '\u0456' -> "i";
			case '\u0457' -> "yi";
			case '\u0439', '\u044b' -> "y";
			case '\u043a' -> "k";
			case '\u043b' -> "l";
			case '\u043c' -> "m";
			case '\u043d' -> "n";
			case '\u043e' -> "o";
			case '\u043f' -> "p";
			case '\u0440' -> "r";
			case '\u0441' -> "s";
			case '\u0442' -> "t";
			case '\u0443' -> "u";
			case '\u0444' -> "f";
			case '\u0445' -> "kh";
			case '\u0446' -> "ts";
			case '\u0447' -> "ch";
			case '\u0448' -> "sh";
			case '\u0449' -> "shch";
			case '\u044a', '\u044c' -> "";
			case '\u044e' -> "yu";
			case '\u044f' -> "ya";
			default -> null;
		};
	}
}
