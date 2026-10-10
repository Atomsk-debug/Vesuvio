package it.cosimo.vesuvio.frame;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

/**
 * The animated sprites a block model can draw, worked out once per model (vanilla's three model classes carry the
 * answer, see {@link Holder}) and added to a section's set for every block the section compiles. This sits before the
 * block renderer, so it works whether the quads are then written by vanilla's renderer or by Fabric API's Indigo
 * (which replaces it in SectionCompiler). It is conservative: every variant and every part a model may pick counts,
 * whether or not the face ends up culled. A model of any other class (from a mod) makes the section "unknown",
 * which keeps every sprite animating while it is on screen.
 */
public final class ModelSprites {
	private static final Direction[] FACES = { null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
			Direction.WEST, Direction.EAST };

	private ModelSprites() {
	}

	/** Implemented by SingleVariant, WeightedVariants and MultiPartModel (frame mixins). */
	public interface Holder {
		/** The animated sprites this model can draw (computed once), or null when unknown. */
		SpriteContents @Nullable [] vesuvio$animatedSprites();
	}

	/** Section compile thread: adds what {@code model} can draw to {@code out}. */
	public static void collect(BlockStateModel model, SpriteSet out) {
		if (!(model instanceof Holder holder)) {
			out.markUnknown();
			return;
		}
		if (!model.hasMaterialFlag(BakedQuad.FLAG_ANIMATED)) {
			return;
		}
		SpriteContents[] sprites = holder.vesuvio$animatedSprites();
		if (sprites == null) {
			out.markUnknown();
		} else {
			out.addAll(sprites);
		}
	}

	/** The animated sprites of one model part, every face. */
	public static void addPart(BlockStateModelPart part, SpriteSet out) {
		for (Direction face : FACES) {
			for (BakedQuad quad : part.getQuads(face)) {
				out.add(quad.materialInfo().sprite());
			}
		}
	}

	/** A sub-model's sprites (WeightedVariants entries, MultiPartModel parts); false when it is unknown. */
	public static boolean addModel(BlockStateModel model, SpriteSet out) {
		if (!(model instanceof Holder holder)) {
			return false;
		}
		SpriteContents[] sprites = holder.vesuvio$animatedSprites();
		if (sprites == null) {
			return false;
		}
		out.addAll(sprites);
		return true;
	}

	/** The parts a model picks with a fixed seed (single variants pick the same part whatever the seed). */
	public static List<BlockStateModelPart> partsOf(BlockStateModel model) {
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(0L), parts);
		return parts;
	}
}
