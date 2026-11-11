package org.Cry_Legende.sHTPDrops;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import org.Cry_Legende.sHTPDrops.network.NetworkLink;
import org.Cry_Legende.sHTPDrops.network.NetworkPreferences;
import org.Cry_Legende.sHTPDrops.network.PreferencesDatabase;
import org.Cry_Legende.sHTPDrops.network.ShNetworkLink;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class SHTPDrops extends JavaPlugin implements Listener, CommandExecutor {
    // --- API Fields ---
    private static SHTPDrops instance;
    private SHTPDropsAPI api;

    // --- Core Fields ---
    // Remplacés d'un bloc par /shtpdrops reload : lus par les threads des mondes et par l'API des autres plugins
    private volatile Set<Material> blacklist;
    private volatile Set<String> worldBlacklist;
    private final Map<UUID, Boolean> playerPreferences = new ConcurrentHashMap<>();
    private File preferencesFile;
    // Non nul seulement avec SHNetwork : les préférences vivent alors dans la base partagée
    private NetworkPreferences network;
    private volatile YamlConfiguration messages;
    // Section reseau de config.yml au démarrage : elle ne se relit pas à chaud
    private String networkSignature;
    private final Gson gson = new Gson();
    // Écrit depuis les threads des mondes (mort d'un mob) et du thread principal (déconnexion) : map concurrente
    private final Map<UUID, Long> lastInventoryFullMessage = new ConcurrentHashMap<>();

    public static SHTPDropsAPI getApi() {
        return instance != null ? instance.api : null;
    }

    @Override
    public void onEnable() {
        instance = this;
        this.api = new SHTPDropsAPI(this);

        this.saveDefaultConfig();
        this.blacklist = loadBlacklist();
        this.worldBlacklist = Set.copyOf(getConfig().getStringList("worldBlacklist"));
        networkSignature = networkSignature();
        setupPreferencesFile();
        migrateLegacyMessages();
        messages = loadMessages();
        enableNetwork();
        // Avec le mode réseau, la base fait foi : le fichier local n'est lu que par /tpdrops import
        if (network == null) {
            loadPlayerPreferences();
        }
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("tpdrops").setExecutor(this);
        AdminCommand admin = new AdminCommand(this);
        this.getCommand("shtpdrops").setExecutor(admin);
        this.getCommand("shtpdrops").setTabCompleter(admin);
    }

    @Override
    public void onDisable() {
        if (network != null) {
            network.stop();
        } else {
            // Le scheduler refuse les tâches d'un plugin en cours d'arrêt : écriture finale synchrone
            writePlayerPreferences(new HashMap<>(this.playerPreferences));
        }
        instance = null;
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont chargées qu'ici : sans lui, le plugin se comporte exactement comme avant.
     */
    private void enableNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        PreferencesDatabase db = null;
        try {
            NetworkLink link = ShNetworkLink.open();
            db = new PreferencesDatabase(
                getConfig().getString("reseau.base-de-donnees.url"),
                getConfig().getString("reseau.base-de-donnees.utilisateur"),
                getConfig().getString("reseau.base-de-donnees.mot-de-passe"),
                Math.max(250, getConfig().getInt("reseau.base-de-donnees.delai-ms", 3000)));
            db.createTable();
            Duration wait = Duration.ofSeconds(Math.max(1, getConfig().getInt("reseau.attente-max-secondes", 10)));
            NetworkPreferences net = new NetworkPreferences(this, playerPreferences, db, link, wait);
            net.start();
            network = net;
            getLogger().info("SHNetwork détecté : les préférences des joueurs sont partagées entre les serveurs (base partagée).");
        } catch (Exception | LinkageError e) {
            if (db != null) db.close();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, préférences locales à ce serveur : " + e);
        }
    }

    /** Textes qui étaient dans config.yml et sont maintenant dans messages.yml (mêmes noms de clés). */
    private static final List<String> LEGACY_MESSAGES = List.of(
        "enabledMessage", "disabledMessage", "noPermissionMessage", "inventoryFullMessage");

    /**
     * Reprend dans messages.yml les textes encore écrits dans config.yml, pour garder ceux du serveur : un messages.yml
     * créé maintenant reçoit tous les textes de config.yml, un messages.yml existant reçoit ceux qui lui manquent.
     * config.yml n'est plus lu ensuite pour ces textes.
     */
    private void migrateLegacyMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        boolean created = !file.exists();
        if (created) saveResource("messages.yml", false);
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : LEGACY_MESSAGES) {
                String text = getConfig().getString(key);
                if (text == null) continue;
                String line = key + ": \"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                int index = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith(key + ":")) index = i;
                }
                if (index < 0) {
                    lines.add(line);
                    changed = true;
                } else if (created) {
                    lines.set(index, line);
                    changed = true;
                }
            }
            if (changed) Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Textes de config.yml non repris dans messages.yml : " + e.getMessage());
        }
    }

    /** messages.yml, avec les valeurs du jar pour toute clé absente du fichier du serveur. */
    private YamlConfiguration loadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStreamReader defaults = new InputStreamReader(Objects.requireNonNull(getResource("messages.yml")), StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(defaults));
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "messages.yml de référence illisible : " + e.getMessage());
        }
        return loaded;
    }

    /** Texte de messages.yml, couleurs appliquées. */
    private Component getMessage(String key) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(rawMessage(key));
    }

    /** Texte de messages.yml sans couleurs appliquées ; chaque paire de {@code values} remplace {nom} par sa valeur. */
    String rawMessage(String key, String... values) {
        // getString(key) et non getString(key, défaut) : seul le premier reprend le texte livré avec le jar
        String text = messages.getString(key);
        if (text == null) text = key;
        for (int i = 0; i + 1 < values.length; i += 2) {
            text = text.replace("{" + values[i] + "}", values[i + 1]);
        }
        return text;
    }

    void sendMessage(CommandSender to, String key, String... values) {
        to.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(rawMessage(key, values)));
    }

    private String networkSignature() {
        return String.valueOf(getConfig().getConfigurationSection("reseau") == null
            ? "" : getConfig().getConfigurationSection("reseau").getValues(true));
    }

    /**
     * /shtpdrops reload : relit config.yml et messages.yml. Un fichier illisible ne change rien. preferences.json et la
     * base ne sont pas touchés ; la section reseau ne se relit qu'au démarrage.
     */
    void reload(CommandSender sender) {
        for (String name : List.of("config.yml", "messages.yml")) {
            File file = new File(getDataFolder(), name);
            if (!file.isFile()) continue;
            try {
                new YamlConfiguration().load(file);
            } catch (IOException | InvalidConfigurationException e) {
                String error = String.valueOf(e.getMessage()).replaceAll("\\s+", " ").trim();
                getLogger().warning(name + " illisible, configuration inchangée : " + error);
                sendMessage(sender, "reload-error", "file", name, "error", error);
                return;
            }
        }
        reloadConfig();
        blacklist = loadBlacklist();
        worldBlacklist = Set.copyOf(getConfig().getStringList("worldBlacklist"));
        messages = loadMessages();
        sendMessage(sender, "reload-done", "blocks", String.valueOf(blacklist.size()), "worlds", String.valueOf(worldBlacklist.size()));
        if (!networkSignature().equals(networkSignature)) {
            sendMessage(sender, "reload-restart");
        }
    }

    /** /shtpdrops info : pourquoi le ramassage automatique est actif ou non pour un joueur en ligne. */
    void showInfo(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sendMessage(sender, "player-not-found", "player", name);
            return;
        }
        String ok = rawMessage("info-ok");
        String blocked = rawMessage("info-blocked");
        boolean permission = hasPermission(target);
        boolean preference = playerPreferences.getOrDefault(target.getUniqueId(), true);
        boolean creative = target.getGameMode() == GameMode.CREATIVE;
        boolean worldBlocked = worldBlacklist.contains(target.getWorld().getName());
        sendMessage(sender, "info-header", "player", target.getName());
        sendMessage(sender, "info-permission", "value", permission ? ok : blocked);
        sendMessage(sender, "info-preference", "value", preference ? ok : blocked);
        sendMessage(sender, "info-gamemode", "value", creative ? blocked : ok);
        sendMessage(sender, "info-world", "world", target.getWorld().getName(), "value", worldBlocked ? blocked : ok);
        sendMessage(sender, "info-result", "value",
            rawMessage(permission && preference && !creative && !worldBlocked ? "info-active" : "info-inactive"));
    }

    /** /shtpdrops import (aussi /tpdrops import) : reprend preferences.json dans la base partagée (une fois, sans écraser ce qui y est déjà). */
    void importPreferences(CommandSender sender) {
        if (network == null) {
            sender.sendMessage(getMessage("reseau-inactif"));
            return;
        }
        network.importFile(preferencesFile.toPath(), (counts, error) -> {
            if (error != null) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    rawMessage("reseau-import-erreur").replace("%erreur%", error)));
            } else {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    rawMessage("reseau-import")
                        .replace("%ajoutes%", String.valueOf(counts[0]))
                        .replace("%deja%", String.valueOf(counts[1]))));
            }
        });
    }

    private boolean hasPermission(Player player) {
        return player.hasPermission("shtpdrops.use");
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

    /**
     * Le contenu d'un conteneur cassé tombe au sol, comme en vanilla ; le bloc lui-même suit la casse normale
     * et son objet arrive dans l'inventaire par {@link #onBlockDropItem}. La casse n'est pas annulée : les
     * autres plugins (niveau d'île, métiers, journaux) la voient. Une boîte de Shulker garde son contenu
     * dans son objet.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (shouldCancelEvent(player, block.getType())) return;

        // Un seul getState(false) : pas de copie de l'état du bloc pour chaque bloc cassé, conteneur ou non
        BlockState state = block.getState(false);
        if (!(state instanceof Container container) || state instanceof ShulkerBox) return;

        // Coffre double : seule la moitié cassée se vide
        Inventory inventory = state instanceof Chest chest ? chest.getBlockInventory() : container.getInventory();
        Location location = block.getLocation();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.isEmpty()) {
                block.getWorld().dropItemNaturally(location, item.clone());
            }
        }
        inventory.clear();
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
        // Ancien nom de /shtpdrops import (la console et ceux qui ont shtpdrops.admin.import)
        if (cmd.getName().equalsIgnoreCase("tpdrops") && args.length > 0 && args[0].equalsIgnoreCase("import")
            && AdminCommand.canUse(sender, "import")) {
            importPreferences(sender);
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("tpdrops") && sender instanceof Player) {
            Player player = (Player) sender;
            if (!hasPermission(player)) {
                player.sendMessage(getMessage("noPermissionMessage"));
                return true;
            }
            UUID playerId = player.getUniqueId();
            boolean isCurrentlyEnabled = playerPreferences.getOrDefault(playerId, true);

            // On inverse l'état actuel
            setPlayerPreference(playerId, !isCurrentlyEnabled);

            String messageKey = isCurrentlyEnabled ? "disabledMessage" : "enabledMessage";
            player.sendMessage(getMessage(messageKey));
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

    // Lecture synchrone au démarrage (fichier minuscule) : une lecture asynchrone pouvait croiser une première
    // sauvegarde et écraser les préférences déjà enregistrées
    private void loadPlayerPreferences() {
        if (preferencesFile.length() == 0) return;
        try (Reader reader = new FileReader(preferencesFile)) {
            Type type = new TypeToken<Map<UUID, Boolean>>() {
            }.getType();
            Map<UUID, Boolean> loadedPrefs = gson.fromJson(reader, type);
            if (loadedPrefs != null) {
                playerPreferences.putAll(loadedPrefs);
            }
        } catch (IOException | JsonParseException e) {
            getLogger().severe("Could not load preferences.json: " + e.getMessage());
            // La prochaine sauvegarde réécrira le fichier : on garde une copie du fichier illisible
            try {
                Files.copy(preferencesFile.toPath(), new File(getDataFolder(), "preferences.json.corrupt").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
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
        // Fichier temporaire puis remplacement : un arrêt en plein milieu ne tronque pas preferences.json
        File tmp = new File(preferencesFile.getParentFile(), preferencesFile.getName() + ".tmp");
        try (Writer writer = new FileWriter(tmp)) {
            gson.toJson(prefsToSave, writer);
        } catch (IOException e) {
            getLogger().warning("Could not save preferences.json: " + e.getMessage());
            return;
        }
        try {
            Files.move(tmp.toPath(), preferencesFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            getLogger().warning("Could not replace preferences.json: " + e.getMessage());
        }
    }

    private Set<Material> loadBlacklist() {
        return getConfig().getStringList("blacklist").stream()
            .map(Material::matchMaterial)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    }

    private boolean tryToAddToInventory(PlayerInventory inventory, ItemStack drop) {
        HashMap<Integer, ItemStack> leftovers = inventory.addItem(drop);
        return leftovers.isEmpty();
    }

    public void setPlayerPreference(UUID playerUUID, boolean enable) {
        if (network != null) {
            network.set(playerUUID, enable);
            return;
        }
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
            player.sendMessage(getMessage("inventoryFullMessage"));
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
