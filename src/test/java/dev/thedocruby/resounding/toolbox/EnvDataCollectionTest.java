package dev.thedocruby.resounding.toolbox;

import dev.thedocruby.resounding.raycast.Hit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvDataCollectionTest {

	@Test
	void listPreservesDistinctEmptyRayResults() {
		List<LinkedList<Hit>> rays = new ArrayList<>();
		rays.add(new LinkedList<>());
		rays.add(new LinkedList<>());
		assertEquals(2, rays.size());
	}

	@Test
	void setCollapsesDistinctEmptyRayResults() {
		Set<LinkedList<Hit>> rays = new HashSet<>();
		rays.add(new LinkedList<>());
		rays.add(new LinkedList<>());
		assertEquals(1, rays.size(), "Set dedup is why evalEnv must not use Collectors.toSet()");
	}
}
