package it.cosimo.vesuvio.ticks.mixin;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.cosimo.vesuvio.ticks.TickStats;
import it.cosimo.vesuvio.ticks.WorldTicks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;

/**
 * Setting worldPoiVerticalRange. PoiManager.getInRange (behind find, findClosest, findAll, take, getRandom,
 * getCountInRange: villagers' job/bed/meeting searches, bees' hive search, golem and raid checks) is
 * getInSquare (every chunk in the square, every sub-chunk of the world height, bottom to top) followed by the
 * filter {@code distSqr(center) <= radius * radius}. A record in a sub-chunk entirely above center.y + radius or
 * below center.y - radius has |dy| > radius, so distSqr >= dy^2 > radius^2 and the filter drops it. We build the
 * same stream (same chunk order, same sub-chunk order, same filters, same laziness) over only the sub-chunks the
 * sphere's y range touches: identical records in identical order. Every chunk still gets at least one
 * getOrLoad, at the same point of the walk, so POI data is loaded exactly when vanilla loads it. Cases the
 * argument does not cover (radius too large or negative, y range outside the world, nothing to skip) run vanilla.
 */
@Mixin(PoiManager.class)
abstract class PoiManagerMixin {
	@Inject(method = "getInRange", at = @At("HEAD"), cancellable = true)
	private void vesuvio$verticalRange(Predicate<Holder<PoiType>> predicate, BlockPos center, int radius,
			PoiManager.Occupancy occupancy, CallbackInfoReturnable<Stream<PoiRecord>> cir) {
		if (!WorldTicks.POI_RANGE.on() || radius < 0 || radius > 46340) {
			return; // 46340^2 still fits an int: radius * radius below is the exact square, as in vanilla
		}
		SectionStorageAccess storage = (SectionStorageAccess) this;
		LevelHeightAccessor height = storage.vesuvio$levelHeightAccessor();
		int minSection = height.getMinSectionY();
		int maxSection = height.getMaxSectionY();
		int from = Math.max(minSection, SectionPos.blockToSectionCoord(center.getY() - radius));
		int to = Math.min(maxSection, SectionPos.blockToSectionCoord(center.getY() + radius));
		if (from > to || (from == minSection && to == maxSection)) {
			return;
		}
		TickStats.poiSearches++;
		int radiusSqr = radius * radius;
		int chunkRadius = Math.floorDiv(radius, 16) + 1;
		cir.setReturnValue(ChunkPos.rangeClosed(ChunkPos.containing(center), chunkRadius)
				.flatMap(chunk -> IntStream.rangeClosed(from, to)
						.boxed()
						.map(sectionY -> storage.vesuvio$getOrLoad(SectionPos.of(chunk, sectionY).asLong()))
						.filter(Optional::isPresent)
						.flatMap(section -> ((PoiSection) section.get()).getRecords(predicate, occupancy)))
				.filter(record -> {
					BlockPos pos = record.getPos();
					return Math.abs(pos.getX() - center.getX()) <= radius && Math.abs(pos.getZ() - center.getZ()) <= radius;
				})
				.filter(r -> r.getPos().distSqr(center) <= radiusSqr));
	}
}
