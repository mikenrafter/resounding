package dev.thedocruby.resounding;

import dev.thedocruby.resounding.material.Acoustics;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public class Physics {

    @Contract("_, _ -> new")
    public static @NotNull Vec3d pseudoReflect(Vec3d ray, @NotNull Vec3i plane) { return pseudoReflect(ray,plane,2); }

    @Contract("_, _, _ -> new")
    public static @NotNull Vec3d pseudoReflect(Vec3d ray, @NotNull Vec3i plane, double fresnel) {
        // Fresnels on a 1-30 scale
        // TODO account for http://hyperphysics.phy-astr.gsu.edu/hbase/Tables/indrf.html

        // `plane` is always exactly one of the 6 axis-unit directions (never a general/oblique
        // normal), so the full ray - 2*dot(ray,n)*n mirror formula collapses to a per-axis check:
        // scale the component on the crossed axis by (1 - fresnel), leave the other two alone.
        // fresnel=2 is a full mirror reflection; smaller values blend in the refraction approximation.
        return new Vec3d(
                plane.getX() != 0 ? ray.x * (1 - fresnel) : ray.x,
                plane.getY() != 0 ? ray.y * (1 - fresnel) : ray.y,
                plane.getZ() != 0 ? ray.z * (1 - fresnel) : ray.z
        );
    }

    /** @see Acoustics#reflection - delegated so the formula has exactly one definition */
    public static @NotNull Double reflection(@NotNull Double impedanceA, @NotNull Double impedanceB) {
        return Acoustics.reflection(impedanceA, impedanceB);
    }
}
