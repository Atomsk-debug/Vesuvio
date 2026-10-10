package it.cosimo.vesuvio.memory.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.resources.Identifier;

/**
 * Setting memoryShareIdNamespaces (idea from FerriteCore/ModernFix identifier deduplication; own code). Ids parsed
 * from text ("minecraft:stone" in models, recipes, tags, network packets) each keep their own copy of the string
 * "minecraft"; this makes them use the one constant instead. Strings are compared with equals everywhere, so
 * nothing changes but memory (~56 bytes per such id).
 */
@Mixin(Identifier.class)
abstract class IdentifierMixin {
	@Shadow
	@Final
	@Mutable
	private String namespace;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void vesuvio$shareNamespace(String namespace, String path, CallbackInfo ci) {
		String ns = this.namespace;
		if (ns != Identifier.DEFAULT_NAMESPACE && Identifier.DEFAULT_NAMESPACE.equals(ns)) {
			this.namespace = Identifier.DEFAULT_NAMESPACE;
		}
	}
}
