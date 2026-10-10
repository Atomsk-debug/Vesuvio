package it.cosimo.vesuvio.chunks;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Smaller section copies (render thread, SectionCopy's constructor). To build one section, vanilla copies its 3x3x3
 * neighbourhood (RenderRegionCache, once per section per frame), and every one of those copies takes an immutable
 * copy of the whole chunk's block-entity map, all 24 sections' worth. A copy is only ever asked for block entities
 * inside its own section (RenderSectionRegion.getBlockEntity picks the copy by the position's section), so keeping
 * just those gives the same answers with a fraction of the garbage in chunks full of chests, signs or other block
 * entities (and none when the section has no block entity: the shared empty map).
 */
public final class SectionCopyTrim {
	private static long entriesSaved;

	private SectionCopyTrim() {
	}

	/** Counter: block-entity map entries not copied since start-up. */
	public static long entriesSaved() {
		return entriesSaved;
	}

	/** Null when trimming is off (the caller then does vanilla's full copy). */
	public static ImmutableMap<BlockPos, BlockEntity> copyOwn(Map<BlockPos, BlockEntity> all, LevelChunk chunk, int sectionIndex) {
		if (!ChunkUpdates.TRIM_COPIES.on()) {
			return null;
		}
		if (all.isEmpty()) {
			return ImmutableMap.of();
		}
		int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
		int maxY = minY + 15;
		ImmutableMap.Builder<BlockPos, BlockEntity> own = null;
		int kept = 0;
		for (Map.Entry<BlockPos, BlockEntity> entry : all.entrySet()) {
			int y = entry.getKey().getY();
			if (y >= minY && y <= maxY) {
				if (own == null) {
					own = ImmutableMap.builder();
				}
				own.put(entry.getKey(), entry.getValue());
				kept++;
			}
		}
		entriesSaved += all.size() - kept;
		return own == null ? ImmutableMap.of() : own.build();
	}
}
