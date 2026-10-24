package dev.norrmi.ingameblocks.client;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.norrmi.ingameblocks.client.library.LocalLibrary;
import dev.norrmi.ingameblocks.network.Payloads;

/** Client side of the mod's network protocol. */
public final class ClientNetworking {
	private static boolean inWorld;

	private ClientNetworking() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(Payloads.RegistryUpdate.TYPE, (payload, context) -> ClientState.applyRegistry(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.TextureData.TYPE, (payload, context) -> ClientState.acceptTexture(payload.hash(), payload.png()));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			inWorld = true;
			ClientState.onJoin(ClientPlayNetworking.canSend(Payloads.RegisterBlock.TYPE));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			inWorld = false;
			ClientState.onDisconnect();
		});
	}

	/** True if we are in a world / on a server that has InGameBlocks. */
	public static boolean available() {
		return inWorld && ClientPlayNetworking.canSend(Payloads.RegisterBlock.TYPE);
	}

	public static void sendRegister(LocalLibrary.Entry entry, int give) {
		if (!available() || !entry.valid()) {
			return;
		}
		List<byte[]> textures = entry.shared() ? entry.allTextures() : new ArrayList<>();
		ClientPlayNetworking.send(new Payloads.RegisterBlock(entry.uid(), entry.name(), entry.kind().id(), entry.sound(),
				entry.hash(), entry.faceHashes(), entry.shared(), textures, give));
	}

	public static void sendGive(String kind, int index, int count) {
		if (available()) {
			ClientPlayNetworking.send(new Payloads.GiveBlock(kind, index, count));
		}
	}

	public static void sendRequest(List<String> hashes) {
		if (available() && !hashes.isEmpty()) {
			ClientPlayNetworking.send(new Payloads.RequestTextures(hashes));
		}
	}
}
