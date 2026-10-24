package dev.norrmi.ingameblocks.client.pack;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.client.ClientState;

/**
 * Adds the generated pack to the client's resource pack list - but only while the player is on a server (or in a
 * world) that has InGameBlocks. It is always enabled (it only contains the assets of this mod), and every resource
 * reload asks it for fresh contents.
 */
public final class DynamicPackSource {
	public static final String PACK_ID = "ingameblocks_server";
	/** Shown as is: the title already says "(Server-side)". */
	private static final PackSource SOURCE = PackSource.create(PackSource.NO_DECORATION, true);

	private DynamicPackSource() {
	}

	/** Called from the mixin every time the pack repository collects its packs. */
	public static void load(Consumer<Pack> consumer) {
		boolean wanted = ClientState.packWanted();
		ClientState.markPackBuilt(wanted);
		if (!wanted) {
			return;
		}
		PackLocationInfo info = new PackLocationInfo(PACK_ID, Component.literal("InGameBlocks (Server-side)"), SOURCE, Optional.empty());
		Pack pack = Pack.readMetaAndCreate(info, new Contents(), PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.TOP, false));
		if (pack != null) {
			consumer.accept(pack);
		} else {
			InGameBlocks.LOGGER.error("Could not create the generated InGameBlocks resource pack");
		}
	}

	private static final class Contents implements Pack.ResourcesSupplier {
		@Override
		public PackMetadataResources openMetadata(PackLocationInfo location) {
			// Only the metadata is needed here, so no files are generated.
			return new DynamicPack(location, Map.of());
		}

		@Override
		public Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata metadata) {
			// Called at every resource reload: take a snapshot of the current blocks.
			return Stream.of(new DynamicPack(location, DynamicPackBuilder.build()));
		}
	}
}
