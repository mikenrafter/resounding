package dev.thedocruby.resounding.debug.math;

/**
 * Plain-double 3D line segment for closest-distance queries (no Minecraft types).
 */
public record Segment3(double ax, double ay, double az, double bx, double by, double bz) {

    private static final double EPS = 1e-12;

    public double closestDistanceTo(Segment3 other) {
        double uX = bx - ax;
        double uY = by - ay;
        double uZ = bz - az;
        double vX = other.bx - other.ax;
        double vY = other.by - other.ay;
        double vZ = other.bz - other.az;
        double wX = ax - other.ax;
        double wY = ay - other.ay;
        double wZ = az - other.az;

        double a = dot(uX, uY, uZ, uX, uY, uZ);
        double b = dot(uX, uY, uZ, vX, vY, vZ);
        double c = dot(vX, vY, vZ, vX, vY, vZ);
        double d = dot(uX, uY, uZ, wX, wY, wZ);
        double e = dot(vX, vY, vZ, wX, wY, wZ);

        if (a <= EPS && c <= EPS) {
            return distance(ax, ay, az, other.ax, other.ay, other.az);
        }
        if (a <= EPS) {
            return other.closestDistanceToPoint(ax, ay, az);
        }
        if (c <= EPS) {
            return closestDistanceToPoint(other.ax, other.ay, other.az);
        }

        double denom = a * c - b * b;
        double sc;
        double sN;
        double sD = denom;
        double tc;
        double tN;
        double tD = denom;

        if (denom < EPS) {
            sN = 0.0;
            sD = 1.0;
            tN = e;
            tD = c;
        } else {
            sN = b * e - c * d;
            tN = a * e - b * d;
            if (sN < 0.0) {
                sN = 0.0;
                tN = e;
                tD = c;
            } else if (sN > sD) {
                sN = sD;
                tN = e + b;
                tD = c;
            }
        }

        if (tN < 0.0) {
            tN = 0.0;
            if (-d < 0.0) {
                sN = 0.0;
            } else if (-d > a) {
                sN = sD;
            } else {
                sN = -d;
                sD = a;
            }
        } else if (tN > tD) {
            tN = tD;
            if (-d + b < 0.0) {
                sN = 0.0;
            } else if (-d + b > a) {
                sN = sD;
            } else {
                sN = -d + b;
                sD = a;
            }
        }

        sc = Math.abs(sN) < EPS ? 0.0 : sN / sD;
        tc = Math.abs(tN) < EPS ? 0.0 : tN / tD;

        double dX = wX + sc * uX - tc * vX;
        double dY = wY + sc * uY - tc * vY;
        double dZ = wZ + sc * uZ - tc * vZ;
        return Math.sqrt(dX * dX + dY * dY + dZ * dZ);
    }

    public double closestDistanceToPoint(double x, double y, double z) {
        double uX = bx - ax;
        double uY = by - ay;
        double uZ = bz - az;
        double lenSq = dot(uX, uY, uZ, uX, uY, uZ);
        if (lenSq <= EPS) {
            return distance(ax, ay, az, x, y, z);
        }

        double t = dot(x - ax, y - ay, z - az, uX, uY, uZ) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));

        double closestX = ax + t * uX;
        double closestY = ay + t * uY;
        double closestZ = az + t * uZ;
        return distance(closestX, closestY, closestZ, x, y, z);
    }

    private static double dot(double ax, double ay, double az, double bx, double by, double bz) {
        return ax * bx + ay * by + az * bz;
    }

    private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
