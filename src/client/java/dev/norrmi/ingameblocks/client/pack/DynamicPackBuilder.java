package dev.norrmi.ingameblocks.client.pack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.block.BlockKind;
import dev.norrmi.ingameblocks.block.Designs;
import dev.norrmi.ingameblocks.client.ClientState;
import dev.norrmi.ingameblocks.data.BlockInfo;
import dev.norrmi.ingameblocks.data.TextureRules;

/**
 * Generates, in memory, the assets of every design of every kind: blockstates, block and item models, item
 * definitions, textures and names. Designs that are unused (or whose textures the player may not see) use the bundled
 * placeholder texture.
 */
public final class DynamicPackBuilder {
	private static final Gson GSON = new Gson();
	private static final String NS = InGameBlocks.MOD_ID;
	private static final String ASSETS = "assets/" + NS + "/";
	private static final String PLACEHOLDER = NS + ":block/placeholder";
	private static final String[] HORIZONTAL = {"north", "east", "south", "west"};

	private DynamicPackBuilder() {
	}

	/** Texture references of one design: index 0..5 = faces (down, up, north, south, west, east). */
	private record Textures(String main, String[] faces) {
		String face(int i) {
			return this.faces[i];
		}

		String down() {
			return this.faces[0];
		}

		String up() {
			return this.faces[1];
		}

		String north() {
			return this.faces[2];
		}
	}

	private static final class Output {
		final Map<String, byte[]> files = new HashMap<>();
		final Set<String> textures = new HashSet<>();
		final JsonObject lang = new JsonObject();

		void json(String path, JsonObject json) {
			this.files.put(ASSETS + path, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
		}

		/** Adds a texture file once and returns the reference to it. */
		String texture(String hash, byte[] png) {
			String name = "t_" + hash;
			if (this.textures.add(name)) {
				this.files.put(ASSETS + "textures/block/" + name + ".png", png);
			}
			return NS + ":block/" + name;
		}
	}

	/** @return path inside the pack ("assets/ingameblocks/...") -> file contents */
	public static Map<String, byte[]> build() {
		Output out = new Output();
		for (BlockKind kind : BlockKind.values()) {
			buildKind(out, kind);
		}
		out.files.put(ASSETS + "lang/en_us.json", GSON.toJson(out.lang).getBytes(StandardCharsets.UTF_8));
		return out.files;
	}

	private static void buildKind(Output out, BlockKind kind) {
		// "none" models for unused designs
		String[] placeholderFaces = new String[TextureRules.FACE_COUNT];
		java.util.Arrays.fill(placeholderFaces, PLACEHOLDER);
		Textures none = new Textures(PLACEHOLDER, placeholderFaces);
		String noneBase = NS + ":block/" + kind.id() + "_none";
		writeModels(out, kind, "block/" + kind.id() + "_none", none);

		String[] modelOf = new String[kind.capacity()];
		List<Integer> used = new ArrayList<>();
		for (int index = 0; index < kind.capacity(); index++) {
			BlockInfo info = ClientState.get(kind, index);
			if (info == null) {
				modelOf[index] = noneBase;
				continue;
			}
			used.add(index);
			out.lang.addProperty(Designs.translationKey(kind, index), info.name().replace("%", "%%"));

			String main = PLACEHOLDER;
			byte[] mainPng = ClientState.displayTexture(info);
			if (mainPng != null) {
				main = out.texture(info.hash(), mainPng);
			}
			String[] faces = new String[TextureRules.FACE_COUNT];
			for (int face = 0; face < TextureRules.FACE_COUNT; face++) {
				byte[] png = ClientState.displayTexture(info, face);
				faces[face] = png != null ? out.texture(info.faceHash(face), png) : PLACEHOLDER;
			}
			String path = "block/" + kind.id() + "_" + index;
			writeModels(out, kind, path, new Textures(main, faces));
			modelOf[index] = NS + ":" + path;
			if (kind == BlockKind.PLANT) {
				JsonObject item = new JsonObject();
				item.addProperty("parent", "minecraft:item/generated");
				JsonObject textures = new JsonObject();
				textures.addProperty("layer0", main);
				item.add("textures", textures);
				out.json("models/item/" + kind.id() + "_" + index + ".json", item);
			}
		}

		out.json("blockstates/" + kind.id() + ".json", blockstate(kind, modelOf, noneBase, used));
		out.json("items/" + kind.id() + ".json", itemDefinition(kind, used, noneBase));
	}

