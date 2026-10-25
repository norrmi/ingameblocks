package dev.norrmi.ingameblocks.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.network.FriendlyByteBuf;

import dev.norrmi.ingameblocks.block.BlockKind;

/**
 * One entry of the (per world / per server) block registry: "design N of kind K is the block called X made by
 * player Y, these are the SHA-256 hashes of its textures, and this is its sound".
 *
 * @param kind      shape of the block
 * @param index     design number inside the kind (the value of the {@code design} block state property)
 * @param ownerUuid UUID of the creator (string form)
 * @param ownerName last known name of the creator
 * @param localId   id of the block inside the creator's own library (chosen by the creator)
 * @param name      display name
 * @param hash      SHA-256 (hex) of the main texture
 * @param faces     6 entries (down, up, north, south, west, east): SHA-256 of a different texture for that face,
 *                  or an empty string if the face uses the main texture
 * @param sound     sound name (see {@link SoundOptions}); empty = the kind's default sound
 * @param shared    true if the creator allows other players to receive the textures
 * @param available true if the server currently holds all texture files and can hand them out
 * @param seeThrough true if a texture of the block has transparent pixels (the block must not hide its neighbours)
 */
public record BlockInfo(BlockKind kind, int index, String ownerUuid, String ownerName, String localId, String name,
						String hash, List<String> faces, String sound, boolean shared, boolean available, boolean seeThrough) {
	public static final List<String> NO_FACES = List.of("", "", "", "", "", "");

	/** Identity of a block: creator + id in the creator's library. */
	public String key() {
		return keyOf(this.ownerUuid, this.localId);
	}

	public static String keyOf(String ownerUuid, String localId) {
		return ownerUuid + ":" + localId;
	}

	/** Key of the place this block occupies ("kind/index"). */
	public String slotKey() {
		return this.kind.id() + "/" + this.index;
	}

	public BlockInfo withAvailable(boolean value) {
		return new BlockInfo(this.kind, this.index, this.ownerUuid, this.ownerName, this.localId, this.name, this.hash,
				this.faces, this.sound, this.shared, value, this.seeThrough);
	}

	/** Hash of the texture that is shown on a face (index into {@link TextureRules#FACE_NAMES}). */
	public String faceHash(int face) {
		String own = this.faces.get(face);
		return own.isEmpty() ? this.hash : own;
	}

	/** Every texture the block needs: the main one and the different face textures. */
	public Set<String> allHashes() {
		Set<String> all = new LinkedHashSet<>();
		all.add(this.hash);
		for (String face : this.faces) {
			if (!face.isEmpty()) {
				all.add(face);
			}
		}
		return all;
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.kind.id());
		buf.writeVarInt(this.index);
		buf.writeUtf(this.ownerUuid);
		buf.writeUtf(this.ownerName);
		buf.writeUtf(this.localId);
		buf.writeUtf(this.name);
		buf.writeUtf(this.hash);
		for (int i = 0; i < TextureRules.FACE_COUNT; i++) {
			buf.writeUtf(this.faces.get(i));
		}
		buf.writeUtf(this.sound);
		buf.writeBoolean(this.shared);
		buf.writeBoolean(this.available);
		buf.writeBoolean(this.seeThrough);
	}

	public static BlockInfo read(FriendlyByteBuf buf) {
		String kindId = buf.readUtf();
		BlockKind kind = BlockKind.byId(kindId);
		if (kind == null) {
			throw new IllegalArgumentException("Unknown block kind " + kindId);
		}
		int index = buf.readVarInt();
		String ownerUuid = buf.readUtf();
		String ownerName = buf.readUtf();
		String localId = buf.readUtf();
		String name = buf.readUtf();
		String hash = buf.readUtf();
		List<String> faces = new ArrayList<>(TextureRules.FACE_COUNT);
		for (int i = 0; i < TextureRules.FACE_COUNT; i++) {
			faces.add(buf.readUtf());
		}
		String sound = buf.readUtf();
		boolean shared = buf.readBoolean();
		boolean available = buf.readBoolean();
		boolean seeThrough = buf.readBoolean();
		return new BlockInfo(kind, index, ownerUuid, ownerName, localId, name, hash, List.copyOf(faces), sound, shared, available, seeThrough);
	}
}
