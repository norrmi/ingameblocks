package dev.norrmi.ingameblocks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;

import dev.norrmi.ingameblocks.block.ModBlocks;
import dev.norrmi.ingameblocks.network.Payloads;
import dev.norrmi.ingameblocks.server.ServerHandlers;

/**
 * InGameBlocks by norrmi.
 *
 * <p>Minecraft does not allow new block ids while the game is running, so the mod registers one block per
 * <i>shape</i> ({@code ingameblocks:cube}, {@code ingameblocks:slab}, ...). Each of them has a {@code design}
 * block state property; a design number is bound at run time to a player-made block (name, textures, sound).
 * The textures live on the creator's computer and are only sent to other players if the creator shares them.
 */
public class InGameBlocks implements ModInitializer {
	public static final String MOD_ID = "ingameblocks";
	public static final Logger LOGGER = LoggerFactory.getLogger("InGameBlocks");

	/** Hard upper limit for one PNG file (bytes). */
	public static final int MAX_TEXTURE_BYTES = 256 * 1024;
	/** One block needs at most the main texture + 6 different face textures. */
	public static final int MAX_TEXTURES_PER_BLOCK = 7;
	public static final int MAX_NAME_LENGTH = 48;
	public static final int MAX_ID_LENGTH = 40;
	/** Max number of texture hashes a client may request in one packet. */
	public static final int MAX_REQUEST_HASHES = 64;

	@Override
	public void onInitialize() {
		ModBlocks.init();
		Payloads.register();
		ServerHandlers.init();
		LOGGER.info("InGameBlocks loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