	// ------------------------------------------------------------------ models

	private static void writeModels(Output out, BlockKind kind, String path, Textures t) {
		switch (kind) {
			case CUBE, GLASS, PILLAR, PISTON -> out.json("models/" + path + ".json", cube(t));
			case SLAB -> {
				out.json("models/" + path + ".json", slab(t, false));
				out.json("models/" + path + "_top.json", slab(t, true));
				out.json("models/" + path + "_double.json", cube(t));
			}
			case STAIRS -> {
				out.json("models/" + path + ".json", parented("minecraft:block/stairs", t, "bottom", t.down(), "top", t.up(), "side", t.north()));
				out.json("models/" + path + "_inner.json", parented("minecraft:block/inner_stairs", t, "bottom", t.down(), "top", t.up(), "side", t.north()));
				out.json("models/" + path + "_outer.json", parented("minecraft:block/outer_stairs", t, "bottom", t.down(), "top", t.up(), "side", t.north()));
			}
			case FENCE -> {
				out.json("models/" + path + "_post.json", parented("minecraft:block/fence_post", t, "texture", t.main()));
				out.json("models/" + path + "_side.json", parented("minecraft:block/fence_side", t, "texture", t.main()));
				out.json("models/" + path + "_inventory.json", parented("minecraft:block/fence_inventory", t, "texture", t.main()));
			}
			case CARPET -> out.json("models/" + path + ".json", parented("minecraft:block/carpet", t, "wool", t.up()));
			case PLANT -> out.json("models/" + path + ".json", parented("minecraft:block/cross", t, "cross", t.main()));
			case HEAD -> {
				// Worn on the head, the block sits half a block too low unless it is lifted (values are in 1/16 block).
				JsonObject model = box(t, new int[] {4, 0, 4}, new int[] {12, 8, 12}, null);
				JsonObject head = new JsonObject();
				head.add("translation", vec(0, 8, 0));
				JsonObject display = new JsonObject();
				display.add("head", head);
				model.add("display", display);
				out.json("models/" + path + ".json", model);
			}
			case CHEST -> out.json("models/" + path + ".json", box(t, new int[] {1, 0, 1}, new int[] {15, 14, 15}, latch(t)));
			case ANVIL -> out.json("models/" + path + ".json", parented("minecraft:block/template_anvil", t, "body", t.main(), "top", t.up()));
			default -> throw new IllegalStateException(kind.toString());
		}
	}

	private static JsonObject textures(Textures t) {
		JsonObject textures = new JsonObject();
		textures.addProperty("particle", t.main());
		for (int i = 0; i < TextureRules.FACE_COUNT; i++) {
			textures.addProperty(TextureRules.FACE_NAMES[i], t.face(i));
		}
		return textures;
	}

	private static JsonObject cube(Textures t) {
		JsonObject model = new JsonObject();
		model.addProperty("parent", "minecraft:block/cube");
		model.add("textures", textures(t));
		return model;
	}

	private static JsonObject parented(String parent, Textures t, String... keyValues) {
		JsonObject model = new JsonObject();
		model.addProperty("parent", parent);
		JsonObject textures = new JsonObject();
		textures.addProperty("particle", t.main());
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			textures.addProperty(keyValues[i], keyValues[i + 1]);
		}
		model.add("textures", textures);
		return model;
	}

	private static JsonObject face(String texture, int[] uv, String cull) {
		JsonObject face = new JsonObject();
		JsonArray uvArray = new JsonArray();
		for (int v : uv) {
			uvArray.add(v);
		}
		face.add("uv", uvArray);
		face.addProperty("texture", texture);
		if (cull != null) {
			face.addProperty("cullface", cull);
		}
		return face;
	}

