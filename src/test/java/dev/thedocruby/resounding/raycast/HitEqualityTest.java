package dev.thedocruby.resounding.raycast;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contract: {@link Hit} equality is value-based on position, length, and reflect so that
 * geometrically identical hits deduplicate in collections.
 */
class HitEqualityTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	@Test
	void valueEqualPositionsMakeHitsEqual() {
		Vec3d a = new Vec3d(1, 2, 3);
		Vec3d b = new Vec3d(1, 2, 3);

		Hit first = new Hit(a, 4.0, 0, 10.0, 2.0, 0.6, 0.5);
		Hit second = new Hit(b, 4.0, 0, 10.0, 2.0, 0.6, 0.5);

		assertEquals(first, second, "hits with value-equal positions must be equal");
	}

	@Test
	void valueEqualHitsDeduplicateInSet() {
		Set<Hit> hits = new HashSet<>();
		hits.add(new Hit(new Vec3d(1, 2, 3), 4.0, 0, 10.0, 2.0, 0.6, 0.5));
		hits.add(new Hit(new Vec3d(1, 2, 3), 4.0, 0, 10.0, 2.0, 0.6, 0.5));

		assertEquals(1, hits.size(), "value-equal hits must collapse to one entry in a Set");
	}
}
