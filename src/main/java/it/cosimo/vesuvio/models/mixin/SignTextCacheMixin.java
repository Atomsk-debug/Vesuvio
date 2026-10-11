package it.cosimo.vesuvio.models.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.models.SignTextCache;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Setting signTextCache, sign side: each line goes to the text renderer wrapped in a {@link SignTextCache.Line} (which
 * reads exactly like the line), so the text renderer can find the line's kept layout; the line's width is kept too.
 */
@Mixin(AbstractSignRenderer.class)
abstract class SignTextCacheMixin {
	@ModifyExpressionValue(method = "submitSignText", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/SignText;getRenderMessages(ZLjava/util/function/Function;)[Lnet/minecraft/util/FormattedCharSequence;"))
	private FormattedCharSequence[] vesuvio$wrapLines(FormattedCharSequence[] messages,
			@Local(argsOnly = true) SignText signText) {
		return SignTextCache.wrap(signText, messages);
	}

	@WrapOperation(method = "submitSignText", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/util/FormattedCharSequence;)I"))
	private int vesuvio$cachedWidth(Font font, FormattedCharSequence text, Operation<Integer> original) {
		return SignTextCache.width(font, text, original);
	}
}
