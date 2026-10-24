package dev.norrmi.ingameblocks.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.block.BlockKind;
import dev.norrmi.ingameblocks.block.Designs;
import dev.norrmi.ingameblocks.data.BlockInfo;
import dev.norrmi.ingameblocks.data.SoundOptions;
import dev.norrmi.ingameblocks.data.TextureRules;

/**
 * Per world / per server table "kind + design number -> block". Only metadata is saved in the world
 * ({@code data/ingameblocks/registry.json}); textures are kept in memory (and optionally on disk).
 * Accessed from the server thread only (reads from the render thread through {@link Designs} are harmless).
 */
public final class ServerRegistry implements Designs.Lookup {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Map<BlockKind, BlockInfo[]> slots = new EnumMap<>(BlockKind.class);
	private final Map<String, byte[]> textures = new HashMap<>();
	private final Path dir;
	private final boolean persistTextures;

	private ServerRegistry(Path dir, boolean persistTextures) {
		this.dir = dir;
		this.persistTextures = persistTextures;
		for (BlockKind kind : BlockKind.values()) {
			this.slots.put(kind, new BlockInfo[kind.capacity()]);
		}
	}

	public static ServerRegistry load(Path dir, boolean persistTextures) {
		ServerRegistry registry = new ServerRegistry(dir, persistTextures);
		registry.readFile();
		if (persistTextures) {
			registry.readTextures();
		}
		return registry;
	}

	// ------------------------------------------------------------------ queries

	@Override
	public BlockInfo get(BlockKind kind, int index) {
		BlockInfo[] array = this.slots.get(kind);
		if (array == null || index < 0 || index >= array.length) {
			return null;
		}
		return array[index];
	}

	/** Entry with the "available" flag filled in (shared, and the server holds every texture of the block). */
	public BlockInfo view(BlockInfo info) {
		boolean all = info.shared();
		if (all) {
			for (String hash : info.allHashes()) {
				if (!this.textures.containsKey(hash)) {
					all = false;
					break;
				}
			}
		}
		return info.withAvailable(all);
	}

	public List<BlockInfo> all() {
		List<BlockInfo> result = new ArrayList<>();
		for (BlockInfo[] array : this.slots.values()) {
			for (BlockInfo info : array) {
				if (info != null) {
					result.add(this.view(info));
				}
			}
		}
		return result;
	}

	public BlockInfo find(String ownerUuid, String localId) {
		for (BlockInfo[] array : this.slots.values()) {
			for (BlockInfo info : array) {
				if (info != null && info.ownerUuid().equals(ownerUuid) && info.localId().equals(localId)) {
					return info;
				}
			}
		}
		return null;
	}

	public int countByOwner(String ownerUuid) {
		int count = 0;
		for (BlockInfo[] array : this.slots.values()) {
			for (BlockInfo info : array) {
				if (info != null && info.ownerUuid().equals(ownerUuid)) {
					count++;
				}
			}
		}
		return count;
	}

	public int used(BlockKind kind) {
		int count = 0;
		for (BlockInfo info : this.slots.get(kind)) {
			if (info != null) {
				count++;
			}
		}
		return count;
	}

	public int used() {
		int count = 0;
		for (BlockKind kind : BlockKind.values()) {
			count += this.used(kind);
		}
		return count;
	}

	public int firstFree(BlockKind kind) {
		BlockInfo[] array = this.slots.get(kind);
		for (int i = 0; i < array.length; i++) {
			if (array[i] == null) {
				return i;
			}
		}
		return -1;
	}

	/** True if a client may be sent the texture with this hash (it belongs to at least one shared block). */
	public boolean isDistributable(String hash) {
		if (!this.textures.containsKey(hash)) {
			return false;
		}
		for (BlockInfo[] array : this.slots.values()) {
			for (BlockInfo info : array) {
				if (info != null && info.shared() && info.allHashes().contains(hash)) {
					return true;
				}
			}
		}
		return false;
	}

	public byte[] texture(String hash) {
		return this.textures.get(hash);
	}

	public boolean hasTexture(String hash) {
		return this.textures.containsKey(hash);
	}

	// ------------------------------------------------------------------ changes

	public void put(BlockInfo info) {
		this.slots.get(info.kind())[info.index()] = info.withAvailable(false);
	}

	public void free(BlockKind kind, int index) {
		BlockInfo[] array = this.slots.get(kind);
		if (index >= 0 && index < array.length) {
			array[index] = null;
		}
	}

	public void putTexture(String hash, byte[] png) {
		this.textures.put(hash, png);
		if (this.persistTextures) {
			try {
				Path folder = this.dir.resolve("textures");
				Files.createDirectories(folder);
				Files.write(folder.resolve(hash + ".png"), png);
			} catch (IOException e) {
				InGameBlocks.LOGGER.warn("Could not store texture {}", hash, e);
			}
		}
	}

