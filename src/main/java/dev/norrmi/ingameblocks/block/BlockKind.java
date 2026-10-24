package dev.norrmi.ingameblocks.block;

import java.util.Locale;

/**
 * The shapes a player-made block can have. Every kind is one registered block with a {@code design} property;
 * {@link #capacity} is the number of different player blocks of that kind one world can hold.
 */
public enum BlockKind {
	CUBE("cube", 1024, "stone"),
	PILLAR("pillar", 256, "wood"),
	GLASS("glass", 256, "glass"),
	SLAB("slab", 256, "stone"),
	STAIRS("stairs", 128, "stone"),
	FENCE("fence", 128, "wood"),
	CARPET("carpet", 256, "wool"),
	PLANT("plant", 256, "grass"),
	HEAD("head", 256, "stone"),
	CHEST("chest", 128, "wood"),
	ANVIL("anvil", 128, "anvil"),
	PISTON("piston", 128, "stone");

	private final String id;
	private final int capacity;
	private final String defaultSound;

	BlockKind(String id, int capacity, String defaultSound) {
		this.id = id;
		this.capacity = capacity;
		this.defaultSound = defaultSound;
	}

	/** Registry path ({@code ingameblocks:<id>}) and the name used in files and packets. */
	public String id() {
		return this.id;
	}

	public int capacity() {
		return this.capacity;
	}

	/** Sound used when the creator picked "default". */
	public String defaultSound() {
		return this.defaultSound;
	}

	public String translationKey() {
		return "ingameblocks.kind." + this.id;
	}

	public static BlockKind byId(String id) {
		if (id == null) {
			return null;
		}
		String wanted = id.toLowerCase(Locale.ROOT);
		for (BlockKind kind : values()) {
			if (kind.id.equals(wanted)) {
				return kind;
			}
		}
		return null;
	}

	public static BlockKind byIdOr(String id, BlockKind fallback) {
		BlockKind kind = byId(id);
		return kind != null ? kind : fallback;
	}

	public BlockKind next() {
		BlockKind[] all = values();
		return all[(this.ordinal() + 1) % all.length];
	}

	public BlockKind previous() {
		BlockKind[] all = values();
		return all[(this.ordinal() + all.length - 1) % all.length];
	}
}
