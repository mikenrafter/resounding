package dev.thedocruby.resounding.raycast;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.shape.VoxelShape;

import static dev.thedocruby.resounding.OctreeManager.CUBE;
import static dev.thedocruby.resounding.OctreeManager.EMPTY;

/**
 * Resolves how far a ray travels through a block cell, using {@link VoxelShape#raycast} for
 * partial blocks (panes, stairs, etc.) instead of treating the whole 1³ cell as solid.
 *
 * <p>Empty cells, full cubes, and misses use {@link Mode#VOXEL} so the caller applies normal
 * octree stepping and full material interaction (air included). Partial solids (panes, stairs,
 * etc.) use {@link Mode#SHAPE}.
 */
final class ShapeTraversal {

	private static final double EPS = 1e-4;

	private ShapeTraversal() {}

	enum Mode {
		/** Standard octree / full-cube stepping through the whole cell. */
		VOXEL,
		/** Partial solid geometry resolved via shape raycast (entry/exit positions). */
		SHAPE
	}

	record Result(
			Mode mode,
			Step step,
			Step reflectStep,
			double permeationDistance,
			Vec3d transmitPosition,
			double reflectDistance,
			Vec3d reflectPosition
	) {
		static Result voxel() {
			return new Result(Mode.VOXEL, null, null, 0, null, 0, null);
		}

		static Result shape(
				Step step,
				Step reflectStep,
				double permeationDistance,
				Vec3d transmitPosition,
				double reflectDistance,
				Vec3d reflectPosition
		) {
			return new Result(Mode.SHAPE, step, reflectStep, permeationDistance, transmitPosition, reflectDistance, reflectPosition);
		}
	}

	static Result resolve(BlockPos origin, int size, Vec3d position, Vec3d vector, VoxelShape shape, ShapeStepper stepper) {
		Step cellStep = stepper.step(blockToVec(origin), size, position, vector);
		Vec3d transmitPosition = truncate(position.add(cellStep.step()));
		double cellReach = cellStep.step().length() + EPS;

		if (shape == null || shape.isEmpty() || shape == EMPTY) {
			return Result.voxel();
		}
		if (shape == CUBE) {
			return Result.voxel();
		}

		double lx = position.x - origin.getX();
		double ly = position.y - origin.getY();
		double lz = position.z - origin.getZ();
		boolean inside = containsPoint(shape, lx, ly, lz);

		Vec3d rayEnd = position.add(vector.multiply(cellReach));
		BlockHitResult hit = shape.raycast(position, rayEnd, origin);

		if (!inside && hit == null) {
			return Result.voxel();
		}

		if (inside) {
			return resolveFromInside(origin, position, vector, shape, cellStep, transmitPosition, cellReach, hit);
		}

		Vec3d entryPos = hit.getPos();
		double entryDist = position.distanceTo(entryPos);
		Step entryStep = new Step(vector.multiply(entryDist), hit.getSide().getVector());

		Vec3d afterEntry = nudgeAlongNormal(entryPos, hit.getSide().getVector());
		BlockHitResult exit = shape.raycast(afterEntry, rayEnd, origin);

		double solidDist;
		Vec3d exitPos;
		Step reflectStep;
		if (exit != null && exit.getPos().distanceTo(entryPos) > EPS) {
			exitPos = nudgeAlongNormal(exit.getPos(), exit.getSide().getVector());
			solidDist = entryPos.distanceTo(exit.getPos());
			reflectStep = new Step(vector.multiply(entryDist), exit.getSide().getVector());
		} else {
			solidDist = Math.max(0, cellReach - entryDist);
			exitPos = transmitPosition;
			reflectStep = entryStep;
		}

		return Result.shape(
				cellStep,
				reflectStep,
				solidDist,
				truncate(exitPos),
				entryDist,
				entryPos
		);
	}

	private static Result resolveFromInside(
			BlockPos origin,
			Vec3d position,
			Vec3d vector,
			VoxelShape shape,
			Step cellStep,
			Vec3d cellExit,
			double cellReach,
			BlockHitResult forwardHit
	) {
		Vec3d rayEnd = position.add(vector.multiply(cellReach));
		BlockHitResult exit = forwardHit != null && forwardHit.getPos().distanceTo(position) > EPS
				? forwardHit
				: shape.raycast(position, rayEnd, origin);

		if (exit == null) {
			return Result.shape(cellStep, cellStep, cellReach, cellExit, 0, position);
		}

		Vec3d exitPos = nudgeAlongNormal(exit.getPos(), exit.getSide().getVector());
		double solidDist = position.distanceTo(exit.getPos());
		Step reflectStep = new Step(vector.multiply(solidDist), exit.getSide().getVector());
		return Result.shape(
				cellStep,
				reflectStep,
				solidDist,
				truncate(exitPos),
				0,
				position
		);
	}

	private static Vec3d nudgeAlongNormal(Vec3d pos, Vec3i normal) {
		return pos.add(Vec3d.of(normal).multiply(EPS));
	}

	private static Vec3d blockToVec(BlockPos pos) {
		return new Vec3d(pos.getX(), pos.getY(), pos.getZ());
	}

	static Vec3d truncate(Vec3d position) {
		// Round (not floor/truncate) so a step that lands within float noise of a whole-number
		// voxel edge snaps exactly onto it. Cast.normalize()'s sign-of-vector octant pick only
		// diverges from the wrong answer when the coordinate is precisely integral; a truncating
		// cast systematically undershoots toward zero and leaves the ray a hair off the boundary,
		// silently defeating that sign check on the next cast.
		return new Vec3d(
				Math.round(position.x * 1e5) / 1e5,
				Math.round(position.y * 1e5) / 1e5,
				Math.round(position.z * 1e5) / 1e5
		);
	}

	static boolean isPartialSolid(VoxelShape shape) {
		return shape != null && !shape.isEmpty() && shape != EMPTY && shape != CUBE;
	}

	static boolean containsLocalPoint(VoxelShape shape, BlockPos origin, Vec3d position) {
		double lx = position.x - origin.getX();
		double ly = position.y - origin.getY();
		double lz = position.z - origin.getZ();
		return containsPoint(shape, lx, ly, lz);
	}

	private static boolean containsPoint(VoxelShape shape, double lx, double ly, double lz) {
		for (Box box : shape.getBoundingBoxes()) {
			if (box.contains(lx, ly, lz)) {
				return true;
			}
		}
		return false;
	}

	@FunctionalInterface
	interface ShapeStepper {
		Step step(Vec3d base, int size, Vec3d position, Vec3d vector);
	}
}