	private static JsonArray vec(int... values) {
		JsonArray array = new JsonArray();
		for (int v : values) {
			array.add(v);
		}
		return array;
	}

	/** A half block (bottom or top) with a texture per face. */
	private static JsonObject slab(Textures t, boolean top) {
		int y0 = top ? 8 : 0;
		int y1 = top ? 16 : 8;
		int[] sideUv = top ? new int[] {0, 0, 16, 8} : new int[] {0, 8, 16, 16};
		JsonObject element = new JsonObject();
		element.add("from", vec(0, y0, 0));
		element.add("to", vec(16, y1, 16));
		JsonObject faces = new JsonObject();
		faces.add("down", face("#down", new int[] {0, 0, 16, 16}, top ? null : "down"));
		faces.add("up", face("#up", new int[] {0, 0, 16, 16}, top ? "up" : null));
		faces.add("north", face("#north", sideUv, "north"));
		faces.add("south", face("#south", sideUv, "south"));
		faces.add("west", face("#west", sideUv, "west"));
		faces.add("east", face("#east", sideUv, "east"));
		element.add("faces", faces);
		JsonObject model = new JsonObject();
		model.addProperty("parent", "minecraft:block/block");
		model.add("textures", textures(t));
		JsonArray elements = new JsonArray();
		elements.add(element);
		model.add("elements", elements);
		return model;
	}

	/** A box (head, chest body) showing each face texture completely, plus optional extra elements. */
	private static JsonObject box(Textures t, int[] from, int[] to, JsonArray extra) {
		JsonObject element = new JsonObject();
		element.add("from", vec(from));
		element.add("to", vec(to));
		JsonObject faces = new JsonObject();
		for (int i = 0; i < TextureRules.FACE_COUNT; i++) {
			String name = TextureRules.FACE_NAMES[i];
			boolean touchesEdge = switch (name) {
				case "down" -> from[1] == 0;
				case "up" -> to[1] == 16;
				case "north" -> from[2] == 0;
				case "south" -> to[2] == 16;
				case "west" -> from[0] == 0;
				default -> to[0] == 16;
			};
			faces.add(name, face("#" + name, new int[] {0, 0, 16, 16}, touchesEdge ? name : null));
		}
		element.add("faces", faces);
		JsonArray elements = new JsonArray();
		elements.add(element);
		if (extra != null) {
			elements.addAll(extra);
		}
		JsonObject model = new JsonObject();
		model.addProperty("parent", "minecraft:block/block");
		model.add("textures", textures(t));
		model.add("elements", elements);
		return model;
	}

	/** The small lock on the front of the chest. */
	private static JsonArray latch(Textures t) {
		JsonObject element = new JsonObject();
		element.add("from", vec(7, 7, 0));
		element.add("to", vec(9, 11, 1));
		JsonObject faces = new JsonObject();
		faces.add("down", face("#north", new int[] {7, 7, 9, 8}, null));
		faces.add("up", face("#north", new int[] {7, 3, 9, 4}, null));
		faces.add("north", face("#north", new int[] {7, 5, 9, 9}, null));
		faces.add("west", face("#north", new int[] {6, 5, 7, 9}, null));
		faces.add("east", face("#north", new int[] {9, 5, 10, 9}, null));
		element.add("faces", faces);
		JsonArray array = new JsonArray();
		array.add(element);
		return array;
	}

	// ------------------------------------------------------------------ blockstates

	private static JsonObject variant(String model, int x, int y, boolean uvlock) {
		JsonObject v = new JsonObject();
		v.addProperty("model", model);
		if (x != 0) {
			v.addProperty("x", x);
		}
		if (y != 0) {
			v.addProperty("y", y);
		}
		if (uvlock) {
			v.addProperty("uvlock", true);
		}
		return v;
	}

