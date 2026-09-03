package dev.thedocruby.resounding.fixture;

import dev.thedocruby.resounding.raycast.Branch;
import dev.thedocruby.resounding.toolbox.ChunkChain;
import net.minecraft.util.shape.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal in-memory {@link ChunkChain} double for Phase 0 octree neighbor-accessor tests: no real
 * Minecraft chunk machinery, just section roots keyed by y-section index and a shared registry of
 * linked chains keyed by absolute chunk coordinates (mirrors {@code WorldChunkMixin.access}, which
 * takes absolute chunk x/z, not an offset).
 */
public final class FakeChunkChain implements ChunkChain {

    private final int chunkX;
    private final int chunkZ;
    private final Map<Long, ChunkChain> registry;
    private final Map<Integer, Branch> sections = new HashMap<>();
    private final Map<Long, VoxelShape> shapes = new HashMap<>();

    public int accessCalls = 0;
    public int getBranchCalls = 0;

    public FakeChunkChain(int chunkX, int chunkZ) {
        this(chunkX, chunkZ, new HashMap<>());
    }

    private FakeChunkChain(int chunkX, int chunkZ, Map<Long, ChunkChain> registry) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.registry = registry;
        registry.put(key(chunkX, chunkZ), this);
    }

    /** Creates a second chain sharing this one's registry, so {@link #access} can find it. */
    public FakeChunkChain neighborChunk(int otherChunkX, int otherChunkZ) {
        return new FakeChunkChain(otherChunkX, otherChunkZ, registry);
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    public void putSection(int ySection, Branch root) {
        sections.put(ySection, root);
    }

    @Override
    public Branch getBranch(int y) {
        getBranchCalls++;
        return sections.get(y);
    }

    @Override
    public @NotNull Map<Long, VoxelShape> getShapes() {
        return shapes;
    }

    @Override
    public void set(int index, Branch branch) {
        sections.put(index, branch);
    }

    @Override
    public ChunkChain set(int plane, ChunkChain negative, ChunkChain positive) {
        return this;
    }

    @Override
    public ChunkChain set(int plane, int index, ChunkChain link) {
        return this;
    }

    @Override
    public ChunkChain get(int plane, int index) {
        return null;
    }

    @Override
    public ChunkChain traverse(int d, int plane) {
        return this;
    }

    @Override
    public ChunkChain access_(int tx, int tz) {
        return this;
    }

    @Override
    public @Nullable ChunkChain access(int x, int z) {
        accessCalls++;
        return registry.get(key(x, z));
    }

    @Override
    public void initStorage() {}

    @Override
    public void replantOctrees() {}

    @Override
    public Branch layer(Branch root) {
        return root;
    }
}
