package it.cosimo.vesuvio.models.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import it.cosimo.vesuvio.models.SignTextCache;
import net.minecraft.world.level.block.entity.SignText;

/** Setting signTextCache: a place on each SignText for its wrapped lines (read and written on the render thread). */
@Mixin(SignText.class)
abstract class SignTextLinesMixin implements SignTextCache.Holder {
	@Unique
	private SignTextCache.Lines vesuvio$signLines;

	@Override
	public SignTextCache.Lines vesuvio$signLines() {
		return this.vesuvio$signLines;
	}

	@Override
	public void vesuvio$setSignLines(SignTextCache.Lines lines) {
		this.vesuvio$signLines = lines;
	}
}
