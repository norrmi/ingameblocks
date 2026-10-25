package dev.norrmi.ingameblocks.block;

import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * The {@code design} block state properties. They must be static constants because vanilla calls
 * {@code createBlockStateDefinition} from inside the block constructor.
 */
public final class DesignProperties {
	public static final String NAME = "design";
	public static final IntegerProperty D1024 = IntegerProperty.create(NAME, 0, 1023);
	public static final IntegerProperty D256 = IntegerProperty.create(NAME, 0, 255);
	public static final IntegerProperty D128 = IntegerProperty.create(NAME, 0, 127);
	/**
	 * True for designs whose picture has transparent pixels. Such blocks must not hide the faces of their neighbours,
	 * otherwise you look through the hole into the void.
	 */
	public static final BooleanProperty SEE_THROUGH = BooleanProperty.create("see_through");

	private DesignProperties() {
	}

	public static IntegerProperty forKind(BlockKind kind) {
		return switch (kind.capacity()) {
			case 1024 -> D1024;
			case 256 -> D256;
			case 128 -> D128;
			default -> throw new IllegalStateException("No design property for capacity " + kind.capacity());
		};
	}
}
