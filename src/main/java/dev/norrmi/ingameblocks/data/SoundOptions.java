package dev.norrmi.ingameblocks.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.block.SoundType;

/** The block sounds a player can pick for his block. The key is stored in files and packets. */
public final class SoundOptions {
	private static final Map<String, SoundType> SOUNDS = new LinkedHashMap<>();

	static {
		SOUNDS.put("stone", SoundType.STONE);
		SOUNDS.put("wood", SoundType.WOOD);
		SOUNDS.put("cherry_wood", SoundType.CHERRY_WOOD);
		SOUNDS.put("bamboo_wood", SoundType.BAMBOO_WOOD);
		SOUNDS.put("nether_wood", SoundType.NETHER_WOOD);
		SOUNDS.put("grass", SoundType.GRASS);
		SOUNDS.put("gravel", SoundType.GRAVEL);
		SOUNDS.put("sand", SoundType.SAND);
		SOUNDS.put("snow", SoundType.SNOW);
		SOUNDS.put("wool", SoundType.WOOL);
		SOUNDS.put("glass", SoundType.GLASS);
		SOUNDS.put("metal", SoundType.METAL);
		SOUNDS.put("iron", SoundType.IRON);
		SOUNDS.put("copper", SoundType.COPPER);
		SOUNDS.put("netherite", SoundType.NETHERITE_BLOCK);
		SOUNDS.put("anvil", SoundType.ANVIL);
		SOUNDS.put("chain", SoundType.CHAIN);
		SOUNDS.put("lantern", SoundType.LANTERN);
		SOUNDS.put("deepslate", SoundType.DEEPSLATE);
		SOUNDS.put("deepslate_bricks", SoundType.DEEPSLATE_BRICKS);
		SOUNDS.put("tuff", SoundType.TUFF);
		SOUNDS.put("calcite", SoundType.CALCITE);
		SOUNDS.put("dripstone", SoundType.DRIPSTONE_BLOCK);
		SOUNDS.put("basalt", SoundType.BASALT);
		SOUNDS.put("netherrack", SoundType.NETHERRACK);
		SOUNDS.put("nether_bricks", SoundType.NETHER_BRICKS);
		SOUNDS.put("soul_sand", SoundType.SOUL_SAND);
		SOUNDS.put("bone", SoundType.BONE_BLOCK);
		SOUNDS.put("amethyst", SoundType.AMETHYST);
		SOUNDS.put("mud", SoundType.MUD);
		SOUNDS.put("mud_bricks", SoundType.MUD_BRICKS);
		SOUNDS.put("moss", SoundType.MOSS);
		SOUNDS.put("leaves", SoundType.AZALEA_LEAVES);
		SOUNDS.put("crop", SoundType.CROP);
		SOUNDS.put("vine", SoundType.VINE);
		SOUNDS.put("slime", SoundType.SLIME_BLOCK);
		SOUNDS.put("honey", SoundType.HONEY_BLOCK);
		SOUNDS.put("coral", SoundType.CORAL_BLOCK);
		SOUNDS.put("sculk", SoundType.SCULK);
		SOUNDS.put("froglight", SoundType.FROGLIGHT);
		SOUNDS.put("resin", SoundType.RESIN);
		SOUNDS.put("decorated_pot", SoundType.DECORATED_POT);
		SOUNDS.put("ladder", SoundType.LADDER);
		SOUNDS.put("scaffolding", SoundType.SCAFFOLDING);
		SOUNDS.put("powder_snow", SoundType.POWDER_SNOW);
		SOUNDS.put("sponge", SoundType.SPONGE);
		SOUNDS.put("shelf", SoundType.SHELF);
		SOUNDS.put("heavy_core", SoundType.HEAVY_CORE);
		SOUNDS.put("sulfur", SoundType.SULFUR);
		SOUNDS.put("cinnabar", SoundType.CINNABAR);
	}

	private SoundOptions() {
	}

	/** All keys in display order. */
	public static List<String> keys() {
		return List.copyOf(SOUNDS.keySet());
	}

	public static boolean isValid(String key) {
		return key != null && (key.isEmpty() || SOUNDS.containsKey(key));
	}

	/** The sound for a key, or null for an unknown / empty key. */
	public static SoundType get(String key) {
		return key == null ? null : SOUNDS.get(key);
	}

	public static String translationKey(String key) {
		return key == null || key.isEmpty() ? "ingameblocks.sound.default" : "ingameblocks.sound." + key;
	}
}
