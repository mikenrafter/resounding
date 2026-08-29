package dev.thedocruby.resounding.debug.math;

import java.util.function.Consumer;

/**
 * Enumerates the 12 edges of an axis-aligned box as {@link Segment3} instances.
 */
public final class BoxEdges {

	private BoxEdges() {}

	public static void forEach(
			double minX,
			double minY,
			double minZ,
			double maxX,
			double maxY,
			double maxZ,
			Consumer<Segment3> consumer
	) {
		consumer.accept(new Segment3(minX, minY, minZ, maxX, minY, minZ));
		consumer.accept(new Segment3(maxX, minY, minZ, maxX, minY, maxZ));
		consumer.accept(new Segment3(maxX, minY, maxZ, minX, minY, maxZ));
		consumer.accept(new Segment3(minX, minY, maxZ, minX, minY, minZ));

		consumer.accept(new Segment3(minX, maxY, minZ, maxX, maxY, minZ));
		consumer.accept(new Segment3(maxX, maxY, minZ, maxX, maxY, maxZ));
		consumer.accept(new Segment3(maxX, maxY, maxZ, minX, maxY, maxZ));
		consumer.accept(new Segment3(minX, maxY, maxZ, minX, maxY, minZ));

		consumer.accept(new Segment3(minX, minY, minZ, minX, maxY, minZ));
		consumer.accept(new Segment3(maxX, minY, minZ, maxX, maxY, minZ));
		consumer.accept(new Segment3(maxX, minY, maxZ, maxX, maxY, maxZ));
		consumer.accept(new Segment3(minX, minY, maxZ, minX, maxY, maxZ));
	}
}
