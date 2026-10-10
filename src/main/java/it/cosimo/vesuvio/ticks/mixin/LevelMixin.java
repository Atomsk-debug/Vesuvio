package it.cosimo.vesuvio.ticks.mixin;

import java.util.List;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import it.cosimo.vesuvio.ticks.LevelEntityStorage;
import it.cosimo.vesuvio.ticks.RareEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Setting worldRareEntityIndex: Level gets its own getEntityCollisions (vanilla Level inherits the default from
 * EntityGetter via CommonLevelAccessor; no vanilla subclass overrides it). Its body is the vanilla default, line
 * for line, except where the entity list comes from: when the index is on and the source uses the plain
 * Entity.canCollideWith, only the sections' collidable lists are walked ({@link RareEntities#getEntities}).
 * Callers: Entity.collide (every moving entity, both sides), CollisionGetter.noCollision / noEntityCollision
 * (item entities every tick, spawning, teleports...), getCollisions, getPreMoveCollisions.
 */
@Mixin(Level.class)
abstract class LevelMixin implements LevelEntityStorage {
	@Shadow
	protected abstract LevelEntityGetter<Entity> getEntities();

	@Shadow
	public abstract List<Entity> getEntities(@Nullable Entity except, AABB bb, Predicate<? super Entity> selector);

	@Override
	@SuppressWarnings("unchecked")
	public EntitySectionStorage<Entity> vesuvio$entityStorage() {
		LevelEntityGetter<Entity> getter = this.getEntities();
		return getter instanceof LevelEntityGetterAdapter
				? (EntitySectionStorage<Entity>) ((LevelEntityGetterAdapterAccessor) getter).vesuvio$sectionStorage()
				: null;
	}

	/** Overrides EntityGetter.getEntityCollisions (the vanilla default, copied, with the faster entity list). */
	public List<VoxelShape> getEntityCollisions(@Nullable final Entity source, final AABB testArea) {
		if (testArea.getSize() < 1.0E-7) {
			return List.of();
		} else {
			Predicate<Entity> canCollide = source == null ? EntitySelector.CAN_BE_COLLIDED_WITH
					: EntitySelector.NO_SPECTATORS.and(source::canCollideWith);
			List<Entity> collidingEntities;
			EntitySectionStorage<Entity> storage;
			if (RareEntities.active() && RareEntities.plainCollider(source)
					&& (storage = this.vesuvio$entityStorage()) != null) {
				collidingEntities = RareEntities.getEntities((Level) (Object) this, storage, source,
						testArea.inflate(1.0E-7), canCollide, RareEntities.COLLIDABLE);
			} else {
				collidingEntities = this.getEntities(source, testArea.inflate(1.0E-7), canCollide);
			}
			if (collidingEntities.isEmpty()) {
				return List.of();
			} else {
				Builder<VoxelShape> shapes = ImmutableList.builderWithExpectedSize(collidingEntities.size());

				for (Entity entity : collidingEntities) {
					shapes.add(Shapes.create(entity.getBoundingBox()));
				}

				return shapes.build();
			}
		}
	}
}
