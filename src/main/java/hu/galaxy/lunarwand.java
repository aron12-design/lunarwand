
package hu.galaxy.lunarwand;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LunarWandPlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey wandKey;
    private NamespacedKey cloneKey;
    private NamespacedKey cloneItemKey;

    // Cooldowns
    private final Map<UUID, Long> shootCdUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cloneCdUntil = new ConcurrentHashMap<>();

    // Clones per player
    private final Map<UUID, Set<UUID>> clonesByOwner = new ConcurrentHashMap<>();

    // Config cache
    private Material wandMat;
    private int wandCmd;
    private String iaId;
    private String wandName;
    private List<String> wandLore;

    private double damage;
    private double headshotMult;
    private double shootCdMs;
    private double cloneCdMs;

    private int cloneMax;
    private int cloneLifetimeSec;
    private double cloneRange;
    private int cloneShootInterval;
    private boolean rotateEveryTick;
    private boolean allowTargetPlayers;
    private boolean allowTargetFriendly;

    private SnakeProjectile.Settings projectileSettings;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        wandKey = new NamespacedKey(this, "lunar_wand");
        cloneKey = new NamespacedKey(this, "lunar_clone");
        cloneItemKey = new NamespacedKey(this, "lunar_clone_item");

        reloadLocalConfig();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("lunarwand")).setExecutor(this);
        Objects.requireNonNull(getCommand("lunarwand")).setTabCompleter(this);

        // Cleanup task: remove dead/expired clones
        new BukkitRunnable() {
            @Override public void run() { cleanupInvalid(); }
        }.runTaskTimer(this, 100L, 100L);
    }

    private void reloadLocalConfig() {
        FileConfiguration c = getConfig();
        wandMat = Material.matchMaterial(c.getString("wand.material", "BLAZE_ROD"));
        if (wandMat == null) wandMat = Material.BLAZE_ROD;
        wandCmd = c.getInt("wand.custom-model-data", 777001);
        iaId = c.getString("wand.itemsadder-id", "");
        if (iaId == null) iaId = "";
        iaId = iaId.trim();

        wandName = c.getString("wand.name", "§d☾ Lunár Kígyó Pálca");
        wandLore = c.getStringList("wand.lore");

        damage = c.getDouble("combat.damage", 150.0);
        headshotMult = c.getDouble("combat.headshot-multiplier", 1.8);

        shootCdMs = (long)(c.getDouble("cooldowns.shoot-seconds", 1.0) * 1000.0);
        cloneCdMs = (long)(c.getDouble("cooldowns.clone-seconds", 2.0) * 1000.0);

        cloneMax = c.getInt("clone.max-per-player", 2);
        cloneLifetimeSec = c.getInt("clone.lifetime-seconds", 10);
        cloneRange = c.getDouble("clone.target-range", 22);
        cloneShootInterval = c.getInt("clone.shoot-interval-ticks", 10);
        rotateEveryTick = c.getBoolean("clone.rotate-every-tick", true);
        allowTargetPlayers = c.getBoolean("clone.allow-target-players", false);
        allowTargetFriendly = c.getBoolean("clone.allow-target-friendly", true);

        projectileSettings = SnakeProjectile.Settings.fromConfig(c);
        projectileSettings.baseDamage = damage;
        projectileSettings.headshotMultiplier = headshotMult;
    }

    // --- Commands ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Player only.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("give")) {
            p.getInventory().addItem(createWand());
            p.sendMessage("§aMegkaptad a pálcát.");
            return true;
        }
        p.sendMessage("§7Használat: §f/lunarwand give");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give");
        return List.of();
    }

    // --- Item creation/recognition ---
    private ItemStack createWand() {
        ItemStack it = new ItemStack(wandMat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(wandName);
            meta.setLore(wandLore);

            // CustomModelData (optional but helpful with RP)
            try { meta.setCustomModelData(wandCmd); } catch (Throwable ignored) {}

            // Hard tag so it always works even if lore/name change
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    private boolean isWand(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return false;
        if (it.getType() != wandMat) return false;

        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;

        Byte tag = meta.getPersistentDataContainer().get(wandKey, PersistentDataType.BYTE);
        if (tag != null && tag == (byte)1) return true;

        // Fallback: CMD match (useful if some plugins strip PDC)
        try {
            if (meta.hasCustomModelData() && meta.getCustomModelData() == wandCmd) return true;
        } catch (Throwable ignored) {}

        // Optional ItemsAdder support WITHOUT hard dependency (reflection).
        if (!iaId.isEmpty()) {
            String namespaced = tryGetItemsAdderNamespacedId(it);
            return iaId.equalsIgnoreCase(namespaced);
        }

        return false;
    }

    private String tryGetItemsAdderNamespacedId(ItemStack it) {
        try {
            // dev.lone.itemsadder.api.CustomStack
            Class<?> customStackClz = Class.forName("dev.lone.itemsadder.api.CustomStack");
            var byItemStack = customStackClz.getMethod("byItemStack", ItemStack.class);
            Object cs = byItemStack.invoke(null, it);
            if (cs == null) return "";
            var getNamespacedID = customStackClz.getMethod("getNamespacedID");
            Object id = getNamespacedID.invoke(cs);
            return id != null ? id.toString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    // --- Events ---
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack inHand = p.getInventory().getItemInMainHand();
        if (!isWand(inHand)) return;

        switch (e.getAction()) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> {
                if (onCooldown(shootCdUntil, p.getUniqueId(), shootCdMs)) return;
                SnakeProjectile.shoot(this, p.getEyeLocation(), p.getEyeLocation().getDirection(), p, projectileSettings);
            }
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                if (onCooldown(cloneCdUntil, p.getUniqueId(), cloneCdMs)) return;
                spawnClone(p, inHand);
            }
        }
    }

    private boolean onCooldown(Map<UUID, Long> map, UUID id, double cdMs) {
        long now = System.currentTimeMillis();
        Long until = map.get(id);
        if (until != null && until > now) return true;
        map.put(id, now + (long)cdMs);
        return false;
    }

    private void spawnClone(Player owner, ItemStack handItem) {
        Set<UUID> set = clonesByOwner.computeIfAbsent(owner.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        // cleanup dead
        set.removeIf(uuid -> {
            Entity ent = Bukkit.getEntity(uuid);
            return ent == null || !ent.isValid();
        });
        if (set.size() >= cloneMax) {
            owner.sendMessage("§cMár van " + cloneMax + " klónod.");
            return;
        }

        Location loc = owner.getLocation().clone();
        ArmorStand as = (ArmorStand) owner.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);

        as.setInvisible(false);
        as.setArms(true);
        as.setBasePlate(false);
        as.setGravity(false);
        as.setCollidable(false);
        as.setCanPickupItems(false);
        as.setInvulnerable(true);

        // Tag as clone
        as.getPersistentDataContainer().set(cloneKey, PersistentDataType.BYTE, (byte)1);

        // Visual: player head + armor
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        if (sm != null) {
            sm.setOwningPlayer(owner);
            head.setItemMeta(sm);
        }
        as.getEquipment().setHelmet(head);
        as.getEquipment().setChestplate(cloneSafeCopy(owner.getInventory().getChestplate()));
        as.getEquipment().setLeggings(cloneSafeCopy(owner.getInventory().getLeggings()));
        as.getEquipment().setBoots(cloneSafeCopy(owner.getInventory().getBoots()));

        // Hand: copy of the wand (display only, locked)
        ItemStack display = cloneSafeCopy(handItem);
        ItemMeta dm = display.getItemMeta();
        if (dm != null) {
            dm.getPersistentDataContainer().set(cloneItemKey, PersistentDataType.BYTE, (byte)1);
            display.setItemMeta(dm);
        }
        display.setAmount(1);
        as.getEquipment().setItemInMainHand(display);

        // Lock equipment (no dupe)
        try {
            as.addEquipmentLock(org.bukkit.inventory.EquipmentSlot.HAND, ArmorStand.LockType.REMOVING_OR_CHANGING);
            as.addEquipmentLock(org.bukkit.inventory.EquipmentSlot.HEAD, ArmorStand.LockType.REMOVING_OR_CHANGING);
            as.addEquipmentLock(org.bukkit.inventory.EquipmentSlot.CHEST, ArmorStand.LockType.REMOVING_OR_CHANGING);
            as.addEquipmentLock(org.bukkit.inventory.EquipmentSlot.LEGS, ArmorStand.LockType.REMOVING_OR_CHANGING);
            as.addEquipmentLock(org.bukkit.inventory.EquipmentSlot.FEET, ArmorStand.LockType.REMOVING_OR_CHANGING);
        } catch (Throwable ignored) {}

        set.add(as.getUniqueId());

        // Lifetime
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (as.isValid()) as.remove();
            set.remove(as.getUniqueId());
        }, cloneLifetimeSec * 20L);

        // AI loop: rotate + shoot
        startCloneTask(owner, as);

        owner.sendMessage("§dKlón lerakva.");
    }

    private ItemStack cloneSafeCopy(ItemStack it) {
        if (it == null) return null;
        ItemStack c = it.clone();
        c.setAmount(1);
        return c;
    }

    private void startCloneTask(Player owner, ArmorStand clone) {
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!clone.isValid() || owner == null || !owner.isOnline()) {
                    cancel();
                    return;
                }

                LivingEntity target = findTarget(owner, clone);
                if (target == null) return;

                // Rotate towards target (even if behind)
                if (rotateEveryTick) {
                    RotationUtil.faceEntity(clone, target);
                }

                // Shoot every N ticks
                tick++;
                if (tick % Math.max(1, cloneShootInterval) != 0) return;

                Location start = clone.getLocation().clone().add(0, 1.6, 0);
                Vector dir = target.getEyeLocation().toVector().subtract(start.toVector()).normalize();
                SnakeProjectile.shoot(LunarWandPlugin.this, start, dir, owner, projectileSettings);
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private LivingEntity findTarget(Player owner, ArmorStand clone) {
        World w = clone.getWorld();
        Location c = clone.getLocation();

        double best = Double.MAX_VALUE;
        LivingEntity bestEnt = null;

        for (Entity e : w.getNearbyEntities(c, cloneRange, cloneRange, cloneRange)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (!le.isValid() || le.isDead()) continue;

            // Avoid owner/players unless allowed
            if (e instanceof Player p) {
                if (!allowTargetPlayers) continue;
                if (p.getUniqueId().equals(owner.getUniqueId())) continue;
            }

            // Allow friendly = true means everything except clones/armorstands
            if (e instanceof ArmorStand) continue;
            if (isCloneEntity(e)) continue;

            // If friendly NOT allowed, restrict to hostile types
            if (!allowTargetFriendly) {
                // crude filter: skip common passives
                if (e instanceof Animals || e instanceof Villager || e instanceof Allay || e instanceof IronGolem || e instanceof Snowman) {
                    continue;
                }
            }

            double d = e.getLocation().distanceSquared(c);
            if (d < best) {
                best = d;
                bestEnt = le;
            }
        }
        return bestEnt;
    }

    private boolean isCloneEntity(Entity e) {
        if (!(e instanceof ArmorStand as)) return false;
        Byte b = as.getPersistentDataContainer().get(cloneKey, PersistentDataType.BYTE);
        return b != null && b == (byte)1;
    }

    @EventHandler
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent e) {
        if (isCloneEntity(e.getRightClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        // prevent messing with clone items via damage knockback tricks
        if (isCloneEntity(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // optional cleanup: remove clones when owner leaves
        UUID owner = e.getPlayer().getUniqueId();
        Set<UUID> set = clonesByOwner.remove(owner);
        if (set != null) {
            for (UUID id : set) {
                Entity ent = Bukkit.getEntity(id);
                if (ent != null && ent.isValid()) ent.remove();
            }
        }
    }

    private void cleanupInvalid() {
        for (var entry : clonesByOwner.entrySet()) {
            entry.getValue().removeIf(uuid -> {
                Entity ent = Bukkit.getEntity(uuid);
                return ent == null || !ent.isValid();
            });
        }
    }
}
