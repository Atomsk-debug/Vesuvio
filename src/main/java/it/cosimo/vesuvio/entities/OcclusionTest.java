package it.cosimo.vesuvio.entities;

import java.util.Arrays;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * "Can the camera see any part of this box?" through the block grid, for the cull worker thread only (one instance,
 * not thread-safe). Only full opaque, visible blocks ({@code BlockState.isSolidRender()}: stone, dirt, planks...
 * not glass, leaves, slabs, doors, water, barriers) stop a ray; unloaded chunks and the world outside the build
 * height count as air.
 *
 * <p>A box is visible when a straight segment from the camera to some point of it crosses only non-opaque voxels
 * (3D DDA voxel walk, the camera's own voxel ignored). Points tried: the box centre, then a grid (spacing at most
 * {@value #STEP} blocks) on each box face turned towards the camera. Rays are walked from the camera outwards, so a
 * wall next to the camera stops them after a few voxels. Opacity of the 64x64x64 voxels around the camera is cached
 * for one pass.
 *
 * <p>Same approach as LogisticsCraft's OcclusionCulling (MIT, used by EntityCulling): visibility by ray casts from
 * the camera to sample points on the faces of the box that face it. This is an independent implementation, no code
 * copied.
 *
 * <p>Reads of the client level from this thread: {@code ClientChunkCache} keeps chunks in an AtomicReferenceArray
 * and a volatile storage reference, and {@code PalettedContainer.get} reads its volatile data once, so a read is
 * at worst one update stale. Anything that still goes wrong (a storage swap during a render distance change) throws,
 * and the caller then treats the box as visible.
 */
final class OcclusionTest {
	/** Largest spacing of the sample grid on a box face, in blocks. */
	static final double STEP = 0.5;
	/** At most this many grid cells per face side (big boxes get a coarser grid). */
	private static final int MAX_CELLS = 8;
	/** Boxes further than this are not tested (reported visible). */
	private static final double MAX_DISTANCE = 256.0;
	private static final double EPS = 1.0E-3;

	private static final int SIZE = 64;
	private static final int HALF = SIZE / 2;
	/** 0 = not looked up this pass, 1 = clear, 2 = opaque. */
	private final byte[] cache = new byte[SIZE * SIZE * SIZE];
	private int originX;
	private int originY;
	private int originZ;

	private ClientChunkCache chunks;
	private int minY;
	private int maxY;
	private int minSectionY;

	private int sectionX;
	private int sectionY;
	private int sectionZ;
	private boolean sectionKnown;
	private LevelChunkSection section;

	private double camX;
	private double camY;
	private double camZ;
	private int camVoxelX;
	private int camVoxelY;
	private int camVoxelZ;

	/** Rays cast since start (single writer: the worker). */
	long rays;

	void begin(ClientLevel level, double x, double y, double z) {
		this.chunks = level.getChunkSource();
		this.minY = level.getMinY();
		this.maxY = level.getMaxY();
		this.minSectionY = level.getMinSectionY();
		this.camX = x;
		this.camY = y;
		this.camZ = z;
		this.camVoxelX = floor(x);
		this.camVoxelY = floor(y);
		this.camVoxelZ = floor(z);
		this.originX = camVoxelX - HALF;
		this.originY = camVoxelY - HALF;
		this.originZ = camVoxelZ - HALF;
		Arrays.fill(cache, (byte) 0);
		sectionKnown = false;
		section = null;
	}

	/** Drop level references between passes so an unloaded world can be collected. */
	void end() {
		chunks = null;
		section = null;
		sectionKnown = false;
	}

	boolean boxVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		if (camX >= minX && camX <= maxX && camY >= minY && camY <= maxY && camZ >= minZ && camZ <= maxZ) {
			return true;
		}
		double ccx = (minX + maxX) * 0.5;
		double ccy = (minY + maxY) * 0.5;
		double ccz = (minZ + maxZ) * 0.5;
		double dx = ccx - camX;
		double dy = ccy - camY;
		double dz = ccz - camZ;
		if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE * MAX_DISTANCE) {
			return true;
		}
		if (ray(ccx, ccy, ccz)) {
			return true;
		}
		// X faces: grid over (y, z)
		if (camX < minX || camX > maxX) {
			double fx = camX < minX ? minX + EPS : maxX - EPS;
			if (faceX(fx, minY, minZ, maxY, maxZ)) {
				return true;
			}
		}
		if (camY < minY || camY > maxY) {
			double fy = camY < minY ? minY + EPS : maxY - EPS;
			if (faceY(fy, minX, minZ, maxX, maxZ)) {
				return true;
			}
		}
		if (camZ < minZ || camZ > maxZ) {
			double fz = camZ < minZ ? minZ + EPS : maxZ - EPS;
			if (faceZ(fz, minX, minY, maxX, maxY)) {
				return true;
			}
		}
		return false;
	}

	private static int cells(double size) {
		return Math.max(1, Math.min(MAX_CELLS, (int) Math.ceil(size / STEP)));
	}

	private boolean faceX(double x, double minY, double minZ, double maxY, double maxZ) {
		int nu = cells(maxY - minY);
		int nv = cells(maxZ - minZ);
		double y0 = minY + EPS;
		double z0 = minZ + EPS;
		double su = (maxY - minY - 2 * EPS) / nu;
		double sv = (maxZ - minZ - 2 * EPS) / nv;
		for (int i = 0; i <= nu; i++) {
			for (int j = 0; j <= nv; j++) {
				if (ray(x, y0 + i * su, z0 + j * sv)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean faceY(double y, double minX, double minZ, double maxX, double maxZ) {
		int nu = cells(maxX - minX);
		int nv = cells(maxZ - minZ);
		double x0 = minX + EPS;
		double z0 = minZ + EPS;
		double su = (maxX - minX - 2 * EPS) / nu;
		double sv = (maxZ - minZ - 2 * EPS) / nv;
		for (int i = 0; i <= nu; i++) {
			for (int j = 0; j <= nv; j++) {
				if (ray(x0 + i * su, y, z0 + j * sv)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean faceZ(double z, double minX, double minY, double maxX, double maxY) {
		int nu = cells(maxX - minX);
		int nv = cells(maxY - minY);
		double x0 = minX + EPS;
		double y0 = minY + EPS;
		double su = (maxX - minX - 2 * EPS) / nu;
		double sv = (maxY - minY - 2 * EPS) / nv;
		for (int i = 0; i <= nu; i++) {
			for (int j = 0; j <= nv; j++) {
				if (ray(x0 + i * su, y0 + j * sv, z)) {
					return true;
				}
			}
		}
		return false;
	}

	/** True if the segment camera -> (ex, ey, ez) crosses no opaque voxel (camera voxel excluded, end voxel included). */
	private boolean ray(double ex, double ey, double ez) {
		rays++;
		double dx = ex - camX;
		double dy = ey - camY;
		double dz = ez - camZ;
		int x = camVoxelX;
		int y = camVoxelY;
		int z = camVoxelZ;
		int tx = floor(ex);
		int ty = floor(ey);
		int tz = floor(ez);
		int stepX = Integer.signum(tx - x);
		int stepY = Integer.signum(ty - y);
		int stepZ = Integer.signum(tz - z);
		// t runs from 0 (camera) to 1 (end point); tMax = t at the next voxel boundary on that axis.
		double tDeltaX = dx != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
		double tDeltaY = dy != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
		double tDeltaZ = dz != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;
		double tMaxX = stepX > 0 ? (x + 1 - camX) * tDeltaX : stepX < 0 ? (camX - x) * tDeltaX : Double.POSITIVE_INFINITY;
		double tMaxY = stepY > 0 ? (y + 1 - camY) * tDeltaY : stepY < 0 ? (camY - y) * tDeltaY : Double.POSITIVE_INFINITY;
		double tMaxZ = stepZ > 0 ? (z + 1 - camZ) * tDeltaZ : stepZ < 0 ? (camZ - z) * tDeltaZ : Double.POSITIVE_INFINITY;
		int steps = Math.abs(tx - x) + Math.abs(ty - y) + Math.abs(tz - z);
		while (steps-- > 0) {
			// Step the axis whose boundary comes first, never past the end voxel on any axis.
			boolean canX = x != tx;
			boolean canY = y != ty;
			boolean canZ = z != tz;
			if (canX && (!canY || tMaxX <= tMaxY) && (!canZ || tMaxX <= tMaxZ)) {
				x += stepX;
				tMaxX += tDeltaX;
			} else if (canY && (!canZ || tMaxY <= tMaxZ)) {
				y += stepY;
				tMaxY += tDeltaY;
			} else {
				z += stepZ;
				tMaxZ += tDeltaZ;
			}
			if (opaque(x, y, z)) {
				return false;
			}
		}
		return true;
	}

	private boolean opaque(int x, int y, int z) {
		int lx = x - originX;
		int ly = y - originY;
		int lz = z - originZ;
		if (lx >= 0 && ly >= 0 && lz >= 0 && lx < SIZE && ly < SIZE && lz < SIZE) {
			int index = (lx * SIZE + ly) * SIZE + lz;
			byte known = cache[index];
			if (known != 0) {
				return known == 2;
			}
			boolean o = lookup(x, y, z);
			cache[index] = o ? (byte) 2 : (byte) 1;
			return o;
		}
		return lookup(x, y, z);
	}

	private boolean lookup(int x, int y, int z) {
		if (y < minY || y > maxY) {
			return false;
		}
		int sx = x >> 4;
		int sy = y >> 4;
		int sz = z >> 4;
		if (!sectionKnown || sx != sectionX || sy != sectionY || sz != sectionZ) {
			sectionKnown = true;
			sectionX = sx;
			sectionY = sy;
			sectionZ = sz;
			section = null;
			LevelChunk chunk = chunks.getChunk(sx, sz, ChunkStatus.FULL, false);
			if (chunk != null) {
				LevelChunkSection[] sections = chunk.getSections();
				int i = sy - minSectionY;
				if (i >= 0 && i < sections.length) {
					LevelChunkSection s = sections[i];
					if (s != null && !s.hasOnlyAir()) {
						section = s;
					}
				}
			}
		}
		if (section == null) {
			return false;
		}
		BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
		return state.isSolidRender() && state.getRenderShape() != RenderShape.INVISIBLE;
	}

	private static int floor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}
}
