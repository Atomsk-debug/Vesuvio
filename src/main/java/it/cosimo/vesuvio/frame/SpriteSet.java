package it.cosimo.vesuvio.frame;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** A small identity set of animated sprites, filled by one compile worker (a section uses a handful at most). */
public final class SpriteSet {
	static final SpriteContents[] NONE = new SpriteContents[0];
	private SpriteContents[] items = NONE;
	private int size;
	private boolean unknown;

	/** Something in the section may draw sprites this set cannot list: the mesh will keep every sprite animating. */
	public void markUnknown() {
		unknown = true;
	}

	public void addAll(SpriteContents[] sprites) {
		for (SpriteContents contents : sprites) {
			addContents(contents);
		}
	}

	public void add(TextureAtlasSprite sprite) {
		addContents(sprite.contents());
	}

	private void addContents(SpriteContents contents) {
		if (!contents.isAnimated()) {
			return;
		}
		for (int i = 0; i < size; i++) {
			if (items[i] == contents) {
				return;
			}
		}
		if (size == items.length) {
			SpriteContents[] grown = new SpriteContents[Math.max(4, size * 2)];
			System.arraycopy(items, 0, grown, 0, size);
			items = grown;
		}
		items[size++] = contents;
	}

	/** The sprites, or null when {@link #markUnknown() unknown}. */
	public SpriteContents[] toArray() {
		if (unknown) {
			return null;
		}
		if (size == 0) {
			return NONE;
		}
		SpriteContents[] out = new SpriteContents[size];
		System.arraycopy(items, 0, out, 0, size);
		return out;
	}
}
