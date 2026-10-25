package dev.norrmi.ingameblocks.block;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import dev.norrmi.ingameblocks.data.BlockInfo;
import dev.norrmi.ingameblocks.data.SoundOptions;

/**
 * Gives blocks and items access to "what is design N of kind K". The server registry (if this game runs one) wins over
 * the client's copy; in single player both are the same.
 */
public final class Designs {
	/** Read-only view of a registry. */
	public interface Lookup {
		BlockInfo get(BlockKind kind, int index);
	}

	private static volatile Lookup server;
	private static volatile Lookup client;

	private Designs() {
	}

	public static void setServer(Lookup lookup) {
		server = lookup;
	}

	public static void setClient(Lookup lookup) {
		client = lookup;
	}

	public static BlockInfo get(BlockKind kind, int index) {
		Lookup s = server;
		if (s != null) {
			BlockInfo info = s.get(kind, index);
			if (info != null) {
				return info;
			}
		}
		Lookup c = client;
		return c != null ? c.get(kind, index) : null;
	}

	/** The sound of a placed block. */
	public static SoundType sound(BlockKind kind, int index, SoundType fallback) {
		BlockInfo info = get(kind, index);
		if (info != null && !info.sound().isEmpty()) {
			SoundType chosen = SoundOptions.get(info.sound());
			if (chosen != null) {
				return chosen;
			}
		}
		return fallback;
	}

	public static String translationKey(BlockKind kind, int index) {
		return "ingameblocks.design." + kind.id() + "." + index;
	}

	/** Name shown for an item / block of this design. */
	public static Component name(BlockKind kind, int index) {
		BlockInfo info = get(kind, index);
		String fallback = info != null ? info.name() : Component.translatable(kind.translationKey()).getString() + " #" + index;
		return Component.translatableWithFallback(translationKey(kind, index), fallback);
	}

	/** An item stack that places exactly this design. */
	public static ItemStack stack(BlockKind kind, int index, int count) {
		ItemStack stack = new ItemStack(ModBlocks.item(kind), count);
		stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(DesignProperties.forKind(kind), index));
		return stack;
	}

	/** Design number stored in an item stack, or -1. */
	public static int designOf(ItemStack stack, BlockKind kind) {
		BlockItemStateProperties props = stack.get(DataComponents.BLOCK_STATE);
		if (props == null) {
			return -1;
		}
		try {
			Integer value = props.get(DesignProperties.forKind(kind));
			return value == null ? -1 : value;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	/** Does this design have transparent pixels? */
	public static boolean seeThrough(BlockKind kind, int index) {
		BlockInfo info = get(kind, index);
		return info != null && info.seeThrough();
	}

	/**
	 * Sets {@link DesignProperties#SEE_THROUGH} for a block that is being placed. The design comes from the item,
	 * because vanilla copies the item's block state component onto the block only after placement.
	 */
	public static BlockState withSeeThrough(BlockState state, BlockPlaceContext context, BlockKind kind) {
		if (state == null || !state.hasProperty(DesignProperties.SEE_THROUGH)) {
			return state;
		}
		int design = designOf(context.getItemInHand(), kind);
		if (design < 0) {
			design = state.getValue(DesignProperties.forKind(kind));
		}
		return state.setValue(DesignProperties.SEE_THROUGH, seeThrough(kind, design));
	}

	/** Corrects {@link DesignProperties#SEE_THROUGH} of a placed block (the picture may have changed since). */
	public static BlockState fixSeeThrough(BlockState state, BlockKind kind) {
		if (state == null || !state.hasProperty(DesignProperties.SEE_THROUGH)) {
			return state;
		}
		boolean wanted = seeThrough(kind, state.getValue(DesignProperties.forKind(kind)));
		return state.getValue(DesignProperties.SEE_THROUGH) == wanted ? state : state.setValue(DesignProperties.SEE_THROUGH, wanted);
	}

	/** Item for middle click on a placed block. */
	public static ItemStack cloneStack(DesignBlock block, BlockState state) {
		return stack(block.kind(), block.design(state), 1);
	}
}
