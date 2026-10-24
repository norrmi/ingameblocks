package dev.norrmi.ingameblocks.client;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import dev.norrmi.ingameblocks.client.library.LocalLibrary;
import dev.norrmi.ingameblocks.data.BlockInfo;

/** {@code /ingameblocks} (alias {@code /igb}) - client side commands. */
public final class IgbCommands {
	private static final SuggestionProvider<FabricClientCommandSource> OWN_BLOCKS = (context, builder) ->
			SharedSuggestionProvider.suggest(LocalLibrary.entries().stream().map(LocalLibrary.Entry::id), builder);

	/** Own library ids plus the ids of blocks other players added to this world. */
	private static final SuggestionProvider<FabricClientCommandSource> ANY_BLOCK = (context, builder) -> {
		List<String> ids = new ArrayList<>();
		for (LocalLibrary.Entry entry : LocalLibrary.entries()) {
			ids.add(entry.id());
		}
		for (BlockInfo info : ClientState.entries()) {
			if (!ClientState.isMine(info) && !ids.contains(info.localId())) {
				ids.add(info.localId());
			}
		}
		return SharedSuggestionProvider.suggest(ids, builder);
	};

	private IgbCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			LiteralCommandNode<FabricClientCommandSource> node = dispatcher.register(ClientCommands.literal("ingameblocks")
					.executes(context -> openMenu())
					.then(ClientCommands.literal("menu").executes(context -> openMenu()))
					.then(ClientCommands.literal("folder").executes(context -> openFolder(context.getSource())))
					.then(ClientCommands.literal("reload").executes(context -> reload(context.getSource())))
					.then(ClientCommands.literal("list").executes(context -> list(context.getSource())))
					.then(ClientCommands.literal("give")
							.then(ClientCommands.argument("block", StringArgumentType.word())
									.suggests(ANY_BLOCK)
									.executes(context -> give(context.getSource(), StringArgumentType.getString(context, "block"), 1))
									.then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 64))
											.executes(context -> give(context.getSource(),
													StringArgumentType.getString(context, "block"),
													IntegerArgumentType.getInteger(context, "count"))))))
					.then(ClientCommands.literal("share")
							.then(ClientCommands.argument("block", StringArgumentType.word())
									.suggests(OWN_BLOCKS)
									.then(ClientCommands.argument("shared", BoolArgumentType.bool())
											.executes(context -> share(context.getSource(),
													StringArgumentType.getString(context, "block"),
													BoolArgumentType.getBool(context, "shared"))))))
					.then(ClientCommands.literal("rename")
							.then(ClientCommands.argument("block", StringArgumentType.word())
									.suggests(OWN_BLOCKS)
									.then(ClientCommands.argument("name", StringArgumentType.greedyString())
											.executes(context -> rename(context.getSource(),
													StringArgumentType.getString(context, "block"),
													StringArgumentType.getString(context, "name"))))))
					.then(ClientCommands.literal("export")
							.then(ClientCommands.argument("block", StringArgumentType.word())
									.suggests(OWN_BLOCKS)
									.executes(context -> export(context.getSource(), StringArgumentType.getString(context, "block")))))
					.then(ClientCommands.literal("import")
							.then(ClientCommands.argument("file", StringArgumentType.string())
									.executes(context -> importFile(context.getSource(), StringArgumentType.getString(context, "file"), null))
									.then(ClientCommands.argument("name", StringArgumentType.greedyString())
											.executes(context -> importFile(context.getSource(),
													StringArgumentType.getString(context, "file"),
													StringArgumentType.getString(context, "name")))))));

			dispatcher.register(ClientCommands.literal("igb").executes(context -> openMenu()).redirect(node));
		});
	}

	private static int openMenu() {
		ClientActions.requestMenu();
		return 1;
	}

	private static int openFolder(FabricClientCommandSource source) {
		Blaze3D.openPath(LocalLibrary.blocksDir());
		source.sendFeedback(Component.translatable("ingameblocks.cmd.folder", LocalLibrary.blocksDir().toString()));
		return 1;
	}

	private static int reload(FabricClientCommandSource source) {
		ClientActions.reloadLibrary();
		long invalid = LocalLibrary.entries().stream().filter(entry -> !entry.valid()).count();
		source.sendFeedback(Component.translatable("ingameblocks.cmd.reloaded", LocalLibrary.entries().size(), invalid));
		return 1;
	}

	private static int list(FabricClientCommandSource source) {
		if (LocalLibrary.entries().isEmpty()) {
			source.sendFeedback(Component.translatable("ingameblocks.cmd.list.empty", LocalLibrary.blocksDir().toString()));
			return 0;
		}
		source.sendFeedback(Component.translatable("ingameblocks.cmd.list.header", LocalLibrary.entries().size()));
		for (LocalLibrary.Entry entry : LocalLibrary.entries()) {
			Component status;
			if (!entry.valid()) {
				status = Component.translatable("ingameblocks.error." + entry.error()).withStyle(ChatFormatting.RED);
			} else {
				BlockInfo info = ClientState.findMine(entry.uid());
				Component sharing = Component.translatable(entry.shared() ? "ingameblocks.status.shared" : "ingameblocks.status.private");
				Component world = Component.translatable(info != null ? "ingameblocks.status.in_world" : "ingameblocks.status.not_in_world");
				status = Component.empty().append(Component.translatable(entry.kind().translationKey())).append(Component.literal(", "))
						.append(sharing).append(Component.literal(", ")).append(world).withStyle(ChatFormatting.GRAY);
			}
			source.sendFeedback(Component.literal(" - " + entry.id() + " (" + entry.name() + "): ").append(status));
		}
		return LocalLibrary.entries().size();
	}

	private static int give(FabricClientCommandSource source, String id, int count) {
		LocalLibrary.Entry entry = LocalLibrary.find(id);
		Component error;
		if (entry != null) {
			error = ClientActions.takeOwn(entry, count);
		} else {
			BlockInfo other = null;
			for (BlockInfo info : ClientState.entries()) {
				if (info.localId().equals(id)) {
					other = info;
					break;
				}
			}
			if (other == null) {
				source.sendError(Component.translatable("ingameblocks.cmd.unknown_block", id));
				return 0;
			}
			error = ClientActions.takeBlock(other, count);
		}
		if (error != null) {
			source.sendError(error);
			return 0;
		}
		return 1;
	}

	private static int share(FabricClientCommandSource source, String id, boolean shared) {
		LocalLibrary.Entry entry = LocalLibrary.find(id);
		if (entry == null) {
			source.sendError(Component.translatable("ingameblocks.cmd.unknown_block", id));
			return 0;
		}
		LocalLibrary.setShared(id, shared);
		ClientActions.libraryChanged();
		source.sendFeedback(Component.translatable(shared ? "ingameblocks.cmd.share.on" : "ingameblocks.cmd.share.off", entry.name()));
		return 1;
	}

	private static int rename(FabricClientCommandSource source, String id, String name) {
		LocalLibrary.Entry entry = LocalLibrary.find(id);
		if (entry == null) {
			source.sendError(Component.translatable("ingameblocks.cmd.unknown_block", id));
			return 0;
		}
		LocalLibrary.rename(id, name);
		ClientActions.libraryChanged();
		LocalLibrary.Entry renamed = LocalLibrary.find(id);
		source.sendFeedback(Component.translatable("ingameblocks.cmd.renamed", renamed != null ? renamed.name() : name));
		return 1;
	}

	private static int export(FabricClientCommandSource source, String id) {
		try {
			Path file = LocalLibrary.exportZip(id);
			source.sendFeedback(Component.translatable("ingameblocks.cmd.exported", file.toString()));
			Blaze3D.openPath(LocalLibrary.exportsDir());
			return 1;
		} catch (IOException e) {
			source.sendError(Component.translatable("ingameblocks.cmd.export_failed", Component.translatable("ingameblocks.error." + LocalLibrary.errorCode(e))));
			return 0;
		}
	}

	private static int importFile(FabricClientCommandSource source, String file, String name) {
		try {
			Path path = Paths.get(file);
			LocalLibrary.ImportResult result = LocalLibrary.importFile(path, name);
			ClientActions.libraryChanged();
			LocalLibrary.Entry entry = result.entry();
			String key = result.created() ? "ingameblocks.cmd.imported" : result.changed() ? "ingameblocks.cmd.updated" : "ingameblocks.cmd.unchanged";
			source.sendFeedback(Component.translatable(key, entry.name(), entry.id()));
			return 1;
		} catch (IOException | RuntimeException e) {
			source.sendError(Component.translatable("ingameblocks.cmd.import_failed",
					Component.translatable("ingameblocks.error." + LocalLibrary.errorCode(e))));
			return 0;
		}
	}
}
