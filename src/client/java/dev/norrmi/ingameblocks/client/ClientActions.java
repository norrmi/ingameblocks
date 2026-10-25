package dev.norrmi.ingameblocks.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import dev.norrmi.ingameblocks.client.library.LocalLibrary;
import dev.norrmi.ingameblocks.data.BlockInfo;

/** Things the player can do (used by the commands and the screens). */
public final class ClientActions {
	private static boolean menuRequested;
	/** library id -> what we last sent to the server for it (reset on every join). */
	private static final Map<String, String> SENT = new HashMap<>();

	private ClientActions() {
	}

	/** True if the server's entry differs from what the creator has in the library. */
	public static boolean needsRegistration(LocalLibrary.Entry entry, BlockInfo info) {
		return info == null
				|| !LocalLibrary.matches(entry, info)
				|| !info.name().equals(entry.name())
				|| info.shared() != entry.shared()
				|| info.seeThrough() != entry.seeThrough()
				|| (entry.shared() && !info.available());
	}

	/** Re-sends our blocks that the server already knows but that are out of date (new texture, sharing changed, ...). */
	public static void syncOwnBlocks() {
		if (!ClientNetworking.available()) {
			return;
		}
		for (LocalLibrary.Entry entry : LocalLibrary.entries()) {
			if (!entry.valid()) {
				continue;
			}
			BlockInfo info = ClientState.findMine(entry.uid());
			if (info != null && needsRegistration(entry, info)) {
				String state = sentState(entry, info);
				if (!state.equals(SENT.get(entry.uid()))) {
					SENT.put(entry.uid(), state);
					ClientNetworking.sendRegister(entry, 0);
				}
			}
		}
	}

	private static String sentState(LocalLibrary.Entry entry, BlockInfo info) {
		return entry.hash() + "|" + String.join(",", entry.faceHashes()) + "|" + entry.kind().id() + "|" + entry.sound()
				+ "|" + entry.name() + "|" + entry.shared() + "|" + entry.seeThrough() + "|" + (info != null && info.available());
	}

	/** Forget what was sent (new connection). */
	public static void resetSent() {
		SENT.clear();
	}

	/**
	 * Takes {@code count} items of one of the player's own library blocks. The block is added to the world's
	 * registry first if it is not there yet.
	 *
	 * @return null if the request was sent, otherwise an error message
	 */
	public static Component takeOwn(LocalLibrary.Entry entry, int count) {
		if (!ClientNetworking.available()) {
			return Component.translatable("ingameblocks.msg.no_server");
		}
		if (!entry.valid()) {
			return Component.translatable("ingameblocks.msg.bad_texture", Component.translatable("ingameblocks.error." + entry.error()));
		}
		BlockInfo info = ClientState.findMine(entry.uid());
		if (needsRegistration(entry, info)) {
			SENT.put(entry.uid(), sentState(entry, info));
			ClientNetworking.sendRegister(entry, count);
		} else {
			ClientNetworking.sendGive(info.kind().id(), info.index(), count);
		}
		playClick();
		return null;
	}

	/** Takes items of a block that is already in the registry (made by anybody). */
	public static Component takeBlock(BlockInfo info, int count) {
		if (!ClientNetworking.available()) {
			return Component.translatable("ingameblocks.msg.no_server");
		}
		ClientNetworking.sendGive(info.kind().id(), info.index(), count);
		playClick();
		return null;
	}

	/** Re-reads the library folder and tells the server about changes. */
	public static void reloadLibrary() {
		LocalLibrary.scan();
		ClientState.refreshLibraryTextures();
		syncOwnBlocks();
	}

	/** Call after the library was changed by the player (rename, faces, ...). */
	public static void libraryChanged() {
		ClientState.refreshLibraryTextures();
		syncOwnBlocks();
	}

	public static void say(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(message);
		}
	}

	/** The button click sound. */
	public static void playClick() {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	// Screens cannot be opened from inside a chat command (the chat closes itself afterwards),
	// so the request is remembered and handled at the next client tick.
	public static void requestMenu() {
		menuRequested = true;
	}

	public static boolean consumeMenuRequest() {
		boolean requested = menuRequested;
		menuRequested = false;
		return requested;
	}
}
