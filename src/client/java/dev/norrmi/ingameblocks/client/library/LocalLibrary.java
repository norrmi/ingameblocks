package dev.norrmi.ingameblocks.client.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.block.BlockKind;
import dev.norrmi.ingameblocks.data.BlockInfo;
import dev.norrmi.ingameblocks.data.SoundOptions;
import dev.norrmi.ingameblocks.data.PngCodec;
import dev.norrmi.ingameblocks.data.TextureRules;

/**
 * The creator's own blocks, stored on his computer in {@code <game dir>/ingameblocks/}:
 * <ul>
 * <li>{@code blocks/<name>.png} - the main picture of a block (the file name gives the block id and default name);</li>
 * <li>{@code faces/<id>/<face>.png} - optional different pictures for single faces;</li>
 * <li>{@code library.json} - names, shapes, sounds and sharing flags.</li>
 * </ul>
 * WebP, JPEG, BMP and GIF pictures (also with a wrong ".png" name) are converted automatically.
 */
public final class LocalLibrary {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Biggest source file that is looked at. Pictures are converted / shrunk to a texture afterwards. */
	private static final long MAX_SOURCE_BYTES = 8L * 1024 * 1024;
	private static final Set<String> EXTENSIONS = Set.of("png", "webp", "jpg", "jpeg", "bmp", "gif");

