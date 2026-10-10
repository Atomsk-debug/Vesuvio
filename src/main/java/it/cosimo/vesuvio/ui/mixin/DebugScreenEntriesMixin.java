package it.cosimo.vesuvio.ui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.ui.VesuvioDebugEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;

/** Registers Vesuvio's F3 entry with vanilla's, once, when the entry table is built. */
@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {
	@Shadow
	private static Identifier register(Identifier identifier, DebugScreenEntry entry) {
		throw new AssertionError();
	}

	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void vesuvio$register(CallbackInfo ci) {
		register(VesuvioDebugEntry.ID, new VesuvioDebugEntry());
	}
}
