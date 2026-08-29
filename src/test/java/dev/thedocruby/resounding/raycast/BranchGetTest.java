package dev.thedocruby.resounding.raycast;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BranchGetTest {

	@Test
	void getDescendsIntoChildUsingAbsoluteChildOriginKeys() {
		BlockPos rootOrigin = new BlockPos(0, 0, 0);
		Branch root = new Branch(rootOrigin, 16);
		Branch eastChild = new Branch(new BlockPos(8, 0, 0), 8);
		root.put(new BlockPos(8, 0, 0).asLong(), eastChild);

		Branch found = root.get(new BlockPos(10, 4, 4));

		assertSame(eastChild, found);
	}

	@Test
	void getReturnsSelfWhenNoChildCoversPosition() {
		Branch root = new Branch(new BlockPos(0, 0, 0), 16);
		Branch found = root.get(new BlockPos(4, 4, 4));
		assertEquals(16, found.size);
	}
}
