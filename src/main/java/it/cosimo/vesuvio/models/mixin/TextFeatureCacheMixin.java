package it.cosimo.vesuvio.models.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import it.cosimo.vesuvio.models.SignTextCache;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;

/**
 * Setting signTextCache, text renderer side: a sign line's layout (plain, and the 8-way outline of glowing text) is
 * taken from its cache when everything it depends on is unchanged; the vertices are still written every frame with
 * the current pose by vanilla's own glyph visitor. Any other text goes straight to vanilla.
 */
@Mixin(TextFeatureRenderer.class)
abstract class TextFeatureCacheMixin {
	@WrapOperation(method = "renderText", require = 2, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;"))
	private static Font.PreparedText vesuvio$cachedText(Font font, FormattedCharSequence text, float x, float y, int color,
			boolean drawShadow, boolean includeEmpty, int backgroundColor, Operation<Font.PreparedText> original) {
		return SignTextCache.prepareText(font, text, x, y, color, drawShadow, includeEmpty, backgroundColor, original);
	}

	@WrapOperation(method = "renderText", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;prepare8xTextOutline(Lnet/minecraft/util/FormattedCharSequence;FFI)Lnet/minecraft/client/gui/Font$PreparedText;"))
	private static Font.PreparedText vesuvio$cachedOutline(Font font, FormattedCharSequence text, float x, float y,
			int outlineColor, Operation<Font.PreparedText> original) {
		return SignTextCache.prepareOutline(font, text, x, y, outlineColor, original);
	}
}