	private static JsonObject blockstate(BlockKind kind, String[] modelOf, String noneBase, List<Integer> used) {
		if (kind == BlockKind.FENCE) {
			return fenceBlockstate(modelOf, noneBase, used);
		}
		JsonObject variants = new JsonObject();
		for (int index = 0; index < modelOf.length; index++) {
			String m = modelOf[index];
			String d = "design=" + index;
			switch (kind) {
				case CUBE, GLASS, CARPET, PLANT -> variants.add(d, variant(m, 0, 0, false));
				case PILLAR -> {
					variants.add("axis=x," + d, variant(m, 90, 90, false));
					variants.add("axis=y," + d, variant(m, 0, 0, false));
					variants.add("axis=z," + d, variant(m, 90, 0, false));
				}
				case SLAB -> {
					variants.add(d + ",type=bottom", variant(m, 0, 0, false));
					variants.add(d + ",type=top", variant(m + "_top", 0, 0, false));
					variants.add(d + ",type=double", variant(m + "_double", 0, 0, false));
				}
				case STAIRS -> addStairs(variants, d, m);
				case HEAD, CHEST -> {
					for (int i = 0; i < HORIZONTAL.length; i++) {
						variants.add(d + ",facing=" + HORIZONTAL[i], variant(m, 0, i * 90, false));
					}
				}
				case ANVIL -> {
					// Same rotations as vanilla's anvil blockstate.
					variants.add(d + ",facing=east", variant(m, 0, 270, false));
					variants.add(d + ",facing=north", variant(m, 0, 180, false));
					variants.add(d + ",facing=south", variant(m, 0, 0, false));
					variants.add(d + ",facing=west", variant(m, 0, 90, false));
				}
				case PISTON -> {
					variants.add(d + ",facing=north", variant(m, 0, 0, false));
					variants.add(d + ",facing=east", variant(m, 0, 90, false));
					variants.add(d + ",facing=south", variant(m, 0, 180, false));
					variants.add(d + ",facing=west", variant(m, 0, 270, false));
					variants.add(d + ",facing=up", variant(m, 270, 0, false));
					variants.add(d + ",facing=down", variant(m, 90, 0, false));
				}
				default -> throw new IllegalStateException(kind.toString());
			}
		}
		JsonObject root = new JsonObject();
		root.add("variants", variants);
		return root;
	}

	/** Vanilla's stairs blockstate (oak_stairs.json), for one design. */
	private static void addStairs(JsonObject variants, String d, String m) {
		String straight = m;
		String inner = m + "_inner";
		String outer = m + "_outer";
		Object[][] table = {
				{"east", "bottom", "inner_left", inner, 0, 270, true}, {"east", "bottom", "inner_right", inner, 0, 0, false},
				{"east", "bottom", "outer_left", outer, 0, 270, true}, {"east", "bottom", "outer_right", outer, 0, 0, false},
				{"east", "bottom", "straight", straight, 0, 0, false},
				{"east", "top", "inner_left", inner, 180, 0, true}, {"east", "top", "inner_right", inner, 180, 90, true},
				{"east", "top", "outer_left", outer, 180, 0, true}, {"east", "top", "outer_right", outer, 180, 90, true},
				{"east", "top", "straight", straight, 180, 0, true},
				{"north", "bottom", "inner_left", inner, 0, 180, true}, {"north", "bottom", "inner_right", inner, 0, 270, true},
				{"north", "bottom", "outer_left", outer, 0, 180, true}, {"north", "bottom", "outer_right", outer, 0, 270, true},
				{"north", "bottom", "straight", straight, 0, 270, true},
				{"north", "top", "inner_left", inner, 180, 270, true}, {"north", "top", "inner_right", inner, 180, 0, true},
				{"north", "top", "outer_left", outer, 180, 270, true}, {"north", "top", "outer_right", outer, 180, 0, true},
				{"north", "top", "straight", straight, 180, 270, true},
				{"south", "bottom", "inner_left", inner, 0, 0, false}, {"south", "bottom", "inner_right", inner, 0, 90, true},
				{"south", "bottom", "outer_left", outer, 0, 0, false}, {"south", "bottom", "outer_right", outer, 0, 90, true},
				{"south", "bottom", "straight", straight, 0, 90, true},
				{"south", "top", "inner_left", inner, 180, 90, true}, {"south", "top", "inner_right", inner, 180, 180, true},
				{"south", "top", "outer_left", outer, 180, 90, true}, {"south", "top", "outer_right", outer, 180, 180, true},
				{"south", "top", "straight", straight, 180, 90, true},
				{"west", "bottom", "inner_left", inner, 0, 90, true}, {"west", "bottom", "inner_right", inner, 0, 180, true},
				{"west", "bottom", "outer_left", outer, 0, 90, true}, {"west", "bottom", "outer_right", outer, 0, 180, true},
				{"west", "bottom", "straight", straight, 0, 180, true},
				{"west", "top", "inner_left", inner, 180, 180, true}, {"west", "top", "inner_right", inner, 180, 270, true},
				{"west", "top", "outer_left", outer, 180, 180, true}, {"west", "top", "outer_right", outer, 180, 270, true},
				{"west", "top", "straight", straight, 180, 180, true},
		};
		for (Object[] row : table) {
			String key = d + ",facing=" + row[0] + ",half=" + row[1] + ",shape=" + row[2];
			variants.add(key, variant((String) row[3], (Integer) row[4], (Integer) row[5], (Boolean) row[6]));
		}
	}

