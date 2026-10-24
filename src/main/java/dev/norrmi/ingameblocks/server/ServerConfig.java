package dev.norrmi.ingameblocks.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import dev.norrmi.ingameblocks.InGameBlocks;

/**
 * Server side settings, stored in {@code config/ingameblocks-server.json}.
 * (In single player the integrated server uses the same file.)
 */
public final class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ServerConfig instance = new ServerConfig();

	/** Max blocks one (non-op) player may create on this server / world. */
	public int maxBlocksPerPlayer = 64;
	/** If true only operators may add blocks. */
	public boolean publishRequiresOp = false;
	/** If true players in survival mode may take custom blocks too (otherwise creative mode or op is required). */
	public boolean allowGiveInSurvival = false;
	/**
	 * If true the server stores the textures of shared blocks in the world folder, so they stay available when the
	 * creator is offline and after a restart. If false (default) the textures only live on the creators' computers and
	 * are re-sent when the creator joins.
	 */
	public boolean persistSharedTextures = false;
	/** Max size of one texture file in bytes (can only lower the built-in limit). */
	public int maxTextureBytes = InGameBlocks.MAX_TEXTURE_BYTES;

	public static ServerConfig get() {
		return instance;
	}

	public int effectiveMaxTextureBytes() {
		return Math.max(1024, Math.min(this.maxTextureBytes, InGameBlocks.MAX_TEXTURE_BYTES));
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("ingameblocks-server.json");
		ServerConfig loaded = null;
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, ServerConfig.class);
			} catch (Exception e) {
				InGameBlocks.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		instance = loaded != null ? loaded : new ServerConfig();
		// Write the file back so that new options appear in it.
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			InGameBlocks.LOGGER.warn("Could not write {}", file, e);
		}
	}
}
