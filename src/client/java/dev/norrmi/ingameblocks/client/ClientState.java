package dev.norrmi.ingameblocks.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.block.BlockKind;
import dev.norrmi.ingameblocks.block.Designs;
import dev.norrmi.ingameblocks.client.library.LocalLibrary;
import dev.norrmi.ingameblocks.data.BlockInfo;
import dev.norrmi.ingameblocks.data.PngCodec;
import dev.norrmi.ingameblocks.data.TextureRules;
import dev.norrmi.ingameblocks.network.Payloads;

/**
 * Everything the client knows about the blocks of the server / world it is currently in,
 * plus the texture store (downloaded textures, own library, on-disk cache).
 * Used from the client thread only (except {@link #reloading}).
 */
public final class ClientState {
	private static final Map<String, BlockInfo> ENTRIES = new TreeMap<>();
	private static final Map<BlockKind, BlockInfo[]> BY_KIND = new EnumMap<>(BlockKind.class);
	/** Textures received from the server in this session (hash -> PNG). */
	private static final Map<String, byte[]> DOWNLOADED = new HashMap<>();
	private static Map<String, byte[]> libraryTextures = Map.of();
	private static final Set<String> REQUESTED = new HashSet<>();
	private static final Set<String> NOT_ON_DISK = new HashSet<>();

	/** True while connected to a server (or world) that has InGameBlocks. The generated resource pack only exists then. */
	private static boolean modServer;
	/** What the currently loaded resource pack was built from. */
	private static boolean packActive;

	private static int version = 0;
	private static String lastBuilt = "";
	private static String pendingSignature = "";
	private static int stableChecks = 0;
	private static int tickCounter = 0;
	private static volatile boolean reloading = false;

	static {
		for (BlockKind kind : BlockKind.values()) {
			BY_KIND.put(kind, new BlockInfo[kind.capacity()]);
		}
	}

	private ClientState() {
	}

	public static void init() {
		refreshLibraryTextures();
		Designs.setClient(ClientState::get);
		lastBuilt = signature();
	}

	/** Increases whenever something changes that a screen may want to show. */
	public static int version() {
		return version;
	}

