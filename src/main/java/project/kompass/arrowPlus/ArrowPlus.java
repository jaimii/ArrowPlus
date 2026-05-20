package project.kompass.arrowPlus;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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

        // Register the new base command
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

    // Reload Command
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("arrowplus")) {

            // Check if they provided an argument and if it is "reload"
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {

                if (!sender.hasPermission("arrowplus.reload")) {
                    sender.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }

                // Reload the configuration into memory
                reloadConfig();
                sender.sendMessage("§a[ArrowPlus] Configuration reloaded successfully!");
                getLogger().info("Config manually reloaded by " + sender.getName());
                return true;
            }

            // If they just typed "/arrowplus" or "/arrowplus somethingelse"
            sender.sendMessage("§cUsage: /arrowplus reload");
            return true;
        }
        return false;
    }

    // Velocity Handler
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;

        LivingEntity shooter = event.getEntity();
        String entityTypeName = shooter.getType().name();

        double multiplier = getConfig().getDouble("multipliers." + entityTypeName,
                getConfig().getDouble("multipliers.DEFAULT", 1.0));

        if (multiplier == 1.0) return;

        Vector newVelocity = arrow.getVelocity().clone().multiply(multiplier);
        arrow.setVelocity(newVelocity);

        customVelocities.put(arrow.getUniqueId(), newVelocity);

        // Fallback cleanup
        Bukkit.getScheduler().runTaskLater(this, () -> customVelocities.remove(arrow.getUniqueId()), 100L);

        // FIX 1: Manually track and trace collisions with NoAI mobs
        Bukkit.getScheduler().runTaskTimer(this, (task) -> {
            if (!arrow.isValid() || arrow.isInBlock() || arrow.isOnGround()) {
                task.cancel();
                customVelocities.remove(arrow.getUniqueId());
                return;
            }

            Vector vel = arrow.getVelocity();
            double speed = vel.length();
            if (speed < 0.1) return;

            RayTraceResult result = arrow.getWorld().rayTraceEntities(
                    arrow.getLocation(),
                    vel.clone().normalize(),
                    speed,
                    0.3,
                    entity -> entity != shooter && entity instanceof LivingEntity && !entity.isDead()
            );

            if (result != null && result.getHitEntity() instanceof LivingEntity target) {
                if (!target.hasAI()) { // Target is a NoAI Mob
                    task.cancel();
                    customVelocities.remove(arrow.getUniqueId());

                    // Replicate vanilla projectile damage formulation
                    double damage = arrow.getDamage() * speed;
                    if (arrow.isCritical()) {
                        damage += Math.random() * (damage / 2.0) + 1.0;
                    }

                    target.damage(damage, shooter);

                    // Replicate hits
                    arrow.getWorld().playSound(result.getHitPosition().toLocation(arrow.getWorld()),
                            org.bukkit.Sound.ENTITY_ARROW_HIT, 1.0F, 1.2F);
                    arrow.remove();
                }
            }
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

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof AbstractArrow arrow) {
            customVelocities.remove(arrow.getUniqueId());
        }
    }

    // Packet fixes through ProtocolLib to remove client side visual glitch.
    private void registerPacketInterceptor() {
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Server.ENTITY_VELOCITY) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                Entity entity = packet.getEntityModifier(event.getPlayer().getWorld()).readSafely(0);

                if (entity instanceof AbstractArrow arrow) {
                    if (customVelocities.containsKey(arrow.getUniqueId())) {
                        Vector correctVelocity = customVelocities.get(arrow.getUniqueId());

                        // FIX 2: Write directly to short/integer velocity indices (scaled by 8000)
                        packet.getIntegers().write(1, (int) (correctVelocity.getX() * 8000.0));
                        packet.getIntegers().write(2, (int) (correctVelocity.getY() * 8000.0));
                        packet.getIntegers().write(3, (int) (correctVelocity.getZ() * 8000.0));
                    }
                }
            }
        });
    }

    private PacketContainer createVelocityPacket(Entity entity, Vector velocity) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_VELOCITY);

        // Entity ID remains an Integer at index 0.
        packet.getIntegers().write(0, entity.getEntityId());

        // FIX 2: Write velocity coordinates as scaled integers (x8000) at indices 1, 2, and 3
        packet.getIntegers().write(1, (int) (velocity.getX() * 8000.0));
        packet.getIntegers().write(2, (int) (velocity.getY() * 8000.0));
        packet.getIntegers().write(3, (int) (velocity.getZ() * 8000.0));

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