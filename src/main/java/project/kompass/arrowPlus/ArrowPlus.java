package project.kompass.arrowPlus;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Illusioner;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Witch;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ArrowPlus extends JavaPlugin implements Listener {

    private ProtocolManager protocolManager;
    private final Map<UUID, Vector> customVelocities = new ConcurrentHashMap<>();

    private WatchService configWatcher;
    private long lastReloadTime = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        protocolManager = ProtocolLibrary.getProtocolManager();

        registerPacketInterceptor();
        startConfigFileListener();

        if (getCommand("arrowplus") != null) {
            getCommand("arrowplus").setExecutor(this);
        }

        getLogger().info("ArrowPlus activated for Paper 1.21.11!");
        getLogger().info("Live Config Listener is running. Use /arrowplus reload after modifying config.yml to update velocities.");
    }

    @Override
    public void onDisable() {
        if (configWatcher != null) {
            try {
                configWatcher.close();
            } catch (IOException e) {
                getLogger().warning("Failed to close config watcher safely.");
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("arrowplus")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("arrowplus.reload")) {
                    sender.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                reloadConfig();
                sender.sendMessage("§a[ArrowPlus] Configuration reloaded successfully!");
                getLogger().info("Config manually reloaded by " + sender.getName());
                return true;
            }
            sender.sendMessage("§cUsage: /arrowplus reload");
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;

        LivingEntity shooter = event.getEntity();
        String entityTypeName = shooter.getType().name();

        double multiplier = getConfig().getDouble("multipliers." + entityTypeName,
                getConfig().getDouble("multipliers.DEFAULT", 1.0));

        // Avoid division-by-zero or redundant calculations if multiplier is 1.0 or invalid
        if (multiplier <= 0.0 || multiplier == 1.0) return;

        Vector origVel = arrow.getVelocity();
        double speed = origVel.length() * multiplier;
        Vector newVelocity;

        // Adjust the vertical pitch for Mobs with active targets so their AI aims correctly
        if (shooter instanceof Mob mob && mob.getTarget() != null) {
            LivingEntity target = mob.getTarget();
            Location arrowLoc = arrow.getLocation();
            Location targetLoc = target.getLocation();

            double d0 = targetLoc.getX() - arrowLoc.getX();
            // 0.3333333333333333D of target height is the standard target offset used by Minecraft AI
            double d1 = (targetLoc.getY() + target.getHeight() * 0.3333333333333333D) - arrowLoc.getY();
            double d2 = targetLoc.getZ() - arrowLoc.getZ();
            double d3 = Math.sqrt(d0 * d0 + d2 * d2); // Horizontal distance

            // Gravity compensation factor scales inversely with the square of the multiplier
            double d1_adjusted = d1 + d3 * (0.2 / (multiplier * multiplier));

            // Create target direction vector
            Vector targetDir = new Vector(d0, d1_adjusted, d2).normalize();

            // Preserve horizontal direction/spread from the original arrow velocity
            Vector horizDir = new Vector(origVel.getX(), 0, origVel.getZ());
            if (horizDir.lengthSquared() > 0) {
                horizDir.normalize();
            } else {
                horizDir = new Vector(d0, 0, d2).normalize();
            }

            double horizLength = Math.sqrt(targetDir.getX() * targetDir.getX() + targetDir.getZ() * targetDir.getZ());

            Vector finalDir = new Vector(
                    horizDir.getX() * horizLength,
                    targetDir.getY(),
                    horizDir.getZ() * horizLength
            );

            newVelocity = finalDir.multiply(speed);
        } else {
            // Fallback to basic multiplication for players or targetless mobs
            newVelocity = origVel.clone().multiply(multiplier);
        }

        arrow.setVelocity(newVelocity);
        customVelocities.put(arrow.getUniqueId(), newVelocity);

        // Fallback cleanup
        Bukkit.getScheduler().runTaskLater(this, () -> customVelocities.remove(arrow.getUniqueId()), 100L);

        // Store the starting location of the arrow
        final Location[] lastLoc = { arrow.getLocation() };

        // Manually track and trace collisions with NoAI, unaware, or clipped mobs
        Bukkit.getScheduler().runTaskTimer(this, (task) -> {
            if (!customVelocities.containsKey(arrow.getUniqueId())) {
                task.cancel();
                return;
            }

            if (!arrow.isValid()) {
                task.cancel();
                customVelocities.remove(arrow.getUniqueId());
                return;
            }

            Location currentLoc = arrow.getLocation();
            Vector travel = currentLoc.toVector().subtract(lastLoc[0].toVector());
            double distance = travel.length();

            if (distance > 0.01) {
                RayTraceResult result = arrow.getWorld().rayTraceEntities(
                        lastLoc[0],
                        travel.clone().normalize(),
                        distance,
                        0.3,
                        entity -> !entity.getUniqueId().equals(shooter.getUniqueId())
                                && entity instanceof LivingEntity target
                                && !shouldPassThrough(shooter, target) // Ignore teamed pass-through mobs in manual raytrace
                                && !(target instanceof Player)
                                && !(target instanceof ArmorStand)
                                && !target.isDead()
                );

                if (result != null && result.getHitEntity() instanceof LivingEntity target) {
                    task.cancel();
                    customVelocities.remove(arrow.getUniqueId());

                    double speedVal = distance;
                    double damage = arrow.getDamage() * speedVal;
                    if (arrow.isCritical()) {
                        damage += Math.random() * (damage / 2.0) + 1.0;
                    }

                    target.damage(damage, shooter);

                    arrow.getWorld().playSound(result.getHitPosition().toLocation(arrow.getWorld()),
                            org.bukkit.Sound.ENTITY_ARROW_HIT, 1.0F, 1.2F);
                    arrow.remove();
                    return;
                }
            }

            if (arrow.isInBlock() || arrow.isOnGround()) {
                task.cancel();
                customVelocities.remove(arrow.getUniqueId());
                return;
            }

            lastLoc[0] = currentLoc;
        }, 1L, 1L);

        if (shooter instanceof Player player) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!arrow.isValid()) return;
                try {
                    PacketContainer packet = createVelocityPacket(arrow, newVelocity);
                    protocolManager.sendServerPacket(player, packet);
                } catch (Exception e) {
                    getLogger().warning("Failed to send velocity sync packet: " + e.getMessage());
                }
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof AbstractArrow arrow)) return;

        if (event.getHitEntity() != null && arrow.getShooter() instanceof Entity shooter) {
            Entity hitEntity = event.getHitEntity();

            if (shouldPassThrough(shooter, hitEntity)) {
                event.setCancelled(true);

                // Synchronize both absolute coordinates and velocity 1-tick later natively
                Bukkit.getScheduler().runTask(this, () -> {
                    if (arrow.isValid()) {
                        Vector velocity = arrow.getVelocity();
                        Location location = arrow.getLocation();

                        // 1. Teleporting the arrow forces Paper to automatically compile and
                        //    broadcast the correct version-specific teleport packet to all tracking clients.
                        //    This breaks the client-side arrow attachment prediction cleanly.
                        arrow.teleport(location);

                        // 2. Re-apply velocity so it continues its trajectory seamlessly on both server and client.
                        arrow.setVelocity(velocity);
                    }
                });
                return; // Do NOT remove custom velocity entry so physics task remains active
            }
        }

        // Standard collision -> clean up tracking maps
        customVelocities.remove(arrow.getUniqueId());
    }

    // --- HELPER PASS-THROUGH LOGIC ---

    private boolean shouldPassThrough(Entity shooter, Entity target) {
        if (shooter == null || target == null) return false;

        // Rule 1: Skeletons (including Bogged, Wither Skeletons, Strays, and Parched) pass-through each other
        if (shooter instanceof AbstractSkeleton && target instanceof AbstractSkeleton) {
            return true;
        }

        // Rule 2: Illusioner and Pillager projectiles pass-through all raider types, including the witch
        if (isIllusionerOrPillager(shooter) && isRaiderOrWitch(target)) {
            return true;
        }

        return false;
    }

    private boolean isIllusionerOrPillager(Entity entity) {
        return entity instanceof Illusioner || entity instanceof Pillager;
    }

    private boolean isRaiderOrWitch(Entity entity) {
        return entity instanceof Raider || entity instanceof Witch;
    }

    private void registerPacketInterceptor() {
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Server.ENTITY_VELOCITY) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                Entity entity = packet.getEntityModifier(event.getPlayer().getWorld()).readSafely(0);

                if (entity instanceof AbstractArrow arrow) {
                    if (customVelocities.containsKey(arrow.getUniqueId())) {
                        Vector correctVelocity = customVelocities.get(arrow.getUniqueId());

                        if (packet.getVectors().size() > 0) {
                            packet.getVectors().write(0, correctVelocity);
                        } else if (packet.getIntegers().size() >= 4) {
                            packet.getIntegers().write(1, (int) (correctVelocity.getX() * 8000.0));
                            packet.getIntegers().write(2, (int) (correctVelocity.getY() * 8000.0));
                            packet.getIntegers().write(3, (int) (correctVelocity.getZ() * 8000.0));
                        }
                    }
                }
            }
        });
    }

    private PacketContainer createVelocityPacket(Entity entity, Vector velocity) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_VELOCITY);
        packet.getIntegers().write(0, entity.getEntityId());

        if (packet.getVectors().size() > 0) {
            packet.getVectors().write(0, velocity);
        } else if (packet.getIntegers().size() >= 4) {
            packet.getIntegers().write(1, (int) (velocity.getX() * 8000.0));
            packet.getIntegers().write(2, (int) (velocity.getY() * 8000.0));
            packet.getIntegers().write(3, (int) (velocity.getZ() * 8000.0));
        }

        return packet;
    }

    // New helper method to construct an absolute coordinates packet for clients
    private PacketContainer createTeleportPacket(Entity entity, Location location) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
        packet.getIntegers().write(0, entity.getEntityId());

        packet.getDoubles().write(0, location.getX());
        packet.getDoubles().write(1, location.getY());
        packet.getDoubles().write(2, location.getZ());

        packet.getBytes().write(0, (byte) (location.getYaw() * 256.0F / 360.0F));
        packet.getBytes().write(1, (byte) (location.getPitch() * 256.0F / 360.0F));

        packet.getBooleans().write(0, entity.isOnGround());
        return packet;
    }

    private void startConfigFileListener() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                configWatcher = FileSystems.getDefault().newWatchService();
                Path pluginFolder = getDataFolder().toPath();

                pluginFolder.register(configWatcher, StandardWatchEventKinds.ENTRY_MODIFY);

                while (!Thread.currentThread().isInterrupted() && isEnabled()) {
                    WatchKey key = configWatcher.take();

                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changedFile = (Path) event.context();

                        if (changedFile.endsWith("config.yml")) {
                            long now = System.currentTimeMillis();
                            if (now - lastReloadTime > 1000) {
                                lastReloadTime = now;

                                Bukkit.getScheduler().runTask(this, () -> {
                                    reloadConfig();
                                    getLogger().info("Detected config.yml changes! Velocities live-updated.");
                                });
                            }
                        }
                    }
                    if (!key.reset()) break;
                }
            } catch (ClosedWatchServiceException | InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                getLogger().warning("Live config listener failed: " + e.getMessage());
            }
        });
    }
}