package it.cosimo.vesuvio.ticks.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;

/** PoiManager's inherited section access, for {@link PoiManagerMixin}. */
@Mixin(SectionStorage.class)
public interface SectionStorageAccess {
	@Invoker("getOrLoad")
	Optional<?> vesuvio$getOrLoad(long sectionPos);

	@Accessor("levelHeightAccessor")
	LevelHeightAccessor vesuvio$levelHeightAccessor();
}
