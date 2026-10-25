package dev.norrmi.ingameblocks.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import dev.norrmi.ingameblocks.InGameBlocks;
import dev.norrmi.ingameblocks.block.BlockKind;
import dev.norrmi.ingameblocks.data.BlockInfo;
import dev.norrmi.ingameblocks.data.TextureRules;

/** All network packets of the mod. */
public final class Payloads {
	/** Max number of registry entries in one packet (sum of all kind capacities). */
	private static final int MAX_ENTRIES;

	static {
		int total = 0;
		for (BlockKind kind : BlockKind.values()) {
			total += kind.capacity();
		}
		MAX_ENTRIES = total;
	}

	private Payloads() {
	}

	public static void register() {
		// A block with all its textures may be bigger than the vanilla 32 KiB limit of client->server packets,
		// so it is registered as a "large" payload (Fabric API splits it into several packets).
		PayloadTypeRegistry.serverboundPlay().registerLarge(RegisterBlock.TYPE, RegisterBlock.CODEC,
				() -> InGameBlocks.MAX_TEXTURES_PER_BLOCK * (InGameBlocks.MAX_TEXTURE_BYTES + 16) + 8192);
		PayloadTypeRegistry.serverboundPlay().register(RequestTextures.TYPE, RequestTextures.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(GiveBlock.TYPE, GiveBlock.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(RegistryUpdate.TYPE, RegistryUpdate.CODEC, 4 * 1024 * 1024);
		PayloadTypeRegistry.clientboundPlay().register(TextureData.TYPE, TextureData.CODEC);
	}

	private static byte[] readBytes(FriendlyByteBuf buf) {
		int length = buf.readVarInt();
		if (length < 0 || length > InGameBlocks.MAX_TEXTURE_BYTES) {
			throw new IllegalArgumentException("Texture is too large: " + length);
		}
		byte[] data = new byte[length];
		buf.readBytes(data);
		return data;
	}

	private static void writeBytes(FriendlyByteBuf buf, byte[] data) {
		buf.writeVarInt(data.length);
		buf.writeBytes(data);
	}

	/**
	 * Client -> server: "create or update this block of mine" (and optionally give me {@code give} items).
	 * The PNG files (the main texture and the different face textures) are only sent when {@code shared} is true;
	 * private blocks are announced without any texture.
	 *
	 * @param kind  shape id (see {@link BlockKind#id()})
	 * @param sound sound key, empty = default
	 * @param hash  SHA-256 of the main texture
	 * @param faces 6 hashes (down, up, north, south, west, east); an empty string means "same as the main texture"
	 */
	public record RegisterBlock(String localId, String name, String kind, String sound, String hash, List<String> faces,
								boolean shared, boolean seeThrough, List<byte[]> textures, int give) implements CustomPacketPayload {
		public static final Type<RegisterBlock> TYPE = new Type<>(InGameBlocks.id("register_block"));
		public static final StreamCodec<FriendlyByteBuf, RegisterBlock> CODEC = StreamCodec.ofMember(RegisterBlock::write, RegisterBlock::read);

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(this.localId);
			buf.writeUtf(this.name);
			buf.writeUtf(this.kind);
			buf.writeUtf(this.sound);
			buf.writeUtf(this.hash);
			for (int i = 0; i < TextureRules.FACE_COUNT; i++) {
				buf.writeUtf(this.faces.get(i));
			}
			buf.writeBoolean(this.shared);
			buf.writeBoolean(this.seeThrough);
			buf.writeVarInt(this.textures.size());
			for (byte[] png : this.textures) {
				writeBytes(buf, png);
			}
			buf.writeVarInt(this.give);
		}

		private static RegisterBlock read(FriendlyByteBuf buf) {
			String localId = buf.readUtf();
			String name = buf.readUtf();
			String kind = buf.readUtf();
			String sound = buf.readUtf();
			String hash = buf.readUtf();
			List<String> faces = new ArrayList<>(TextureRules.FACE_COUNT);
			for (int i = 0; i < TextureRules.FACE_COUNT; i++) {
				faces.add(buf.readUtf());
			}
			boolean shared = buf.readBoolean();
			boolean seeThrough = buf.readBoolean();
			int count = buf.readVarInt();
			if (count < 0 || count > InGameBlocks.MAX_TEXTURES_PER_BLOCK) {
				throw new IllegalArgumentException("Too many textures: " + count);
			}
			List<byte[]> textures = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				textures.add(readBytes(buf));
			}
			int give = buf.readVarInt();
			return new RegisterBlock(localId, name, kind, sound, hash, List.copyOf(faces), shared, seeThrough, List.copyOf(textures), give);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: "please send me the textures with these hashes". */
	public record RequestTextures(List<String> hashes) implements CustomPacketPayload {
		public static final Type<RequestTextures> TYPE = new Type<>(InGameBlocks.id("request_textures"));
		public static final StreamCodec<FriendlyByteBuf, RequestTextures> CODEC = StreamCodec.ofMember(RequestTextures::write, RequestTextures::read);

		private void write(FriendlyByteBuf buf) {
			buf.writeVarInt(this.hashes.size());
			for (String hash : this.hashes) {
				buf.writeUtf(hash);
			}
		}

		private static RequestTextures read(FriendlyByteBuf buf) {
			int count = buf.readVarInt();
			if (count < 0 || count > InGameBlocks.MAX_REQUEST_HASHES) {
				throw new IllegalArgumentException("Too many hashes: " + count);
			}
			List<String> hashes = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				hashes.add(buf.readUtf());
			}
			return new RequestTextures(hashes);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: "put {@code count} items of this design into my inventory". */
	public record GiveBlock(String kind, int index, int count) implements CustomPacketPayload {
		public static final Type<GiveBlock> TYPE = new Type<>(InGameBlocks.id("give_block"));
		public static final StreamCodec<FriendlyByteBuf, GiveBlock> CODEC = StreamCodec.ofMember(GiveBlock::write, GiveBlock::read);

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(this.kind);
			buf.writeVarInt(this.index);
			buf.writeVarInt(this.count);
		}

		private static GiveBlock read(FriendlyByteBuf buf) {
			return new GiveBlock(buf.readUtf(), buf.readVarInt(), buf.readVarInt());
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: the block registry. {@code full} replaces everything, otherwise entries are added/updated. */
	public record RegistryUpdate(boolean full, List<BlockInfo> entries) implements CustomPacketPayload {
		public static final Type<RegistryUpdate> TYPE = new Type<>(InGameBlocks.id("registry_update"));
		public static final StreamCodec<FriendlyByteBuf, RegistryUpdate> CODEC = StreamCodec.ofMember(RegistryUpdate::write, RegistryUpdate::read);

		private void write(FriendlyByteBuf buf) {
			buf.writeBoolean(this.full);
			buf.writeVarInt(this.entries.size());
			for (BlockInfo entry : this.entries) {
				entry.write(buf);
			}
		}

		private static RegistryUpdate read(FriendlyByteBuf buf) {
			boolean full = buf.readBoolean();
			int count = buf.readVarInt();
			if (count < 0 || count > MAX_ENTRIES) {
				throw new IllegalArgumentException("Too many entries: " + count);
			}
			List<BlockInfo> entries = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				entries.add(BlockInfo.read(buf));
			}
			return new RegistryUpdate(full, entries);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: the PNG of a shared texture. */
	public record TextureData(String hash, byte[] png) implements CustomPacketPayload {
		public static final Type<TextureData> TYPE = new Type<>(InGameBlocks.id("texture_data"));
		public static final StreamCodec<FriendlyByteBuf, TextureData> CODEC = StreamCodec.ofMember(TextureData::write, TextureData::read);

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(this.hash);
			writeBytes(buf, this.png);
		}

		private static TextureData read(FriendlyByteBuf buf) {
			return new TextureData(buf.readUtf(), readBytes(buf));
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
