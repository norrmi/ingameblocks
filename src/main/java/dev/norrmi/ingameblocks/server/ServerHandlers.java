package dev.norrmi.ingameblocks.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.block.BlockKind;
import dev.norrmi.ingameblocks.block.Designs;
import dev.norrmi.ingameblocks.data.BlockInfo;
import dev.norrmi.ingameblocks.data.SoundOptions;
import dev.norrmi.ingameblocks.data.TextureRules;
import dev.norrmi.ingameblocks.network.Payloads;

/** Everything that runs on the (dedicated or integrated) server. */
public final class ServerHandlers {
	private static MinecraftServer server;
	private static ServerRegistry registry;

	private ServerHandlers() {
	}

	public static void init() {
		ServerConfig.load();

		ServerLifecycleEvents.SERVER_STARTING.register(s -> {
			server = s;
			Path dir = s.getWorldPath(LevelResource.DATA).resolve(InGameBlocks.MOD_ID);
			registry = ServerRegistry.load(dir, ServerConfig.get().persistSharedTextures);
			Designs.setServer(registry);
			InGameBlocks.LOGGER.info("InGameBlocks registry loaded: {} blocks", registry.used());
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			if (registry != null) {
				registry.save();
			}
			Designs.setServer(null);
			registry = null;
			server = null;
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> sendFullSync(handler.getPlayer()));

		ServerPlayNetworking.registerGlobalReceiver(Payloads.RegisterBlock.TYPE, (payload, context) -> handleRegister(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.RequestTextures.TYPE, (payload, context) -> handleRequest(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.GiveBlock.TYPE, (payload, context) -> handleGive(context.player(), payload));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(Commands.literal("igbadmin")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.then(Commands.literal("list").executes(context -> adminList(context.getSource())))
				.then(Commands.literal("free")
						.then(Commands.argument("kind", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
										java.util.Arrays.stream(BlockKind.values()).map(BlockKind::id), builder))
								.then(Commands.argument("design", IntegerArgumentType.integer(0, 1023))
										.executes(context -> adminFree(context.getSource(),
												StringArgumentType.getString(context, "kind"),
												IntegerArgumentType.getInteger(context, "design"))))))));
	}

	// ------------------------------------------------------------------ helpers

	private static boolean isOp(ServerPlayer player) {
		return player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static void message(ServerPlayer player, String key, Object... args) {
		player.sendSystemMessage(Component.translatable(key, args));
	}

	private static void send(ServerPlayer player, Payloads.RegistryUpdate update) {
		if (ServerPlayNetworking.canSend(player, Payloads.RegistryUpdate.TYPE)) {
			ServerPlayNetworking.send(player, update);
		}
	}

	private static void sendFullSync(ServerPlayer player) {
		if (registry != null) {
			send(player, new Payloads.RegistryUpdate(true, registry.all()));
		}
	}

	private static void broadcast(Payloads.RegistryUpdate update) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			send(player, update);
		}
	}

	// ------------------------------------------------------------------ packets from clients

	private static void handleRegister(ServerPlayer player, Payloads.RegisterBlock packet) {
		ServerRegistry reg = registry;
		if (reg == null) {
			return;
		}
		ServerConfig config = ServerConfig.get();
		boolean op = isOp(player);

		if (config.publishRequiresOp && !op) {
			message(player, "ingameblocks.msg.no_permission");
			return;
		}

		String localId = packet.localId();
		String name = TextureRules.cleanName(packet.name());
		String hash = packet.hash();
		BlockKind kind = BlockKind.byId(packet.kind());
		String sound = packet.sound();
		if (!TextureRules.isValidLocalId(localId) || name.isEmpty() || !TextureRules.isValidHash(hash) || kind == null
				|| !SoundOptions.isValid(sound) || packet.faces().size() != TextureRules.FACE_COUNT) {
			message(player, "ingameblocks.msg.invalid_block");
			return;
		}
		// A face that uses the main texture is stored as "" so that every block has exactly one representation.
		List<String> faces = new ArrayList<>(TextureRules.FACE_COUNT);
		for (String face : packet.faces()) {
			if (!TextureRules.isValidFaceHash(face)) {
				message(player, "ingameblocks.msg.invalid_block");
				return;
			}
			faces.add(face.equals(hash) ? "" : face);
		}
		faces = List.copyOf(faces);

		String ownerUuid = player.getUUID().toString();
		BlockInfo existing = reg.find(ownerUuid, localId);
		int index;
		boolean moved = false;
		if (existing != null && existing.kind() == kind) {
			index = existing.index();
		} else {
			if (existing == null && !op && reg.countByOwner(ownerUuid) >= config.maxBlocksPerPlayer) {
				message(player, "ingameblocks.msg.limit", config.maxBlocksPerPlayer);
				return;
			}
			index = reg.firstFree(kind);
			if (index < 0) {
				message(player, "ingameblocks.msg.no_slots", Component.translatable(kind.translationKey()));
				return;
			}
			moved = existing != null;
		}

		BlockInfo info = new BlockInfo(kind, index, ownerUuid, player.getName().getString(), localId, name, hash, faces,
				sound, packet.shared(), false);

		// Shared blocks must come with every texture they need (or the server must already have it).
		Map<String, byte[]> provided = new HashMap<>();
		if (packet.shared()) {
			for (byte[] png : packet.textures()) {
				String error = TextureRules.validatePng(png, config.effectiveMaxTextureBytes());
				if (error != null) {
					message(player, "ingameblocks.msg.bad_texture", Component.translatable("ingameblocks.error." + error));
					return;
				}
				provided.put(TextureRules.sha256(png), png);
			}
			for (String needed : info.allHashes()) {
				if (!provided.containsKey(needed) && !reg.hasTexture(needed)) {
					message(player, "ingameblocks.msg.bad_texture", Component.translatable("ingameblocks.error.missing"));
					return;
				}
			}
		}

		if (moved) {
			reg.free(existing.kind(), existing.index());
		}
		reg.put(info);
		if (packet.shared()) {
			for (String needed : info.allHashes()) {
				byte[] png = provided.get(needed);
				if (png != null) {
					reg.putTexture(needed, png);
				}
			}
		}
		reg.pruneTextures();
		reg.save();

		if (moved) {
			broadcast(new Payloads.RegistryUpdate(true, reg.all()));
		} else {
			broadcast(new Payloads.RegistryUpdate(false, List.of(reg.view(info))));
		}
		if (existing == null) {
			message(player, "ingameblocks.msg.registered", name);
		}

		if (packet.give() > 0) {
			give(player, kind, index, packet.give());
		}
	}

