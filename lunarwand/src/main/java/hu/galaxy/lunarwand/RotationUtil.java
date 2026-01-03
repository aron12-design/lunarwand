
package hu.galaxy.lunarwand;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

public class RotationUtil {
    public static void faceEntity(Entity from, LivingEntity to) {
        Location f = from.getLocation();
        Location t = to.getEyeLocation();

        Vector dir = t.toVector().subtract(f.toVector());
        double dx = dir.getX();
        double dy = dir.getY();
        double dz = dir.getZ();

        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double xz = Math.sqrt(dx*dx + dz*dz);
        double pitch = Math.toDegrees(-Math.atan2(dy, xz));

        Location newLoc = from.getLocation();
        newLoc.setYaw((float) yaw);
        newLoc.setPitch((float) pitch);
        from.teleport(newLoc);
    }
}
