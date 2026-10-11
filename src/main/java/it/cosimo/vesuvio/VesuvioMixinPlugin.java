package it.cosimo.vesuvio;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Lets a broken optimization be switched off without a new build: any mixin whose simple class name is in
 * "disabledMixins" in config/vesuvio.properties is not applied. Live on/off switches live in VesuvioConfig.
 * A mixin that casts to an interface (or calls an accessor) another mixin adds goes with it: it would otherwise throw
 * ClassCastException the first time it runs.
 */
public final class VesuvioMixinPlugin implements IMixinConfigPlugin {
	/** Mixin -> the mixins whose interfaces it (or the code it calls) casts to. */
	private static final Map<String, List<String>> NEEDS = Map.ofEntries(
			// chunks
			Map.entry("ResortThrottleMixin", List.of("RenderSectionStateMixin")),
			Map.entry("ExtractSchedulingMixin", List.of("RenderSectionInvoker")),
			Map.entry("UberBufferBudgetMixin", List.of("StagedEntryAccessor", "StagingBufferAccessor")),
			Map.entry("StagingMappedMixin", List.of("StagingCpuMixin")),
			// clears: holding a clear back is only safe with every hook that sends, splits or drops it
			Map.entry("ClearsFrontendEncoderMixin", List.of("ClearsFrontendRenderPassMixin", "ClearsFrontendSurfaceMixin",
					"ClearsVulkanEncoderAccessor", "ClearsVulkanEncoderMixin", "ClearsVulkanTextureMixin")),
			Map.entry("ClearsFrontendRenderPassMixin", List.of("ClearsVulkanEncoderAccessor")),
			Map.entry("ClearsPostChainMixin", List.of("ClearsPostPassAccessor")),
			// entities
			Map.entry("LevelExtractorMixin", List.of("EntityCullStateMixin", "BlockEntityCullStateMixin",
					"EntityRendererInvoker", "FrustumInvoker")),
			// reads the particle's position through the culling package's accessor
			Map.entry("QuadParticleGroupMixin", List.of("ParticleAccessor")),
			// culling
			Map.entry("CullLevelExtractorMixin", List.of("CullEntityRendererInvoker", "FogRendererAccessor")),
			// entity fog culling runs on the state CullLevelExtractorMixin sets up each frame
			Map.entry("CullEntityVisibilityMixin", List.of("CullLevelExtractorMixin", "CullEntityRendererInvoker")),
			// section fog culling only acts inside the main-view mark CullLevelRendererMixin sets
			Map.entry("CullSectionMeshMixin", List.of("CullLevelRendererMixin")),
			Map.entry("CullLevelRendererMixin", List.of("FogRendererAccessor")),
			Map.entry("FogParticleGroupMixin", List.of("ParticleAccessor")),
			// models: the sign side casts SignText to the holder; without the font generation a kept layout could
			// outlive a resource reload; without the sign side the text side never sees a cached line (harmless).
			Map.entry("SignTextCacheMixin", List.of("SignTextLinesMixin", "FontSetGenerationMixin")),
			Map.entry("TextFeatureCacheMixin", List.of("FontSetGenerationMixin")),
			// faces
			Map.entry("SectionCompilerMixin", List.of("SectionCompilerResultsMixin")),
			Map.entry("CompiledSectionMeshMixin", List.of("SectionCompilerResultsMixin")),
			// Without the allocation counter the cache could hand back stale lists; without the main-view mark it
			// never serves anything.
			Map.entry("LevelRendererDrawCacheMixin", List.of("UberAllocationsMixin", "LevelRendererFacesMixin")),
			// frame
			Map.entry("FrameSectionCompilerMixin", List.of("FrameSectionResultsMixin")),
			Map.entry("FrameCompiledSectionMeshMixin", List.of("FrameSectionResultsMixin", "FrameSpriteContentsMixin")),
			Map.entry("FrameSpriteContentsMixin", List.of("FrameAnimationStateMixin")),
			Map.entry("FrameAnimationStateMixin", List.of("FrameSpriteContentsMixin")),
			Map.entry("FrameAtlasSpriteMixin", List.of("FrameSpriteContentsMixin")),
			Map.entry("FrameBlockModelFeatureRendererMixin", List.of("FrameSpriteContentsMixin")),
			Map.entry("FrameItemFeatureRendererMixin", List.of("FrameSpriteContentsMixin")),
			Map.entry("FrameMovingBlockFeatureRendererMixin", List.of("FrameSpriteContentsMixin")),
			Map.entry("FrameLevelRendererMixin", List.of("FrameSpriteContentsMixin")),
			// Kept framebuffers must go when their views close, or a reused view handle could match a stale one.
			Map.entry("FrameFramebufferCacheMixin", List.of("FrameTextureViewCloseMixin")),
			Map.entry("FrameTextureViewCloseMixin", List.of("FrameFramebufferCacheMixin")),
			Map.entry("FrameStagedVertexBufferMixin", List.of("FrameStagedDrawAccessor", "FrameStagedVertexBufferInvoker")),
			// The staging buffer's timestamps go through the encoder's recorder.
			Map.entry("FrameGpuTimersStagingMixin", List.of("FrameGpuTimersEncoderMixin")),
			// ticks
			Map.entry("LevelMixin", List.of("EntitySectionMixin", "LevelEntityGetterAdapterAccessor")),
			Map.entry("HopperBlockEntityMixin", List.of("LevelMixin", "EntitySectionMixin")),
			Map.entry("WalkNodeEvaluatorMixin", List.of("BlockStatePathTypeMixin")),
			Map.entry("PoiManagerMixin", List.of("SectionStorageAccess")),
			// memory
			Map.entry("BlockShapeTableMixin", List.of("VoxelShapeAccessor", "BitSetDiscreteVoxelShapeAccessor")),
			Map.entry("StateCacheShapeMixin", List.of("VoxelShapeAccessor", "BitSetDiscreteVoxelShapeAccessor")),
			Map.entry("BlockStateOcclusionShapeMixin", List.of("VoxelShapeAccessor", "BitSetDiscreteVoxelShapeAccessor")));

	private final Set<String> disabled = new HashSet<>();

	@Override
	public void onLoad(String mixinPackage) {
		for (String name : Arrays.asList(VesuvioConfig.raw("disabledMixins", "").split(","))) {
			if (!name.isBlank()) {
				disabled.add(name.trim());
			}
		}
		boolean changed = !disabled.isEmpty();
		while (changed) {
			changed = false;
			for (Map.Entry<String, List<String>> entry : NEEDS.entrySet()) {
				if (!disabled.contains(entry.getKey()) && entry.getValue().stream().anyMatch(disabled::contains)) {
					disabled.add(entry.getKey());
					changed = true;
				}
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
			Vesuvio.LOGGER.warn("Vesuvio: {} switched off in vesuvio.properties (disabledMixins, or a mixin it needs is)",
					simple);
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
