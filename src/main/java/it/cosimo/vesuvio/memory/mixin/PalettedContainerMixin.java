package it.cosimo.vesuvio.memory.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import it.cosimo.vesuvio.memory.LightLock;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Setting memoryLightThreadChecks: chunk sections' block and biome containers skip their ThreadingDetector
 * (Semaphore + ReentrantLock, ~128 bytes each, and new ones for every copy made to build a section mesh or save a
 * chunk) and use {@link LightLock} instead. Only applied when the setting is on at start-up.
 */
@Mixin(PalettedContainer.class)
abstract class PalettedContainerMixin {
	/** null, the Thread inside acquire()..release(), or the crash report to throw (see LightLock). */
	@Unique
	private volatile Object vesuvio$holder;

	/** The field initializer, inlined in the four constructors that don't delegate to this(...). */
	@Redirect(method = "<init>*", at = @At(value = "NEW", target = "net/minecraft/util/ThreadingDetector"), require = 4)
	private ThreadingDetector vesuvio$noDetector(String name) {
		return LightLock.AVAILABLE ? LightLock.MARKER : new ThreadingDetector(name);
	}

	@Redirect(method = "acquire", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ThreadingDetector;checkAndLock()V"))
	private void vesuvio$lock(ThreadingDetector detector) {
		if (detector != LightLock.MARKER) {
			detector.checkAndLock();
		} else {
			LightLock.lock((PalettedContainer<?>) (Object) this);
		}
	}

	@Redirect(method = "release", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ThreadingDetector;checkAndUnlock()V"))
	private void vesuvio$unlock(ThreadingDetector detector) {
		if (detector != LightLock.MARKER) {
			detector.checkAndUnlock();
		} else {
			LightLock.unlock((PalettedContainer<?>) (Object) this);
		}
	}
}
