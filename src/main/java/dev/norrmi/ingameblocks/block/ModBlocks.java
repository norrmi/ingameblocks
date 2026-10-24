package dev.norrmi.ingameblocks.block;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import dev.norrmi.ingameblocks.InGameBlocks;

/** Registers one block + item per {@link BlockKind} (ingameblocks:cube, ingameblocks:slab, ...). */
public final class ModBlocks {
	private static final Map<BlockKind, Block> BLOCKS = new EnumMap<>(BlockKind.class);
	private static final Map<BlockKind, Item> ITEMS = new EnumMap<>(BlockKind.class);
	public static BlockEntityType<ChestDesignBlockEntity> CHEST_ENTITY;

	private ModBlocks() {
	}

	public static void init() {
		register(BlockKind.CUBE, CubeDesignBlock::new, BlockBehaviour.Properties.of()
				.mapColor(MapColor.STONE).strength(1.5F, 6.0F).sound(SoundType.STONE));
		register(BlockKind.PILLAR, PillarDesignBlock::new, BlockBehaviour.Properties.of()
				.mapColor(MapColor.WOOD).strength(2.0F).sound(SoundType.WOOD));
		register(BlockKind.GLASS, GlassDesignBlock::new, BlockBehaviour.Properties.of()
				.strength(0.3F).sound(SoundType.GLASS).noOcclusion()
				.isValidSpawn((state, level, pos, type) -> false)
				.isRedstoneConductor((state, level, pos) -> false)
				.isSuffocating((state, level, pos) -> false)
				.isViewBlocking((state, level, pos, box) -> false));
		register(BlockKind.SLAB, SlabDesignBlock::new, BlockBehaviour.Properties.of()
				.mapColor(MapColor.STONE).strength(1.5F, 6.0F).sound(SoundType.STONE));
		register(BlockKind.STAIRS, StairsDesignBlock::new, BlockBehaviour.Properties.of()
				.mapColor(MapColor.STONE).strength(1.5F, 6.0F).sound(SoundType.STONE));
		register(BlockKind.FENCE, FenceDesignBlock::new, BlockBehaviour.Properties.of()
				.mapColor(MapColor.WOOD).forceSolidOn().strength(2.0F, 3.0F).sound(SoundType.WOOD));
		register(BlockKind.CARPET, CarpetDesignBlock::new, BlockBehaviour.Properties.of()
				.mapColor(MapColor.WOOL).strength(0.1F).sound(SoundType.WOOL));
		register(BlockKind.PLANT, PlantDesignBlock::new, BlockBehaviour.Properties.of()
				.mapColor(MapColor.PLANT).noCollision().instabreak().sound(SoundType.GRASS)
				.offsetType(BlockBehaviour.OffsetType.XZ).pushReaction(PushReaction.POPPED));
		register(BlockKind.HEAD, HeadDesignBlock::new, BlockBehaviour.Properties.of()
				.strength(1.0F).sound(SoundType.STONE).pushReaction(PushReaction.POPPED));
		register(BlockKind.CHEST, ChestDesignBlock::new, BlockBehaviour.Properties.of()
				.mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD));
		register(BlockKind.ANVIL, AnvilDesignBlock::new, BlockBehaviour.Properties.of()
				.mapColor(MapColor.METAL).strength(5.0F, 1200.0F).sound(SoundType.ANVIL).pushReaction(PushReaction.IMMOVEABLE));
		register(BlockKind.PISTON, PistonDesignBlock::new, BlockBehaviour.Properties.of()
				.mapColor(MapColor.STONE).strength(1.5F).sound(SoundType.STONE));

		CHEST_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, InGameBlocks.id("chest"),
				FabricBlockEntityTypeBuilder.create(ChestDesignBlockEntity::new, block(BlockKind.CHEST)).build());
	}

	private static void register(BlockKind kind, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, InGameBlocks.id(kind.id()));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, InGameBlocks.id(kind.id()));

		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(properties.setId(blockKey)));

		Item.Properties itemProperties = new Item.Properties().useBlockDescriptionPrefix().setId(itemKey);
		if (kind == BlockKind.HEAD) {
			itemProperties = itemProperties.equippableUnswappable(EquipmentSlot.HEAD);
		}
		Item item = Registry.register(BuiltInRegistries.ITEM, itemKey, new DesignBlockItem(block, kind, itemProperties));

		BLOCKS.put(kind, block);
		ITEMS.put(kind, item);
	}

	public static Block block(BlockKind kind) {
		return BLOCKS.get(kind);
	}

	public static Item item(BlockKind kind) {
		return ITEMS.get(kind);
	}
}
