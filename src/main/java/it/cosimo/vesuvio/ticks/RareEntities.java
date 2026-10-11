package it.cosimo.vesuvio.ticks;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import com.google.common.collect.Lists;

import it.cosimo.vesuvio.VesuvioConfig;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Continuation;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;

/**
 * Setting worldRareEntityIndex. Two box lookups run for many entities every tick but can only ever return a few
 * kinds of entity:
 * <ul>
 * <li>entity collisions (Level.getEntityCollisions: every moving entity in Entity.collide, every item entity's
 * noCollision check): with the vanilla predicate an entity passes only if its {@code canBeCollidedWith} says so,
 * and Entity's own version is {@code return false}. Only classes that override it can pass (boats, minecarts,
 * shulkers, happy ghasts in vanilla).</li>
 * <li>hopper container entities (HopperBlockEntity.getEntityContainer, every idle hopper every tick): the
 * predicate starts with {@code entity instanceof Container}.</li>
 * </ul>
 * Each entity section keeps, next to its own list, the sub-list of entities of those classes, updated in the
 * same add/remove calls in the same order. A lookup then walks the same sections (same storage method) and in
 * each only the sub-list, applying the unchanged vanilla tests: the result list is the same list in the same
 * order (the dropped entities could never pass), so the random index the hopper picks is the same too.
 */
public final class RareEntities {
	public static final int COLLIDABLE = 0;
	public static final int CONTAINER = 1;
	public static final int KINDS = 2;

	/** Whether the sections fill their lists in this session (decided once, at start-up: restart setting). */
	public static final boolean MAINTAIN = WorldTicks.RARE_ENTITY_INDEX.get() && VesuvioConfig.ENABLED.get();

	/** Bit k set: instances of the class go in list k. */
	private static final ClassValue<Integer> KIND = new ClassValue<>() {
		@Override
		protected Integer computeValue(Class<?> type) {
			if (!Entity.class.isAssignableFrom(type)) {
				return 0; // not an Entity: never in a Level's entity storage, never looked up here
			}
			int bits = 0;
			if (overrides(type, "canBeCollidedWith")) {
				bits |= 1 << COLLIDABLE;
			}
			if (Container.class.isAssignableFrom(type)) {
				bits |= 1 << CONTAINER;
			}
			return bits;
		}
	};

	/** Whether the class has its own canCollideWith (boats and minecarts also collide with pushable entities). */
	private static final ClassValue<Boolean> OWN_CAN_COLLIDE_WITH = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return overrides(type, "canCollideWith");
		}
	};

	private RareEntities() {
	}

	static void init() {
		// Class initialisation decides MAINTAIN.
	}

	/** The declaring class of the public method {@code name(Entity)} is not Entity. Unsure means yes (safe). */
	private static boolean overrides(Class<?> type, String name) {
		try {
			return type.getMethod(name, Entity.class).getDeclaringClass() != Entity.class;
		} catch (Throwable e) {
			return true;
		}
	}

	/** Bit set of the lists an entity belongs to (a class-only property, so it never changes for an entity). */
	public static int kinds(Object entity) {
		return KIND.get(entity.getClass());
	}

	/** The index can be used right now. */
	public static boolean active() {
		return MAINTAIN && WorldTicks.RARE_ENTITY_INDEX.on();
	}

	/** Whether getEntityCollisions(source, ...) uses the plain vanilla predicate (Entity.canCollideWith). */
	public static boolean plainCollider(Entity source) {
		return source == null || !OWN_CAN_COLLIDE_WITH.get(source.getClass());
	}

	/**
	 * Level.getEntities(except, bb, selector) restricted to the entities of one kind. Callers guarantee that an
	 * entity outside the kind can never pass {@code selector}. Mirrors vanilla step by step: the profiler counter,
	 * sections through the storage's own walk, per section the entities whose box intersects bb (vanilla tests
	 * that first), then {@code entity != except && selector.test(entity)}, then the dragon parts loop.
	 */
	public static List<Entity> getEntities(Level level, EntitySectionStorage<Entity> storage, Entity except, AABB bb,
			Predicate<? super Entity> selector, int kind) {
		TickStats.rareLookups++;
		Profiler.get().incrementCounter("getEntities");
		List<Entity> output = Lists.newArrayList();
		AbortableIterationConsumer<EntitySection<Entity>> visitor = section -> {
			for (EntityAccess access : ((RareEntitySection) section).vesuvio$rare(kind)) {
				Entity entity = (Entity) access;
				if (entity.getBoundingBox().intersects(bb) && entity != except && selector.test(entity)) {
					output.add(entity);
				}
			}
			return Continuation.CONTINUE;
		};
		storage.forEachAccessibleNonEmptySection(bb, visitor);
		for (EnderDragonPart dragonPart : level.dragonParts()) {
			if (dragonPart != except && dragonPart.parentMob != except && selector.test(dragonPart)
					&& bb.intersects(dragonPart.getBoundingBox())) {
				output.add(dragonPart);
			}
		}
		return output;
	}

	public static List<EntityAccess> empty() {
		return Collections.emptyList();
	}
}
