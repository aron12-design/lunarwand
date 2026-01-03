
package hu.galaxy.lunarwand;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class SnakeProjectile {

    public static class Settings {
        public double maxDistance = 35.0;
        public double step = 0.45;
        public double hitRadius = 0.65;
        public int bodyPoints = 6;
        public double bodySpacing = 0.12;
        public double waveAmplitude = 0.45;
        public double waveFrequency = 0.65;

        public double baseDamage = 150.0;
        public double headshotMultiplier = 1.8;

        public Particle particle = Particle.END_ROD;
        public Color dustColor = Color.fromRGB(80,255,120);
        public float dustSize = 1.2f;

        public static Settings fromConfig(FileConfiguration c) {
            Settings s = new Settings();
            s.maxDistance = c.getDouble("projectile.max-distance", 35.0);
            s.step = c.getDouble("projectile.step", 0.45);
            s.hitRadius = c.getDouble("projectile.hit-radius", 0.65);
            s.bodyPoints = c.getInt("projectile.body-points", 6);
            s.bodySpacing = c.getDouble("projectile.body-spacing", 0.12);
            s.waveAmplitude = c.getDouble("projectile.wave.amplitude", 0.45);
            s.waveFrequency = c.getDouble("projectile.wave.frequency", 0.65);

            String p = c.getString("projectile.particle.type", "DUST");
            try { s.particle = Particle.valueOf(p.trim().toUpperCase()); } catch (Throwable ignored) { s.particle = Particle.END_ROD; }

            String rgb = c.getString("projectile.particle.rgb", "80,255,120");
            try {
                String[] parts = rgb.split(",");
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                s.dustColor = Color.fromRGB(r,g,b);
            } catch (Throwable ignored) {}

            s.dustSize = (float) c.getDouble("projectile.particle.size", 1.2);

            return s;
        }
    }

    public static void shoot(JavaPlugin plugin, Location start, Vector direction, Entity damager, Settings s) {
        final World world = start.getWorld();
        final Vector dir = direction.clone().normalize();

        // Two orthogonal vectors for wave
        Vector up = new Vector(0, 1, 0);
        Vector right = dir.clone().crossProduct(up);
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector upOrtho = right.clone().crossProduct(dir).normalize();

        final int maxTicks = (int) Math.ceil(s.maxDistance / s.step);
        final Particle.DustOptions dust = new Particle.DustOptions(s.dustColor, s.dustSize);

        new BukkitRunnable() {
            Location pos = start.clone();
            int t = 0;

            @Override
            public void run() {
                t++;
                if (t > maxTicks) { cancel(); return; }

                // advance forward
                pos.add(dir.clone().multiply(s.step));

                // stop on solid block
                if (pos.getBlock().getType().isSolid()) { cancel(); return; }

                // wave offset
                double phase = t * s.waveFrequency;
                double x = Math.sin(phase) * s.waveAmplitude;
                double y = Math.cos(phase * 0.8) * (s.waveAmplitude * 0.35);

                Location wavePos = pos.clone()
                        .add(right.clone().multiply(x))
                        .add(upOrtho.clone().multiply(y));

                // body particles
                for (int i = 0; i < Math.max(1, s.bodyPoints); i++) {
                    Location p = wavePos.clone().subtract(dir.clone().multiply(i * s.bodySpacing));
                    if (s.particle == Particle.DUST) {
                        world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust);
                    } else {
                        world.spawnParticle(s.particle, p, 1, 0, 0, 0, 0);
                    }
                }

                // hit check
                for (Entity e : world.getNearbyEntities(wavePos, s.hitRadius, s.hitRadius, s.hitRadius)) {
                    if (!(e instanceof LivingEntity target)) continue;
                    if (!target.isValid() || target.isDead()) continue;
                    if (damager != null && e.getUniqueId().equals(damager.getUniqueId())) continue;

                    // By default: no PVP. Remove this if you want players to be hittable.
                    if (target instanceof Player) continue;

                    double dmg = s.baseDamage;

                    // headshot: closer to eye than mid-body AND within small radius
                    Location head = target.getEyeLocation();
                    Location body = target.getLocation().add(0, target.getHeight() * 0.5, 0);

                    double dh = wavePos.distanceSquared(head);
                    double db = wavePos.distanceSquared(body);

                    boolean headshot = dh < db && dh < (0.55 * 0.55);
                    if (headshot) dmg *= s.headshotMultiplier;

                    target.damage(dmg, damager);

                    world.spawnParticle(Particle.CRIT, headshot ? head : body, headshot ? 18 : 8, 0.2, 0.2, 0.2, 0.1);
                    cancel();
                    return;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
