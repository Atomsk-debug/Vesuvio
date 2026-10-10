package it.cosimo.vesuvio.frame;

/** Added to SpriteContents: when it was last seen, and whether it belongs to a loaded atlas animation. */
public interface SpriteContentsExt {
	int vesuvio$seen();

	void vesuvio$setSeen(int tick);

	boolean vesuvio$live();

	void vesuvio$setLive(boolean live);
}
