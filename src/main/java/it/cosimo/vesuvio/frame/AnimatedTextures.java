package it.cosimo.vesuvio.frame;

import org.jspecify.annotations.Nullable;

import it.cosimo.vesuvio.VesuvioConfig;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/**
 * Animated block textures (water, lava, fire, portal, sea lantern, prismarine, ...) are redrawn into the block
 * atlas only while something on screen uses them. Vanilla redraws every animated sprite of every atlas on every
 * texture tick (20 a second), one render pass per mip level, whether or not anything shows it.
 *
 * <p>How "on screen" is known, conservatively:
 * <ul>
 * <li>terrain: each compiled section mesh lists the animated sprites its block models and fluids use (collected on the
 * compile worker), and the list is marked whenever vanilla (or Etna's shadow pass, which goes through the same
 * code) prepares that section for drawing;</li>
 * <li>baked quads drawn outside terrain (items anywhere, including the GUI; block models held or carried by entities;
 * moving pistons and falling blocks) are marked as they are written;</li>
 * <li>everything else that draws an atlas sprite (particles, entity fire, the fire/portal/in-wall overlays, block
 * entity textures) reads its UVs through {@link TextureAtlasSprite}'s getters, and every getter call marks it;</li>
 * <li>a mesh compiled while this was off, or one that still points at sprites from before a resource reload, counts
 * as "unknown" and keeps every sprite animating.</li>
 * </ul>
 * A sprite not marked for {@link #GRACE_TICKS} texture ticks stops being redrawn (its animation clock keeps running,
 * so it resumes in step with vanilla). When it is marked again it is redrawn on the next texture tick, at most one
 * tick (50 ms) after it comes into view. Only the block atlas is affected; other atlases always animate.
 */
public final class AnimatedTextures {
	public static final VesuvioConfig.Toggle SETTING = VesuvioConfig.toggle("frame", "animatedTextures", true, false);

	/** Texture ticks a sprite keeps animating after it was last seen. */
	static final int GRACE_TICKS = 8;
	private static final int NEVER = -1_000_000;

	/** Animated block-atlas sprites not redrawn at the last texture tick (for the settings screen). */
	public static int pausedLastTick;
	/** Animated block-atlas sprites in total at the last texture tick. */
	public static int animatedLastTick;

	/** Texture tick counter, render thread. */
	private static int now;
	/** Mirror of SETTING.on(), refreshed every texture tick; plain field so the hot hooks read it for free. */
	public static boolean active;
	/** Last texture tick something "unknown" was drawn: every sprite counts as seen then. */
	private static int unknownSeen = NEVER;
	/** True only while the block atlas cycles its animations. */
	private static boolean pruning;
	private static int pausedCounting;
	private static int animatedCounting;

	private AnimatedTextures() {
	}

	public static void init() {
		// Touching the class creates the setting. F3, the settings screen's box, the stats log and the benchmark.
		it.cosimo.vesuvio.ui.VesuvioStats.register("animated sprites paused", () -> pausedLastTick);
	}

	/** AtlasManager.tick, before any atlas cycles. Render thread. */
	public static void beginTick() {
		now++;
		boolean on = SETTING.on();
		if (on && !active) {
			// Nothing was marked while off: give everything one grace period.
			unknownSeen = now;
		}
		active = on;
	}

	/** TextureAtlas.cycleAnimationFrames, start. */
	public static void beginAtlas(boolean blockAtlas) {
		pruning = active && blockAtlas;
		if (pruning) {
			pausedCounting = 0;
			animatedCounting = 0;
		}
	}

	/** TextureAtlas.cycleAnimationFrames, end. */
	public static void endAtlas() {
		if (pruning) {
			pausedLastTick = pausedCounting;
			animatedLastTick = animatedCounting;
		} else if (!active) {
			pausedLastTick = 0;
		}
		pruning = false;
	}

	/**
	 * AnimationState.tick, end: true when a paused sprite must be redrawn now (it came back into view, or pruning
	 * stopped), so the caller marks it dirty.
	 */
	public static boolean afterStateTick(AnimationStateExt state) {
		SpriteContents owner = state.vesuvio$owner();
		boolean visible = !pruning || owner == null || isSeen(owner);
		if (pruning) {
			animatedCounting++;
			if (!visible) {
				pausedCounting++;
			}
		}
		if (state.vesuvio$stale() && visible) {
			state.vesuvio$setStale(false);
			return true;
		}
		return false;
	}

	/** AnimationState.needsToDraw: vanilla's answer, turned to false for a sprite nobody sees. */
	public static boolean needsToDraw(AnimationStateExt state, boolean original) {
		if (!original || !pruning) {
			return original;
		}
		SpriteContents owner = state.vesuvio$owner();
		if (owner == null || isSeen(owner)) {
			return true;
		}
		state.vesuvio$setStale(true);
		return false;
	}

	private static boolean isSeen(SpriteContents contents) {
		int seen = Math.max(((SpriteContentsExt) contents).vesuvio$seen(), unknownSeen);
		return now - seen <= GRACE_TICKS;
	}

	public static void mark(SpriteContents contents) {
		SpriteContentsExt ext = (SpriteContentsExt) contents;
		// Write only on change: the same sprite is marked from several threads (fluids on compile workers).
		if (ext.vesuvio$seen() != now) {
			ext.vesuvio$setSeen(now);
		}
	}

	public static void markQuad(BakedQuad quad) {
		if (active) {
			mark(quad.materialInfo().sprite().contents());
		}
	}

	public static void markUnknown() {
		unknownSeen = now;
	}

	/** A section about to be drawn (main view or Etna's shadow casters). Render thread. */
	public static void markMesh(@Nullable SectionMesh mesh) {
		if (!active || !(mesh instanceof CompiledMeshExt ext)) {
			return;
		}
		SpriteContents[] sprites = ext.vesuvio$animatedSprites();
		if (sprites == null) {
			markUnknown();
			return;
		}
		for (SpriteContents sprite : sprites) {
			if (!((SpriteContentsExt) sprite).vesuvio$live()) {
				// The mesh predates a resource reload: its sprites are not the atlas's any more.
				markUnknown();
				return;
			}
			mark(sprite);
		}
	}
}