	/**
	 * @param id     stable id derived from the file name ({@code [a-z0-9_]+})
	 * @param name   display name
	 * @param kind   shape of the block
	 * @param sound  sound key, empty = the kind's default
	 * @param shared whether the creator wants to hand the textures out to other players
	 * @param file   the main picture file
	 * @param png    the main texture as PNG (null if invalid)
	 * @param hash   SHA-256 of {@link #png} (empty if invalid)
	 * @param error  null if the block is fine, otherwise an error code (see ingameblocks.error.* translations)
	 * @param faces  6 PNGs (down, up, north, south, west, east); null = the main texture
	 */
	public record Entry(String id, String uid, String name, BlockKind kind, String sound, boolean shared, Path file, byte[] png,
						String hash, String error, List<byte[]> faces, boolean seeThrough) {
		public boolean valid() {
			return this.error == null;
		}

		/** 6 hashes, "" where the face uses the main texture. */
		public List<String> faceHashes() {
			List<String> out = new ArrayList<>(TextureRules.FACE_COUNT);
			for (byte[] face : this.faces) {
				String h = face == null ? "" : TextureRules.sha256(face);
				out.add(h.equals(this.hash) ? "" : h);
			}
			return out;
		}

		/** PNG shown on one face. */
		public byte[] facePng(int face) {
			byte[] own = this.faces.get(face);
			return own != null ? own : this.png;
		}

		/** All textures of the block, main first. */
		public List<byte[]> allTextures() {
			List<byte[]> out = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			if (this.png != null && seen.add(this.hash)) {
				out.add(this.png);
			}
			for (byte[] face : this.faces) {
				if (face != null && seen.add(TextureRules.sha256(face))) {
					out.add(face);
				}
			}
			return out;
		}

		public boolean hasCustomFaces() {
			for (byte[] face : this.faces) {
				if (face != null) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * @param entry   the block in the library
	 * @param created true if a new block was added
	 * @param changed true if the picture was added or replaced (false: it was already there, identical)
	 */
	public record ImportResult(Entry entry, boolean created, boolean changed) {
	}

	private static Path root;
	private static JsonObject settings = new JsonObject();
	private static List<Entry> entries = List.of();

	private LocalLibrary() {
	}

	public static void init() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		root = gameDir.resolve(InGameBlocks.MOD_ID);
		migrateFromCustomBlocks(gameDir.resolve("customblocks"));
		try {
			Files.createDirectories(blocksDir());
			Files.createDirectories(cacheDir());
		} catch (IOException e) {
			InGameBlocks.LOGGER.error("Could not create the InGameBlocks folders in {}", root, e);
		}
		loadSettings();
		scan();
	}

	/** Copies the blocks of the old "CustomBlocks" version of the mod once. */
	private static void migrateFromCustomBlocks(Path old) {
		if (Files.exists(root) || !Files.isDirectory(old)) {
			return;
		}
		try {
			copyTree(old.resolve("blocks"), root.resolve("blocks"));
			copyTree(old.resolve("cache"), root.resolve("cache"));
			Path oldSettings = old.resolve("library.json");
			if (Files.isRegularFile(oldSettings)) {
				Files.createDirectories(root);
				Files.copy(oldSettings, root.resolve("library.json"));
			}
			InGameBlocks.LOGGER.info("Copied the blocks of CustomBlocks from {} to {}", old, root);
		} catch (IOException e) {
			InGameBlocks.LOGGER.warn("Could not copy the old CustomBlocks blocks", e);
		}
	}

	private static void copyTree(Path from, Path to) throws IOException {
		if (!Files.isDirectory(from)) {
			return;
		}
		Files.createDirectories(to);
		try (Stream<Path> files = Files.list(from)) {
			for (Path file : files.toList()) {
				if (Files.isRegularFile(file)) {
					Files.copy(file, to.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	public static Path root() {
		return root;
	}

	/** Put your pictures here. */
	public static Path blocksDir() {
		return root.resolve("blocks");
	}

	public static Path facesDir(String id) {
		return root.resolve("faces").resolve(id);
	}

	/** Textures downloaded from other players. */
	public static Path cacheDir() {
		return root.resolve("cache");
	}

	public static Path exportsDir() {
		return root.resolve("exports");
	}

	public static List<Entry> entries() {
		return entries;
	}

	public static Entry find(String id) {
		for (Entry entry : entries) {
			if (entry.id().equals(id)) {
				return entry;
			}
		}
		return null;
	}

	public static boolean shareByDefault() {
		return settings.has("shareByDefault") && settings.get("shareByDefault").getAsBoolean();
	}

	public static boolean isSupportedFile(Path file) {
		return EXTENSIONS.contains(extension(file));
	}

	private static String extension(Path file) {
		String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1);
	}

	private static String baseName(Path file) {
		String name = String.valueOf(file.getFileName());
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/** True if a registry entry already shows exactly what this library entry looks like. */
	public static boolean matches(Entry entry, BlockInfo info) {
		return info.hash().equals(entry.hash()) && info.faces().equals(entry.faceHashes())
				&& info.kind() == entry.kind() && info.sound().equals(entry.sound());
	}

	// ------------------------------------------------------------------ scanning

	/** Reads the folder again. */
	public static void scan() {
		Set<String> usedIds = new HashSet<>();
		JsonObject meta = settings.has("blocks") && settings.get("blocks").isJsonObject() ? settings.getAsJsonObject("blocks") : new JsonObject();

		List<Path> files = new ArrayList<>();
		try (Stream<Path> stream = Files.list(blocksDir())) {
			stream.filter(p -> Files.isRegularFile(p) && isSupportedFile(p)).sorted().forEach(files::add);
		} catch (IOException e) {
			InGameBlocks.LOGGER.warn("Could not list {}", blocksDir(), e);
		}

		List<Entry> found = new ArrayList<>();
		boolean settingsChanged = false;
		for (Path file : files) {
			String base = baseName(file);
			String id = uniqueId(TextureRules.slug(base), usedIds);
			usedIds.add(id);

			JsonObject m = meta.has(id) && meta.get(id).isJsonObject() ? meta.getAsJsonObject(id) : null;
			// The uid is what the server remembers as this block's identity. It is stored in library.json, so a
			// texture change or a restart never makes the server think it is a new block. Blocks from before the uid
			// existed keep the id they were registered with.
			String uid = m != null && m.has("uid") && m.get("uid").isJsonPrimitive() ? m.get("uid").getAsString() : "";
			if (!TextureRules.isValidLocalId(uid)) {
				uid = id;
				blockSettings(id).addProperty("uid", uid);
				settingsChanged = true;
			}
			String name = m != null && m.has("name") ? TextureRules.cleanName(m.get("name").getAsString()) : defaultName(base);
			if (name.isEmpty()) {
				name = defaultName(base);
			}
			boolean shared = m != null && m.has("shared") ? m.get("shared").getAsBoolean() : shareByDefault();
			BlockKind kind = m != null && m.has("kind") ? BlockKind.byIdOr(m.get("kind").getAsString(), BlockKind.CUBE) : BlockKind.CUBE;
			String sound = m != null && m.has("sound") ? m.get("sound").getAsString() : "";
			if (!SoundOptions.isValid(sound)) {
				sound = "";
			}

			byte[] png = null;
			String error;
			try {
				if (Files.size(file) > MAX_SOURCE_BYTES) {
					error = "too_large";
				} else {
					ImageConverter.Result result = ImageConverter.toTexture(Files.readAllBytes(file));
					error = result.error();
					png = result.png();
				}
			} catch (IOException e) {
				error = "unreadable";
			}
			String hash = error == null ? TextureRules.sha256(png) : "";

			List<byte[]> faces = new ArrayList<>(TextureRules.FACE_COUNT);
			for (String faceName : TextureRules.FACE_NAMES) {
				faces.add(error == null ? readFace(id, faceName) : null);
			}
			boolean seeThrough = false;
			if (error == null) {
				seeThrough = hasTransparency(png);
				for (byte[] facePng : faces) {
					seeThrough |= facePng != null && hasTransparency(facePng);
				}
			}
			found.add(new Entry(id, uid, name, kind, sound, shared, file, error == null ? png : null, hash, error, faces, seeThrough));
		}
		entries = List.copyOf(found);
		if (settingsChanged) {
			saveSettings();
		}
	}

	/** True if the picture has at least one pixel that is not fully opaque. */
	static boolean hasTransparency(byte[] png) {
		try {
			for (int argb : PngCodec.decode(png).pixels()) {
				if ((argb >>> 24) != 0xFF) {
					return true;
				}
			}
		} catch (IOException e) {
			return false;
		}
		return false;
	}

	/** A new, unique identity for a block: readable, and never equal to the identity of another block. */
	static String newUid(String base) {
		String slug = TextureRules.slug(base);
		if (slug.isEmpty()) {
			slug = "block";
		}
		if (slug.length() > 34) {
			slug = slug.substring(0, 34);
		}
		return slug + "_" + String.format(Locale.ROOT, "%04x", ThreadLocalRandom.current().nextInt(0x10000));
	}

	private static byte[] readFace(String id, String faceName) {
		Path file = facesDir(id).resolve(faceName + ".png");
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			ImageConverter.Result result = ImageConverter.toTexture(Files.readAllBytes(file));
			return result.error() == null ? result.png() : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static String uniqueId(String wanted, Set<String> used) {
		String id = wanted;
		int n = 2;
		while (used.contains(id)) {
			String suffix = "_" + n++;
			String shortened = wanted.length() + suffix.length() > InGameBlocks.MAX_ID_LENGTH
					? wanted.substring(0, InGameBlocks.MAX_ID_LENGTH - suffix.length()) : wanted;
			id = shortened + suffix;
		}
		return id;
	}

	private static String defaultName(String fileBase) {
		String text = fileBase.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
		text = TextureRules.cleanName(text);
		if (text.isEmpty()) {
			return "Block";
		}
		int first = text.codePointAt(0);
		return new String(Character.toChars(Character.toUpperCase(first))) + text.substring(Character.charCount(first));
	}

	// ------------------------------------------------------------------ changes

	public static void setShared(String id, boolean shared) {
		blockSettings(id).addProperty("shared", shared);
		saveSettings();
		scan();
	}

	public static void rename(String id, String newName) {
		String name = TextureRules.cleanName(newName);
		if (name.isEmpty()) {
			return;
		}
		blockSettings(id).addProperty("name", name);
		saveSettings();
		scan();
	}

	public static void setKind(String id, BlockKind kind) {
		blockSettings(id).addProperty("kind", kind.id());
		saveSettings();
		scan();
	}

	public static void setSound(String id, String sound) {
		if (!SoundOptions.isValid(sound)) {
			return;
		}
		blockSettings(id).addProperty("sound", sound);
		saveSettings();
		scan();
	}

	/** Several settings at once (used by the edit screen). */
	public static void update(String id, String name, BlockKind kind, String sound, boolean shared) {
		JsonObject m = blockSettings(id);
		String clean = TextureRules.cleanName(name);
		if (!clean.isEmpty()) {
			m.addProperty("name", clean);
		}
		m.addProperty("kind", kind.id());
		if (SoundOptions.isValid(sound)) {
			m.addProperty("sound", sound);
		}
		m.addProperty("shared", shared);
		saveSettings();
		scan();
	}

	/** Replaces the main picture of a block (painter). */
	public static void setMainTexture(String id, byte[] png) throws IOException {
		Entry entry = find(id);
		if (entry == null) {
			throw new IOException("unreadable");
		}
		Path target = entry.file();
		if (!"png".equals(extension(target))) {
			// Keep a single file per block: write a PNG next to it and remove the old picture.
			Path png2 = blocksDir().resolve(baseName(target) + ".png");
			Files.write(png2, png);
			Files.deleteIfExists(target);
		} else {
			Files.write(target, png);
		}
		scan();
	}

	/**
	 * Sets a different picture for one face (index into {@link TextureRules#FACE_NAMES}).
	 * {@code png == null} removes it (the face shows the main picture again).
	 */
	public static void setFace(String id, int face, byte[] png) throws IOException {
		Path file = facesDir(id).resolve(TextureRules.FACE_NAMES[face] + ".png");
		if (png == null) {
			Files.deleteIfExists(file);
		} else {
			Files.createDirectories(file.getParent());
			Files.write(file, png);
		}
		scan();
	}

	/** Removes all different face pictures. */
	public static void clearFaces(String id) throws IOException {
		for (int face = 0; face < TextureRules.FACE_COUNT; face++) {
			Files.deleteIfExists(facesDir(id).resolve(TextureRules.FACE_NAMES[face] + ".png"));
		}
		scan();
	}

	/**
	 * Gives a block a new id (the file is renamed). The display name is kept.
	 *
	 * @return the new id
	 * @throws IOException message is an error code: bad_id, id_taken, unreadable
	 */
	public static String changeId(String oldId, String wantedId) throws IOException {
		String newId = wantedId == null ? "" : wantedId.trim().toLowerCase(Locale.ROOT);
		if (!TextureRules.isValidLocalId(newId)) {
			throw new IOException("bad_id");
		}
		if (newId.equals(oldId)) {
			return oldId;
		}
		Entry entry = find(oldId);
		if (entry == null) {
			throw new IOException("unreadable");
		}
		if (find(newId) != null) {
			throw new IOException("id_taken");
		}
		Path newFile = blocksDir().resolve(newId + "." + extension(entry.file()));
		if (Files.exists(newFile)) {
			throw new IOException("id_taken");
		}
		JsonObject old = blockSettings(oldId).deepCopy();
		if (!old.has("name")) {
			old.addProperty("name", entry.name());
		}
		Files.move(entry.file(), newFile);
		Path oldFaces = facesDir(oldId);
		if (Files.isDirectory(oldFaces)) {
			Files.createDirectories(facesDir(newId).getParent());
			Files.move(oldFaces, facesDir(newId), StandardCopyOption.REPLACE_EXISTING);
		}
		JsonObject blocks = settings.getAsJsonObject("blocks");
		blocks.remove(oldId);
		blocks.add(newId, old);
		saveSettings();
		scan();
		return newId;
	}

	/**
	 * Creates a new block from a PNG (painter, copy of a shared block).
	 *
	 * @return the new entry
	 */
	public static Entry createBlock(String displayName, byte[] png, BlockKind kind, String sound, List<byte[]> faces) throws IOException {
		String name = TextureRules.cleanName(displayName);
		if (name.isEmpty()) {
			name = "Block";
		}
		String base = safeFileBase(name);
		Path target = blocksDir().resolve(base + ".png");
		int n = 2;
		while (Files.exists(target)) {
			target = blocksDir().resolve(base + " " + n++ + ".png");
		}
		Files.createDirectories(target.getParent());
		Files.write(target, png);
		scan();
		Entry entry = entryForFile(target);
		if (entry == null) {
			throw new IOException("unreadable");
		}
		JsonObject m = blockSettings(entry.id());
		m.addProperty("uid", newUid(entry.id()));
		m.addProperty("name", name);
		m.addProperty("kind", (kind == null ? BlockKind.CUBE : kind).id());
		if (sound != null && SoundOptions.isValid(sound)) {
			m.addProperty("sound", sound);
		}
		saveSettings();
		if (faces != null) {
			for (int face = 0; face < Math.min(faces.size(), TextureRules.FACE_COUNT); face++) {
				byte[] facePng = faces.get(face);
				if (facePng != null) {
					Path file = facesDir(entry.id()).resolve(TextureRules.FACE_NAMES[face] + ".png");
					Files.createDirectories(file.getParent());
					Files.write(file, facePng);
				}
			}
		}
		scan();
		return find(entry.id());
	}

	private static Entry entryForFile(Path file) {
		for (Entry entry : entries) {
			if (entry.file().equals(file)) {
				return entry;
			}
		}
		return null;
	}

	private static String safeFileBase(String name) {
		String base = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
		return base.isEmpty() ? "block" : base;
	}

	/**
	 * Adds a picture (any supported format) or an exported block (.zip) to the library. A picture with the name of an
	 * existing block replaces that block's picture.
	 *
	 * @throws IOException message is a short error code (see ingameblocks.error.* translations)
	 */
	public static ImportResult importFile(Path source, String displayName) throws IOException {
		if (!Files.isRegularFile(source)) {
			throw new IOException("unreadable");
		}
		if ("zip".equals(extension(source))) {
			return importZip(source);
		}
		if (!isSupportedFile(source)) {
			throw new IOException("not_png");
		}
		if (Files.size(source) > MAX_SOURCE_BYTES) {
			throw new IOException("too_large");
		}
		ImageConverter.Result converted = ImageConverter.toTexture(Files.readAllBytes(source));
		if (converted.error() != null) {
			throw new IOException(converted.error());
		}
		byte[] data = converted.png();

		String base = displayName != null && !TextureRules.cleanName(displayName).isEmpty()
				? safeFileBase(TextureRules.cleanName(displayName)) : safeFileBase(baseName(source));
		Path target = blocksDir().resolve(base + ".png");
		// The same picture under another extension (e.g. a renamed .webp) is replaced by the converted PNG.
		Path sameName = null;
		for (String ext : EXTENSIONS) {
			Path candidate = blocksDir().resolve(base + "." + ext);
			if (Files.exists(candidate)) {
				sameName = candidate;
				break;
			}
		}

		boolean existed = sameName != null;
		boolean changed;
		if (existed && Files.isSameFile(source, sameName)) {
			// A file that is already in the folder: only rewritten if it had to be converted.
			changed = converted.changed() || !sameName.equals(target);
		} else if (existed && Arrays.equals(Files.readAllBytes(sameName), data)) {
			changed = false;
		} else {
			changed = true;
		}
		if (changed) {
			Files.createDirectories(target.getParent());
			Files.write(target, data);
			if (sameName != null && !sameName.equals(target)) {
				Files.deleteIfExists(sameName);
			}
		}
		scan();
		Entry entry = entryForFile(target);
		if (entry == null && sameName != null) {
			entry = entryForFile(sameName);
		}
		if (entry == null) {
			throw new IOException("unreadable");
		}
		return new ImportResult(entry, !existed, changed);
	}

	// ------------------------------------------------------------------ export / import of whole blocks

	/** Writes a block (picture, face pictures, name, shape, sound) to {@code exports/<id>.zip}. */
	public static Path exportZip(String id) throws IOException {
		Entry entry = find(id);
		if (entry == null || !entry.valid()) {
			throw new IOException("unreadable");
		}
		Files.createDirectories(exportsDir());
		Path out = exportsDir().resolve(entry.id() + ".zip");
		JsonObject info = new JsonObject();
		info.addProperty("format", 1);
		info.addProperty("mod", InGameBlocks.MOD_ID);
		info.addProperty("id", entry.id());
		info.addProperty("name", entry.name());
		info.addProperty("kind", entry.kind().id());
		info.addProperty("sound", entry.sound());
		try (OutputStream file = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(file)) {
			zip.putNextEntry(new ZipEntry("block.json"));
			zip.write(GSON.toJson(info).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("texture.png"));
			zip.write(entry.png());
			zip.closeEntry();
			for (int face = 0; face < TextureRules.FACE_COUNT; face++) {
				byte[] png = entry.faces().get(face);
				if (png != null) {
					zip.putNextEntry(new ZipEntry("faces/" + TextureRules.FACE_NAMES[face] + ".png"));
					zip.write(png);
					zip.closeEntry();
				}
			}
		}
		return out;
	}

	private static ImportResult importZip(Path source) throws IOException {
		JsonObject info = null;
		byte[] main = null;
		List<byte[]> faces = new ArrayList<>();
		for (int i = 0; i < TextureRules.FACE_COUNT; i++) {
			faces.add(null);
		}
		try (InputStream file = Files.newInputStream(source); ZipInputStream zip = new ZipInputStream(file)) {
			ZipEntry zipEntry;
			while ((zipEntry = zip.getNextEntry()) != null) {
				String name = zipEntry.getName().replace('\\', '/');
				byte[] data = zip.readNBytes((int) MAX_SOURCE_BYTES + 1);
				if (data.length > MAX_SOURCE_BYTES) {
					throw new IOException("too_large");
				}
				if (name.equals("block.json")) {
					info = GSON.fromJson(new String(data, StandardCharsets.UTF_8), JsonObject.class);
				} else if (name.equals("texture.png")) {
					main = data;
				} else if (name.startsWith("faces/") && name.endsWith(".png")) {
					String faceName = name.substring(6, name.length() - 4);
					int face = Arrays.asList(TextureRules.FACE_NAMES).indexOf(faceName);
					if (face >= 0) {
						faces.set(face, data);
					}
				}
			}
		} catch (RuntimeException e) {
			throw new IOException("corrupt", e);
		}
		if (info == null || main == null) {
			throw new IOException("not_block_zip");
		}
		ImageConverter.Result mainResult = ImageConverter.toTexture(main);
		if (mainResult.error() != null) {
			throw new IOException(mainResult.error());
		}
		for (int i = 0; i < faces.size(); i++) {
			if (faces.get(i) != null) {
				ImageConverter.Result r = ImageConverter.toTexture(faces.get(i));
				faces.set(i, r.error() == null ? r.png() : null);
			}
		}
		String name = info.has("name") ? info.get("name").getAsString() : baseName(source);
		BlockKind kind = info.has("kind") ? BlockKind.byIdOr(info.get("kind").getAsString(), BlockKind.CUBE) : BlockKind.CUBE;
		String sound = info.has("sound") ? info.get("sound").getAsString() : "";
		Entry entry = createBlock(name, mainResult.png(), kind, sound, faces);
		return new ImportResult(entry, true, true);
	}

	/** Every PNG the library knows (main pictures and face pictures). */
	public static List<byte[]> allTextures() {
		List<byte[]> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Entry entry : entries) {
			if (!entry.valid()) {
				continue;
			}
			for (byte[] png : entry.allTextures()) {
				if (seen.add(TextureRules.sha256(png))) {
					out.add(png);
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ settings file

	private static JsonObject blockSettings(String id) {
		if (!settings.has("blocks") || !settings.get("blocks").isJsonObject()) {
			settings.add("blocks", new JsonObject());
		}
		JsonObject blocks = settings.getAsJsonObject("blocks");
		if (!blocks.has(id) || !blocks.get(id).isJsonObject()) {
			blocks.add(id, new JsonObject());
		}
		return blocks.getAsJsonObject(id);
	}

	private static void loadSettings() {
		Path file = root.resolve("library.json");
		if (!Files.isRegularFile(file)) {
			settings = new JsonObject();
			settings.addProperty("shareByDefault", false);
			settings.add("blocks", new JsonObject());
			saveSettings();
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject loaded = GSON.fromJson(reader, JsonObject.class);
			settings = loaded != null ? loaded : new JsonObject();
		} catch (Exception e) {
			InGameBlocks.LOGGER.warn("Could not read {}", file, e);
			settings = new JsonObject();
		}
	}

	private static void saveSettings() {
		Path file = root.resolve("library.json");
		try {
			Files.createDirectories(root);
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(settings, writer);
			}
		} catch (IOException e) {
			InGameBlocks.LOGGER.warn("Could not write {}", file, e);
		}
	}

	/** Short error code for an exception (always one of the ingameblocks.error.* keys). */
	public static String errorCode(Throwable error) {
		String message = error.getMessage();
		if (message != null) {
			switch (message) {
				case "empty", "too_large", "not_png", "not_square", "bad_size", "corrupt", "unreadable", "exists",
						"is_webp", "is_ico", "is_html", "missing", "bad_id", "id_taken", "not_block_zip":
					return message;
				default:
					break;
			}
		}
		return "unreadable";
	}
}