	/** Forgets textures that no shared block refers to any more. */
	public void pruneTextures() {
		Set<String> used = new HashSet<>();
		for (BlockInfo[] array : this.slots.values()) {
			for (BlockInfo info : array) {
				if (info != null && info.shared()) {
					used.addAll(info.allHashes());
				}
			}
		}
		List<String> unused = new ArrayList<>();
		for (String hash : this.textures.keySet()) {
			if (!used.contains(hash)) {
				unused.add(hash);
			}
		}
		for (String hash : unused) {
			this.textures.remove(hash);
			if (this.persistTextures) {
				try {
					Files.deleteIfExists(this.dir.resolve("textures").resolve(hash + ".png"));
				} catch (IOException e) {
					InGameBlocks.LOGGER.warn("Could not delete texture {}", hash, e);
				}
			}
		}
	}

	// ------------------------------------------------------------------ persistence

	public void save() {
		JsonArray array = new JsonArray();
		for (BlockInfo[] kindSlots : this.slots.values()) {
			for (BlockInfo info : kindSlots) {
				if (info == null) {
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("kind", info.kind().id());
				o.addProperty("index", info.index());
				o.addProperty("owner", info.ownerUuid());
				o.addProperty("ownerName", info.ownerName());
				o.addProperty("id", info.localId());
				o.addProperty("name", info.name());
				o.addProperty("hash", info.hash());
				JsonArray faces = new JsonArray();
				for (String face : info.faces()) {
					faces.add(face);
				}
				o.add("faces", faces);
				o.addProperty("sound", info.sound());
				o.addProperty("shared", info.shared());
				array.add(o);
			}
		}
		JsonObject root = new JsonObject();
		root.addProperty("version", 2);
		root.add("blocks", array);
		try {
			Files.createDirectories(this.dir);
			Path tmp = this.dir.resolve("registry.json.tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
			Files.move(tmp, this.dir.resolve("registry.json"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			InGameBlocks.LOGGER.error("Could not save the InGameBlocks registry to {}", this.dir, e);
		}
	}

	private void readFile() {
		Path file = this.dir.resolve("registry.json");
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject root = GSON.fromJson(reader, JsonObject.class);
			if (root == null || !root.has("blocks")) {
				return;
			}
			for (JsonElement element : root.getAsJsonArray("blocks")) {
				try {
					JsonObject o = element.getAsJsonObject();
					BlockKind kind = BlockKind.byId(o.has("kind") ? o.get("kind").getAsString() : "cube");
					int index = o.has("index") ? o.get("index").getAsInt() : -1;
					if (kind == null || index < 0 || index >= kind.capacity()) {
						continue;
					}
					List<String> faces = BlockInfo.NO_FACES;
					if (o.has("faces") && o.get("faces").isJsonArray() && o.getAsJsonArray("faces").size() == TextureRules.FACE_COUNT) {
						List<String> read = new ArrayList<>();
						for (JsonElement face : o.getAsJsonArray("faces")) {
							read.add(face.getAsString());
						}
						faces = List.copyOf(read);
					}
					String sound = o.has("sound") ? o.get("sound").getAsString() : "";
					if (!SoundOptions.isValid(sound)) {
						sound = "";
					}
					this.slots.get(kind)[index] = new BlockInfo(kind, index,
							o.get("owner").getAsString(),
							o.has("ownerName") ? o.get("ownerName").getAsString() : "?",
							o.get("id").getAsString(),
							o.get("name").getAsString(),
							o.get("hash").getAsString(),
							faces, sound,
							o.has("shared") && o.get("shared").getAsBoolean(),
							false);
				} catch (RuntimeException e) {
					InGameBlocks.LOGGER.warn("Skipping a broken entry in {}: {}", file, e.toString());
				}
			}
		} catch (Exception e) {
			InGameBlocks.LOGGER.error("Could not read the InGameBlocks registry {}", file, e);
		}
	}

	private void readTextures() {
		Path folder = this.dir.resolve("textures");
		if (!Files.isDirectory(folder)) {
			return;
		}
		try (Stream<Path> files = Files.list(folder)) {
			for (Path file : files.toList()) {
				String fileName = file.getFileName().toString();
				if (!fileName.endsWith(".png")) {
					continue;
				}
				String hash = fileName.substring(0, fileName.length() - 4);
				byte[] data = Files.readAllBytes(file);
				if (TextureRules.isValidHash(hash) && TextureRules.sha256(data).equals(hash)) {
					this.textures.put(hash, data);
				}
			}
		} catch (IOException e) {
			InGameBlocks.LOGGER.warn("Could not read stored textures from {}", folder, e);
		}
	}
}