	public static String myUuid() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.player == null ? null : minecraft.player.getUUID().toString();
	}

	public static boolean modServer() {
		return modServer;
	}

	/** Asked by the pack source when the resource packs are collected. */
	public static boolean packWanted() {
		return modServer;
	}

	public static void markPackBuilt(boolean active) {
		packActive = active;
	}

	public static boolean packActive() {
		return packActive;
	}

	// ------------------------------------------------------------------ registry

	public static Collection<BlockInfo> entries() {
		return ENTRIES.values();
	}

	public static BlockInfo get(BlockKind kind, int index) {
		BlockInfo[] array = BY_KIND.get(kind);
		return index >= 0 && index < array.length ? array[index] : null;
	}

	public static BlockInfo findMine(String localId) {
		String me = myUuid();
		if (me == null) {
			return null;
		}
		for (BlockInfo info : ENTRIES.values()) {
			if (info.ownerUuid().equals(me) && info.localId().equals(localId)) {
				return info;
			}
		}
		return null;
	}

	public static boolean isMine(BlockInfo info) {
		String me = myUuid();
		return me != null && me.equals(info.ownerUuid());
	}

	private static void clearEntries() {
		ENTRIES.clear();
		for (BlockInfo[] array : BY_KIND.values()) {
			java.util.Arrays.fill(array, null);
		}
	}

	public static void onJoin(boolean serverHasMod) {
		ClientActions.resetSent();
		clearEntries();
		REQUESTED.clear();
		NOT_ON_DISK.clear();
		DOWNLOADED.clear();
		modServer = serverHasMod;
		version++;
	}

	public static void onDisconnect() {
		clearEntries();
		REQUESTED.clear();
		modServer = false;
		version++;
	}

	public static void applyRegistry(Payloads.RegistryUpdate update) {
		modServer = true;
		if (update.full()) {
			clearEntries();
			REQUESTED.clear();
		}
		for (BlockInfo info : update.entries()) {
			if (info.index() < 0 || info.index() >= info.kind().capacity()) {
				continue;
			}
			BlockInfo old = get(info.kind(), info.index());
			if (old != null) {
				REQUESTED.removeAll(old.allHashes());
			}
			REQUESTED.removeAll(info.allHashes());
			ENTRIES.put(info.slotKey(), info);
			BY_KIND.get(info.kind())[info.index()] = info;
		}
		version++;
		ClientActions.syncOwnBlocks();
		requestMissingTextures();
	}

	/** Asks the server for textures of shared blocks that we do not have yet. */
	public static void requestMissingTextures() {
		List<String> missing = new ArrayList<>();
		for (BlockInfo info : ENTRIES.values()) {
			if (!info.shared() || !info.available()) {
				continue;
			}
			for (String hash : info.allHashes()) {
				if (TextureRules.isValidHash(hash) && texture(hash) == null && REQUESTED.add(hash)) {
					missing.add(hash);
				}
			}
		}
		for (int i = 0; i < missing.size(); i += InGameBlocks.MAX_REQUEST_HASHES) {
			ClientNetworking.sendRequest(new ArrayList<>(missing.subList(i, Math.min(missing.size(), i + InGameBlocks.MAX_REQUEST_HASHES))));
		}
	}

	// ------------------------------------------------------------------ textures

	public static void refreshLibraryTextures() {
		Map<String, byte[]> map = new HashMap<>();
		for (byte[] png : LocalLibrary.allTextures()) {
			map.put(TextureRules.sha256(png), png);
		}
		libraryTextures = map;
		version++;
	}

	/** PNG bytes for a hash from any source we have, or null. */
	public static byte[] texture(String hash) {
		if (!TextureRules.isValidHash(hash)) {
			return null;
		}
		byte[] data = DOWNLOADED.get(hash);
		if (data != null) {
			return data;
		}
		data = libraryTextures.get(hash);
		if (data != null) {
			return data;
		}
		if (NOT_ON_DISK.contains(hash)) {
			return null;
		}
		Path file = LocalLibrary.cacheDir().resolve(hash + ".png");
		if (Files.isRegularFile(file)) {
			try {
				byte[] cached = Files.readAllBytes(file);
				if (TextureRules.validatePng(cached, InGameBlocks.MAX_TEXTURE_BYTES) == null && TextureRules.sha256(cached).equals(hash)) {
					DOWNLOADED.put(hash, cached);
					return cached;
				}
				Files.deleteIfExists(file);
			} catch (IOException e) {
				InGameBlocks.LOGGER.warn("Could not read cached texture {}", file, e);
			}
		}
		NOT_ON_DISK.add(hash);
		return null;
	}

	/** Validates and stores a texture received from the server. */
	public static boolean acceptTexture(String hash, byte[] png) {
		if (!TextureRules.isValidHash(hash)
				|| TextureRules.validatePng(png, InGameBlocks.MAX_TEXTURE_BYTES) != null
				|| !TextureRules.sha256(png).equals(hash)) {
			return false;
		}
		try {
			PngCodec.decode(png);
		} catch (IOException e) {
			return false;
		}
		DOWNLOADED.put(hash, png);
		NOT_ON_DISK.remove(hash);
		try {
			Files.createDirectories(LocalLibrary.cacheDir());
			Files.write(LocalLibrary.cacheDir().resolve(hash + ".png"), png);
		} catch (IOException e) {
			InGameBlocks.LOGGER.warn("Could not cache texture {}", hash, e);
		}
		version++;
		return true;
	}

	/** May the texture of this block be shown here? (shared, or my own) */
	public static boolean visible(BlockInfo info) {
		return info.shared() || isMine(info);
	}

	/** The texture shown on one face of a block (index into TextureRules.FACE_NAMES), or null for the placeholder. */
	public static byte[] displayTexture(BlockInfo info, int face) {
		return visible(info) ? texture(info.faceHash(face)) : null;
	}

	/** The main texture of a block, or null for the placeholder. */
	public static byte[] displayTexture(BlockInfo info) {
		return visible(info) ? texture(info.hash()) : null;
	}

	// ------------------------------------------------------------------ resource reloading

	/** Describes what the generated resource pack would contain right now. */
	public static String signature() {
		StringBuilder sb = new StringBuilder();
		sb.append(modServer).append('\n');
		for (BlockInfo info : ENTRIES.values()) {
			sb.append(info.slotKey()).append('|').append(info.name());
			sb.append('|').append(displayTexture(info) != null ? info.hash() : "-");
			for (int face = 0; face < TextureRules.FACE_COUNT; face++) {
				sb.append('|').append(displayTexture(info, face) != null ? info.faceHash(face) : "-");
			}
			sb.append('\n');
		}
		return TextureRules.sha256(sb.toString());
	}

	/** Called every client tick. Reloads resources when the set of visible blocks has changed. */
	public static void tick(Minecraft minecraft) {
		if (minecraft.level == null || reloading) {
			return;
		}
		if (++tickCounter % 10 != 0) {
			return;
		}
		String current = signature();
		if (current.equals(lastBuilt)) {
			pendingSignature = "";
			stableChecks = 0;
			return;
		}
		if (current.equals(pendingSignature)) {
			stableChecks++;
		} else {
			pendingSignature = current;
			stableChecks = 0;
		}
		if (stableChecks >= 2) {
			lastBuilt = current;
			pendingSignature = "";
			stableChecks = 0;
			reloading = true;
			if (minecraft.player != null && (modServer || packActive)) {
				minecraft.player.sendSystemMessage(Component.translatable("ingameblocks.msg.reloading"));
			}
			try {
				minecraft.reloadResourcePacks().whenComplete((result, error) -> reloading = false);
			} catch (RuntimeException e) {
				reloading = false;
				InGameBlocks.LOGGER.error("Could not reload the resources", e);
			}
		}
	}
}
