package it.cosimo.vesuvio.frame;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** A small identity set of animated sprites, filled by one compile worker (a section uses a handful at most). */
public final class SpriteSet {
	static final SpriteContents[] NONE = new SpriteContents[0];
	private SpriteContents[] items = NONE;
	private int size;

	public void add(TextureAtlasSprite sprite) {
		SpriteContents contents = sprite.contents();
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

	public SpriteContents[] toArray() {
		if (size == 0) {
			return NONE;
		}
		SpriteContents[] out = new SpriteContents[size];
		System.arraycopy(items, 0, out, 0, size);
		return out;
	}
}