	private static JsonObject fenceBlockstate(String[] modelOf, String noneBase, List<Integer> used) {
		JsonArray parts = new JsonArray();
		for (int index : used) {
			addFenceParts(parts, Integer.toString(index), modelOf[index]);
		}
		Set<Integer> usedSet = new HashSet<>(used);
		List<String> unused = new ArrayList<>();
		for (int index = 0; index < modelOf.length; index++) {
			if (!usedSet.contains(index)) {
				unused.add(Integer.toString(index));
			}
		}
		if (!unused.isEmpty()) {
			addFenceParts(parts, String.join("|", unused), noneBase);
		}
		JsonObject root = new JsonObject();
		root.add("multipart", parts);
		return root;
	}

	private static void addFenceParts(JsonArray parts, String designs, String base) {
		JsonObject post = new JsonObject();
		JsonObject postWhen = new JsonObject();
		postWhen.addProperty("design", designs);
		post.add("when", postWhen);
		post.add("apply", variant(base + "_post", 0, 0, false));
		parts.add(post);
		for (int i = 0; i < HORIZONTAL.length; i++) {
			JsonObject side = new JsonObject();
			JsonObject when = new JsonObject();
			when.addProperty("design", designs);
			when.addProperty(HORIZONTAL[i], "true");
			side.add("when", when);
			side.add("apply", variant(base + "_side", 0, i * 90, true));
			parts.add(side);
		}
	}

	// ------------------------------------------------------------------ items

	private static String itemModel(BlockKind kind, String base) {
		return switch (kind) {
			case FENCE -> base + "_inventory";
			default -> base;
		};
	}

	private static JsonObject plainModel(String model) {
		JsonObject m = new JsonObject();
		m.addProperty("type", "minecraft:model");
		m.addProperty("model", model);
		return m;
	}

	private static JsonObject itemDefinition(BlockKind kind, List<Integer> used, String noneBase) {
		if (used.isEmpty()) {
			JsonObject root = new JsonObject();
			root.add("model", plainModel(itemModel(kind, noneBase)));
			return root;
		}
		JsonObject select = new JsonObject();
		select.addProperty("type", "minecraft:select");
		select.addProperty("property", "minecraft:block_state");
		select.addProperty("block_state_property", "design");
		JsonArray cases = new JsonArray();
		for (int index : used) {
			JsonObject entry = new JsonObject();
			entry.addProperty("when", Integer.toString(index));
			String model = kind == BlockKind.PLANT
					? NS + ":item/" + kind.id() + "_" + index
					: itemModel(kind, NS + ":block/" + kind.id() + "_" + index);
			entry.add("model", plainModel(model));
			cases.add(entry);
		}
		select.add("cases", cases);
		select.add("fallback", plainModel(itemModel(kind, noneBase)));
		JsonObject root = new JsonObject();
		root.add("model", select);
		return root;
	}
}
