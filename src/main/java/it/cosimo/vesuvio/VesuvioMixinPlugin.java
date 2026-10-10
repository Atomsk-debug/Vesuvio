package it.cosimo.vesuvio;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Lets a broken optimization be switched off without a new build: any mixin whose simple class name is in
 * "disabledMixins" in config/vesuvio.properties is not applied. Live on/off switches live in VesuvioConfig.
 */
public final class VesuvioMixinPlugin implements IMixinConfigPlugin {
	private final Set<String> disabled = new HashSet<>();

	@Override
	public void onLoad(String mixinPackage) {
		for (String name : Arrays.asList(VesuvioConfig.raw("disabledMixins", "").split(","))) {
			if (!name.isBlank()) {
				disabled.add(name.trim());
			}
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (disabled.contains(simple)) {
			Vesuvio.LOGGER.warn("Vesuvio: {} switched off in vesuvio.properties (disabledMixins)", simple);
			return false;
		}
		return true;
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
