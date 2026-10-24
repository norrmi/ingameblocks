package dev.norrmi.ingameblocks.client.pack;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.InclusiveRange;

import dev.norrmi.ingameblocks.InGameBlocks;

/** A resource pack that only exists in memory. Its files are produced by {@link DynamicPackBuilder}. */
public final class DynamicPack implements PackResources {
	private static final String ASSETS_PREFIX = "assets/";

	private final PackLocationInfo location;
	private final Map<String, byte[]> files;
	private final PackMetadataSection metadata;

	public DynamicPack(PackLocationInfo location, Map<String, byte[]> files) {
		this.location = location;
		this.files = files;
		// "This pack is made for the current game version."
		PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
		this.metadata = new PackMetadataSection(Component.literal("InGameBlocks: generated block textures"), new InclusiveRange<>(format));
	}

	@Override
	public IoSupplier<InputStream> getRootResource(String... pathSegments) {
		// There is no pack.mcmeta / pack.png: the metadata is handed out by getMetadataSection().
		return null;
	}

	@Override
	public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
		if (type != PackType.CLIENT_RESOURCES || !InGameBlocks.MOD_ID.equals(id.getNamespace())) {
			return null;
		}
		byte[] data = this.files.get(ASSETS_PREFIX + id.getNamespace() + "/" + id.getPath());
		if (data == null) {
			return null;
		}
		return () -> new ByteArrayInputStream(data);
	}

	@Override
	public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
		if (type != PackType.CLIENT_RESOURCES || !InGameBlocks.MOD_ID.equals(namespace)) {
			return;
		}
		String namespacePrefix = ASSETS_PREFIX + namespace + "/";
		String searchPrefix = path.isEmpty() ? namespacePrefix : namespacePrefix + path + "/";
		for (Map.Entry<String, byte[]> entry : this.files.entrySet()) {
			String key = entry.getKey();
			if (!key.startsWith(searchPrefix)) {
				continue;
			}
			Identifier id = Identifier.tryBuild(namespace, key.substring(namespacePrefix.length()));
			if (id != null) {
				byte[] data = entry.getValue();
				output.accept(id, () -> new ByteArrayInputStream(data));
			}
		}
	}

	@Override
	public Set<String> getNamespaces(PackType type) {
		return type == PackType.CLIENT_RESOURCES ? Set.of(InGameBlocks.MOD_ID) : Set.of();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getMetadataSection(MetadataSectionType<T> type) {
		return PackMetadataSection.CLIENT_TYPE.equals(type) ? (T) this.metadata : null;
	}

	@Override
	public PackLocationInfo location() {
		return this.location;
	}

	// Fabric's own PackResources implementation overrides this one as well.
	public String packId() {
		return this.location.id();
	}

	@Override
	public void close() {
	}
}
