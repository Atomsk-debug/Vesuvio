package it.cosimo.vesuvio.memory;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.VesuvioMixinPlugin;
import it.cosimo.vesuvio.Vesuvio;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mixin plugin of vesuvio.memory.mixins.json. Does what {@link VesuvioMixinPlugin} does (the disabledMixins kill
 * list) and also leaves out every mixin whose setting, or the master switch, is off at start-up: these
 * optimizations act while things are built, so when off they must not be in the game at all.
 */
public final class MemoryMixinPlugin implements IMixinConfigPlugin {
	private final VesuvioMixinPlugin base = new VesuvioMixinPlugin();
	/** FerriteCore / ModernFix change the same code (and would clash with these mixins): let them do it. */
	private boolean otherMemoryMod;

	@Override
	public void onLoad(String mixinPackage) {
		base.onLoad(mixinPackage);
		FabricLoader loader = FabricLoader.getInstance();
		otherMemoryMod = loader.isModLoaded("ferritecore") || loader.isModLoaded("modernfix");
		if (otherMemoryMod) {
			Vesuvio.LOGGER.info("Vesuvio: FerriteCore or ModernFix installed, Vesuvio's memory savings stay off");
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (otherMemoryMod || !base.shouldApplyMixin(targetClassName, mixinClassName)) {
			return false;
		}
		String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		boolean on = switch (simple) {
			case "PalettedContainerMixin" -> on(MemoryBoost.KEY_THREAD_CHECKS, MemoryBoost.DEFAULT_THREAD_CHECKS);
			case "BlockShapeTableMixin", "WallBlockMixin", "CrossCollisionBlockMixin", "StateCacheShapeMixin",
					"BlockStateOcclusionShapeMixin", "VoxelShapeAccessor", "BitSetDiscreteVoxelShapeAccessor" ->
				on(MemoryBoost.KEY_SHAPES, MemoryBoost.DEFAULT_SHAPES);
			case "StateCacheSupportMixin", "BlockStateFaceTableMixin" ->
				on(MemoryBoost.KEY_STATE_ARRAYS, MemoryBoost.DEFAULT_STATE_ARRAYS);
			case "IdentifierMixin" -> on(MemoryBoost.KEY_IDENTIFIERS, MemoryBoost.DEFAULT_IDENTIFIERS);
			default -> true;
		};
		if (!on) {
			Vesuvio.LOGGER.info("Vesuvio: {} not applied (its memory setting or the master switch is off)", simple);
		}
		return on;
	}

	private static boolean on(String key, boolean fallback) {
		return Boolean.parseBoolean(VesuvioConfig.raw("enabled", "true").trim())
				&& Boolean.parseBoolean(VesuvioConfig.raw(key, Boolean.toString(fallback)).trim());
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
