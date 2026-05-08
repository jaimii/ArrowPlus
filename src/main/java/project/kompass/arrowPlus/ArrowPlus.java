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
import org.bukkit.plugin.java.JavaPlugin;
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

        if (getCommand("arrowplusreload") != null) {
            getCommand("arrowplusreload").setExecutor(this);
        }

        getLogger().info("ArrowPlus activated for Paper 1.21.11!");
        getLogger().info("Live Config Listener is running. Use /arrowplusreload after modifying config.yml to update velocities.");
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

    //Reload Command
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("arrowplusreload")) {
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

        Bukkit.getScheduler().runTaskLater(this, () -> customVelocities.remove(arrow.getUniqueId()), 100L);

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
    // Packet fixes through ProtocolLib to remove client side visual glitch.
    private void registerPacketInterceptor() {
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Server.ENTITY_VELOCITY) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Entity entity = event.getPacket().getEntityModifier(event.getPlayer().getWorld()).readSafely(0);

                if (entity instanceof AbstractArrow arrow) {
                    if (customVelocities.containsKey(arrow.getUniqueId())) {
                        Vector correctVelocity = customVelocities.get(arrow.getUniqueId());

                        // Write the Bukkit Vector directly to the ProtocolLib packet.
                        event.getPacket().getVectors().write(0, correctVelocity);
                    }
                }
            }
        });
    }

    private PacketContainer createVelocityPacket(Entity entity, Vector velocity) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_VELOCITY);

        // Entity ID remains an Integer at index 0.
        packet.getIntegers().write(0, entity.getEntityId());

        // The unscaled movement velocity is written as a Vector object to index 0.
        packet.getVectors().write(0, velocity);

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