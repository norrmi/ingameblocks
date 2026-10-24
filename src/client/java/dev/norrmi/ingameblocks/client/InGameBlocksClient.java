package dev.norrmi.ingameblocks.client;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLScancode;

import net.minecraft.client.KeyMapping;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.client.gui.BlocksScreen;
import dev.norrmi.ingameblocks.client.library.LocalLibrary;

public class InGameBlocksClient implements ClientModInitializer {
	private static KeyMapping openMenuKey;

	@Override
	public void onInitializeClient() {
		LocalLibrary.init();
		ClientState.init();
		ClientNetworking.init();
		IgbCommands.register();

		KeyMapping.Category category = KeyMapping.Category.register(InGameBlocks.id("main"));
		openMenuKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.ingameblocks.open_menu", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_B, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientState.tick(client);

			boolean open = ClientActions.consumeMenuRequest();
			while (openMenuKey.consumeClick()) {
				open = true;
			}
			if (open && client.level != null && client.gui.screen() == null) {
				client.gui.setScreen(new BlocksScreen());
			}
		});
	}
}
