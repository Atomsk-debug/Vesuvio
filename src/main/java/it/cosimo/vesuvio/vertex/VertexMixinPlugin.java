package it.cosimo.vesuvio.vertex;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import it.cosimo.vesuvio.Vesuvio;
import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.VesuvioMixinPlugin;

/**
 * Mixin plugin of vesuvio.vertex.mixins.json. Does what {@link VesuvioMixinPlugin} does (the disabledMixins kill
 * list) and leaves out every compact-vertex mixin unless the master switch and compactTerrainVertices were both on
 * at start-up: the vertex format is fixed when the game sets up its pipelines, so with the setting off nothing of
 * it may be in the game. Reads only the raw file (TerrainFormat's keys are inlined constants): no game class may be
 * loaded this early.
 */
public final class VertexMixinPlugin implements IMixinConfigPlugin {
	/**
	 * Every mixin of the config. They work only all together (the pipelines' format, the code that writes it and the
	 * code that reads it back), so naming any of them in disabledMixins leaves out all of them.
	 */
	private static final List<String> MIXINS = List.of("BufferBuilderCompactMixin", "MeshDataCompactMixin",
			"RenderPipelinesCompactMixin", "TextureAtlasCompactMixin");

	private final VesuvioMixinPlugin base = new VesuvioMixinPlugin();
	private boolean on;

	/**
	 * Whether compact vertices are on this run: master switch and setting on in the file at start-up, and none of
	 * the mixins switched off. TerrainFormat asks the same, so the format and the mixins always agree.
	 */
	static boolean startDecision() {
		if (!Boolean.parseBoolean(VesuvioConfig.raw("enabled", "true").trim())
				|| !Boolean.parseBoolean(VesuvioConfig.raw(TerrainFormat.KEY, "false").trim())) {
			return false;
		}
		for (String name : VesuvioConfig.raw("disabledMixins", "").split(",")) {
			if (MIXINS.contains(name.trim())) {
				return false;
			}
		}
		return true;
	}

	@Override
	public void onLoad(String mixinPackage) {
		base.onLoad(mixinPackage);
		on = startDecision();
		if (!on) {
			Vesuvio.LOGGER.info("Vesuvio: compact terrain vertices off (setting, master switch or disabledMixins)");
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return on && base.shouldApplyMixin(targetClassName, mixinClassName);
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
