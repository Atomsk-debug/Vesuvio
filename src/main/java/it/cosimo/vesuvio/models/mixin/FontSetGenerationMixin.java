package it.cosimo.vesuvio.models.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.models.SignTextCache;
import net.minecraft.client.gui.font.FontSet;

/**
 * Setting signTextCache: a font set throws its glyphs away on a resource reload, on a font option change (Force
 * Unicode, Japanese glyph variants) and when it is closed; every kept sign text layout goes with them.
 */
@Mixin(FontSet.class)
abstract class FontSetGenerationMixin {
	@Inject(method = "resetTextures", at = @At("HEAD"))
	private void vesuvio$glyphsReset(CallbackInfo ci) {
		SignTextCache.invalidate();
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void vesuvio$fontSetClosed(CallbackInfo ci) {
		SignTextCache.invalidate();
	}
}
