package org.Cry_Legende.sHTPDrops;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import javax.annotation.Nullable;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SHTPDrops extends JavaPlugin implements Listener, CommandExecutor {
    // --- API Fields ---
    private static SHTPDrops instance;
    private SHTPDropsAPI api;

    // --- Core Fields ---
    private Set<Material> blacklist;
    private Set<String> worldBlacklist;
    private Map<UUID, Boolean> playerPreferences;
    private File preferencesFile;
    private final Gson gson = new Gson();
    private Map<UUID, Long> lastInventoryFullMessage = new HashMap<>();

    public static SHTPDropsAPI getApi() {
        return instance != null ? instance.api : null;
    }

    @Override
    public void onEnable() {
        instance = this;
        this.api = new SHTPDropsAPI(this);

        this.saveDefaultConfig();
        this.blacklist = loadBlacklist();
        this.worldBlacklist = new HashSet<>(getConfig().getStringList("worldBlacklist"));
        this.playerPreferences = new ConcurrentHashMap<>();
        setupPreferencesFile();
        loadPlayerPreferences();
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("tpdrops").setExecutor(this);
    }

    @Override
    public void onDisable() {
        // Le scheduler refuse les tâches d'un plugin en cours d'arrêt : écriture finale synchrone
        writePlayerPreferences(new HashMap<>(this.playerPreferences));
        instance = null;
    }

    private boolean hasPermission(Player player) {
        return player.hasPermission("shtpdrops.use");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Rien à faire ici
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Nettoyer le cache du message d'inventaire plein pour le joueur qui se déconnecte
        lastInventoryFullMessage.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof LivingEntity)) return;

        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();

        if (killer != null && !(entity instanceof Player) && shouldHandleLoot(killer)) {
            List<ItemStack> drops = new ArrayList<>(event.getDrops());
            event.getDrops().clear();
            for (ItemStack drop : drops) {
                if (!tryToAddToInventory(killer.getInventory(), drop)) {
                    killer.getWorld().dropItem(killer.getLocation(), drop);
                    sendInventoryFullMessage(killer);
                }
            }
        }
    }

    private boolean shouldHandleLoot(Player player) {
        return hasPermission(player) &&
            player.getGameMode() != GameMode.CREATIVE &&
            !worldBlacklist.contains(player.getWorld().getName()) &&
            playerPreferences.getOrDefault(player.getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        Material type = event.getBlock().getType();

        if (shouldCancelEvent(player, type)) return;

        if (event.getBlock().getState() instanceof Container) {
            Container container = (Container) event.getBlock().getState();
            container.getInventory().forEach(item -> {
                if (item != null) {
                    event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), item.clone());
                }
            });
            container.getInventory().clear();
            World world = player.getWorld();
            ItemStack dropStack = new ItemStack(type);
            if (!tryToAddToInventory(player.getInventory(), dropStack)) {
                world.dropItem(player.getLocation(), dropStack);
                sendInventoryFullMessage(player);
            }
            event.setCancelled(true);
            event.getBlock().setType(Material.AIR);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockDropItem(BlockDropItemEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        Material type = event.getBlockState().getType();

        if (shouldCancelEvent(player, type)) return;

        event.setCancelled(true);
        for (Item drop : event.getItems()) {
            ItemStack dropStack = drop.getItemStack();
            if (!tryToAddToInventory(player.getInventory(), dropStack)) {
                player.getWorld().dropItem(player.getLocation(), dropStack);
                sendInventoryFullMessage(player);
            } else {
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2F, 1.5F);
            }
        }
    }

    public boolean shouldCancelEvent(Player player, @Nullable Material type) {
        if (type != null && blacklist.contains(type)) {
            return true;
        }
        return !hasPermission(player) ||
            player.getGameMode() == GameMode.CREATIVE ||
            worldBlacklist.contains(player.getWorld().getName()) ||
            !playerPreferences.getOrDefault(player.getUniqueId(), true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("tpdrops") && sender instanceof Player) {
            Player player = (Player) sender;
            if (!hasPermission(player)) {
                Component noPermMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                    getConfig().getString("noPermissionMessage", "&cVous n'avez pas la permission d'utiliser cette commande."));
                player.sendMessage(noPermMessage);
                return true;
            }
            UUID playerId = player.getUniqueId();
            boolean isCurrentlyEnabled = playerPreferences.getOrDefault(playerId, true);

            // On inverse l'état actuel
            setPlayerPreference(playerId, !isCurrentlyEnabled);

            String messageKey = isCurrentlyEnabled ? "disabledMessage" : "enabledMessage";
            String rawMsg = getConfig().getString(messageKey, "TPDrops is now " + (!isCurrentlyEnabled ? "enabled" : "disabled") + ".");
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(rawMsg));
            return true;
        }
        return false;
    }

    private void setupPreferencesFile() {
        preferencesFile = new File(getDataFolder(), "preferences.json");
        if (!preferencesFile.exists()) {
            try {
                getDataFolder().mkdirs();
                preferencesFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create preferences.json: " + e.getMessage());
            }
        }
    }

    private void loadPlayerPreferences() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (preferencesFile.length() != 0) {
                        Reader reader = new FileReader(preferencesFile);
                        Type type = new TypeToken<Map<UUID, Boolean>>() {
                        }.getType();
                        Map<UUID, Boolean> loadedPrefs = gson.fromJson(reader, type);
                        if (loadedPrefs != null) {
                            playerPreferences = new ConcurrentHashMap<>(loadedPrefs);
                        } else {
                            playerPreferences = new ConcurrentHashMap<>();
                        }
                        reader.close();
                    } else {
                        playerPreferences = new ConcurrentHashMap<>();
                    }
                } catch (IOException e) {
                    getLogger().severe("Could not load preferences.json: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(this);
    }

    private void savePlayerPreferencesAsync() {
        Map<UUID, Boolean> prefsToSave = new HashMap<>(this.playerPreferences);
        new BukkitRunnable() {
            @Override
            public void run() {
                writePlayerPreferences(prefsToSave);
            }
        }.runTaskAsynchronously(this);
    }

    // synchronized : une sauvegarde async encore en cours ne doit pas croiser l'écriture de onDisable()
    private synchronized void writePlayerPreferences(Map<UUID, Boolean> prefsToSave) {
        try (Writer writer = new FileWriter(preferencesFile)) {
            gson.toJson(prefsToSave, writer);
        } catch (IOException e) {
            getLogger().warning("Could not save preferences.json: " + e.getMessage());
        }
    }

    private Set<Material> loadBlacklist() {
        return getConfig().getStringList("blacklist").stream()
            .map(Material::matchMaterial)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    private boolean tryToAddToInventory(PlayerInventory inventory, ItemStack drop) {
        HashMap<Integer, ItemStack> leftovers = inventory.addItem(drop);
        return leftovers.isEmpty();
    }

    public void setPlayerPreference(UUID playerUUID, boolean enable) {
        if (enable) {
            // Activer signifie retirer la préférence "false" (le défaut est true)
            playerPreferences.remove(playerUUID);
        } else {
            // Désactiver signifie mettre la préférence à "false"
            playerPreferences.put(playerUUID, false);
        }
        savePlayerPreferencesAsync();
    }

    private void sendInventoryFullMessage(Player player) {
        long now = System.currentTimeMillis();
        long lastSent = lastInventoryFullMessage.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastSent >= 15000) {
            lastInventoryFullMessage.put(player.getUniqueId(), now);
            String rawMsg = getConfig().getString("inventoryFullMessage", "&cTon inventaire est plein, l'objet a été déposé à tes pieds.");
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(rawMsg));
        }
    }

    // Getters for the API
    protected Set<Material> getBlacklist() {
        return blacklist;
    }

    protected Set<String> getWorldBlacklist() {
        return worldBlacklist;
    }

    protected Map<UUID, Boolean> getPlayerPreferences() {
        return playerPreferences;
    }
}
