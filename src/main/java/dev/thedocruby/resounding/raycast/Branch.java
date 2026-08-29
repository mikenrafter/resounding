package dev.thedocruby.resounding.raycast;

import dev.thedocruby.resounding.OctreeManager;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.toolbox.MaterialData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

@Environment(EnvType.CLIENT)
public class Branch {
    public BlockPos start;
    public int size;
    public @NotNull VoxelShape shape = OctreeManager.CUBE;
    public @Nullable Material material; // TODO: use!
    public @Nullable String materialLabel;

    public @NotNull HashMap<Long, Branch> leaves;


    public Branch(BlockPos start, int size) {
        this.leaves = new HashMap<>(size < 4 ? 0 : 8, 2 /* should never be reached */);
        this.start = start;
        this.size = size;
    }

    public Branch(BlockPos start, int size, @NotNull VoxelShape shape) {
        this(start, size);
        set(shape);
    }

    public Branch(BlockPos start, int size, @Nullable Material material) {
        this(start, size);
        set(material);
    }

    public Branch(BlockPos start, int size, @Nullable VoxelShape shape, @Nullable Material material) {
        this(start, size);
        set(shape);
        set(material);
    }

    public Branch set(@Nullable VoxelShape shape) {
        this.shape = shape;
        return this;
    }

    public Branch set(@Nullable Material material) {
        this.material = material;
        return this;
    }

    public Branch set(int size) {
        this.size = size;
        return this;
    }

    public @NotNull Branch get(BlockPos pos) {
        if (leaves.isEmpty()) return this;
        int half = size >> 1;
        if (half == 0) return this;
        int dx = pos.getX() >= start.getX() + half ? half : 0;
        int dy = pos.getY() >= start.getY() + half ? half : 0;
        int dz = pos.getZ() >= start.getZ() + half ? half : 0;
        BlockPos childOrigin = start.add(dx, dy, dz);
        @Nullable Branch leaf = leaves.get(childOrigin.asLong());
        return leaf == null ? this : leaf.get(pos);
    }

    // recursively search tree for corresponding branch
    // positions are normalized by section (16³)
    @Deprecated
    public @NotNull Branch get(BlockPos pos, int layer) {
        return get(pos);
    }

//    private static BlockPos sub(BlockPos pos, BlockPos octo, int n) {
//        return pos.subtract(octo.multiply(n));
//    }

    // this should only be used
    @Deprecated // NOT REALLY, but @Unsafe isn't available... :/
    public Branch put(Long pos, Branch branch) { return leaves.put(pos, branch); }

    public Branch empty() {
        leaves.clear();
        return this;
    }

    public boolean isEmpty() {
        return leaves.isEmpty();
    }

    public Branch replace(Long pos, Branch branch) { return leaves.replace(pos, branch); }
}