	private static void handleRequest(ServerPlayer player, Payloads.RequestTextures packet) {
		ServerRegistry reg = registry;
		if (reg == null) {
			return;
		}
		Set<String> unique = new LinkedHashSet<>(packet.hashes());
		for (String hash : unique) {
			if (!TextureRules.isValidHash(hash) || !reg.isDistributable(hash)) {
				continue;
			}
			if (ServerPlayNetworking.canSend(player, Payloads.TextureData.TYPE)) {
				ServerPlayNetworking.send(player, new Payloads.TextureData(hash, reg.texture(hash)));
			}
		}
	}

	private static void handleGive(ServerPlayer player, Payloads.GiveBlock packet) {
		BlockKind kind = BlockKind.byId(packet.kind());
		if (kind != null) {
			give(player, kind, packet.index(), packet.count());
		}
	}

	private static void give(ServerPlayer player, BlockKind kind, int index, int count) {
		ServerRegistry reg = registry;
		if (reg == null) {
			return;
		}
		BlockInfo info = reg.get(kind, index);
		if (info == null) {
			message(player, "ingameblocks.msg.unknown_block");
			return;
		}
		if (!(player.isCreative() || isOp(player) || ServerConfig.get().allowGiveInSurvival)) {
			message(player, "ingameblocks.msg.give_denied");
			return;
		}
		int amount = Math.max(1, Math.min(64, count));
		ItemStack stack = Designs.stack(kind, index, amount);
		if (player.getInventory().add(stack)) {
			message(player, "ingameblocks.msg.given", amount, info.name());
		} else {
			message(player, "ingameblocks.msg.inventory_full");
		}
	}

	// ------------------------------------------------------------------ /igbadmin

	private static int adminList(CommandSourceStack source) {
		ServerRegistry reg = registry;
		if (reg == null) {
			return 0;
		}
		Map<String, Integer> perOwner = new TreeMap<>();
		for (BlockInfo info : reg.all()) {
			perOwner.merge(info.ownerName(), 1, Integer::sum);
		}
		List<String> owners = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : perOwner.entrySet()) {
			owners.add(entry.getKey() + ": " + entry.getValue());
		}
		List<String> kinds = new ArrayList<>();
		for (BlockKind kind : BlockKind.values()) {
			kinds.add(kind.id() + " " + reg.used(kind) + "/" + kind.capacity());
		}
		String text = "InGameBlocks: " + reg.used() + " blocks (" + String.join(", ", kinds) + ")"
				+ (owners.isEmpty() ? "" : "\nBy player: " + String.join(", ", owners));
		source.sendSuccess(() -> Component.literal(text), false);
		return reg.used();
	}

	private static int adminFree(CommandSourceStack source, String kindId, int index) {
		ServerRegistry reg = registry;
		if (reg == null) {
			return 0;
		}
		BlockKind kind = BlockKind.byId(kindId);
		if (kind == null) {
			source.sendFailure(Component.literal("Unknown kind: " + kindId));
			return 0;
		}
		BlockInfo info = reg.get(kind, index);
		if (info == null) {
			source.sendFailure(Component.literal(kind.id() + " #" + index + " is already free"));
			return 0;
		}
		reg.free(kind, index);
		reg.pruneTextures();
		reg.save();
		broadcast(new Payloads.RegistryUpdate(true, reg.all()));
		source.sendSuccess(() -> Component.literal("Freed " + kind.id() + " #" + index + " (" + info.name() + " by " + info.ownerName()
				+ "). Blocks of this design that are already placed will show whatever gets this number next."), true);
		return 1;
	}
}
