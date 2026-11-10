package org.Cry_Legende.sHTPDrops.network;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import org.Cry_Legende.sHTPDrops.SHTPDrops;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Mode réseau : les joueurs qui ont désactivé la téléportation des drops vivent dans la base partagée, une ligne
 * par joueur (la préférence « activé » est la valeur par défaut, elle n'est jamais écrite).
 *
 * <ul>
 *   <li>à l'arrivée d'un joueur : une fois le départ de l'autre serveur confirmé, sa préférence est lue en base ;
 *       d'ici là il est traité comme actif (valeur par défaut) ;</li>
 *   <li>à chaque changement : une seule ligne est ajoutée ou retirée, sur un fil à part (jamais le fil principal) ;</li>
 *   <li>au départ : la barrière de SHNetwork attend que ces écritures soient finies ;</li>
 *   <li>au départ aussi, le joueur sort de la mémoire : la base fait foi à son prochain passage.</li>
 * </ul>
 *
 * Le fichier preferences.json n'est plus lu ni écrit dans ce mode (sauf par la commande d'import).
 */
public final class NetworkPreferences implements Listener {

    private static final int WRITE_ATTEMPTS = 3;

    private final SHTPDrops plugin;
    /** Préférences en mémoire du plugin : seules les valeurs « false » (désactivé) y figurent. */
    private final Map<UUID, Boolean> memory;
    private final PreferencesDatabase db;
    private final NetworkLink link;
    private final Duration waitMax;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SHTPDrops-reseau");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lock = new Object();
    /** Lecture en cours de chaque joueur qui vient d'arriver (jeton de la lecture). */
    private final Map<UUID, Object> loading = new ConcurrentHashMap<>();
    /** Joueurs qui ont changé leur préférence pendant leur lecture : leur choix l'emporte sur la base. */
    private final Set<UUID> touched = ConcurrentHashMap.newKeySet();

    public NetworkPreferences(SHTPDrops plugin, Map<UUID, Boolean> memory, PreferencesDatabase db, NetworkLink link, Duration waitMax) {
        this.plugin = plugin;
        this.memory = memory;
        this.db = db;
        this.link = link;
        this.waitMax = waitMax;
    }

    /** Branche le mode réseau : écoute des connexions et sauvegarde au départ. */
    public void start() {
        link.registerSaveTask(player -> flush());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // les joueurs déjà là (rechargement à chaud) sont lus comme à une arrivée
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            beginLoad(online.getUniqueId());
        }
    }

    /** Termine les écritures en cours puis ferme la base. */
    public void stop() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Des écritures de préférences n'ont pas pu être terminées à l'arrêt.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }

    /** Change la préférence d'un joueur : vrai pour activer, faux pour désactiver. */
    public void set(UUID uuid, boolean enable) {
        synchronized (lock) {
            if (enable) {
                memory.remove(uuid);
            } else {
                memory.put(uuid, false);
            }
            if (loading.containsKey(uuid)) {
                touched.add(uuid);
            }
        }
        submit(() -> write(uuid, !enable));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        beginLoad(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        synchronized (lock) {
            loading.remove(uuid);
            touched.remove(uuid);
            memory.remove(uuid);
        }
    }

    private void beginLoad(UUID uuid) {
        Object token = new Object();
        synchronized (lock) {
            loading.put(uuid, token);
            touched.remove(uuid);
            memory.remove(uuid);
        }
        link.awaitSaved(uuid, waitMax).whenComplete((saved, error) -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                plugin.getLogger().warning("Départ de " + uuid + " non confirmé par l'autre serveur"
                    + (error != null ? " (" + error + ")" : "") + " : préférence relue quand même.");
            }
            submit(() -> load(uuid, token));
        });
    }

    private void load(UUID uuid, Object token) {
        boolean disabled;
        try {
            disabled = db.isDisabled(uuid);
        } catch (SQLException e) {
            // le joueur garde la valeur par défaut (activé) plutôt que de rester bloqué
            plugin.getLogger().log(Level.WARNING, "Préférence de " + uuid + " non relue en base : " + e.getMessage());
            synchronized (lock) {
                loading.remove(uuid, token);
                touched.remove(uuid);
            }
            return;
        }
        synchronized (lock) {
            if (loading.get(uuid) != token) {
                return; // parti ou revenu entre-temps
            }
            loading.remove(uuid);
            if (!touched.remove(uuid)) {
                if (disabled) {
                    memory.put(uuid, false);
                } else {
                    memory.remove(uuid);
                }
            }
        }
    }

    private void write(UUID uuid, boolean disabled) {
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                db.setDisabled(uuid, disabled);
                return;
            } catch (SQLException e) {
                if (attempt == WRITE_ATTEMPTS) {
                    plugin.getLogger().log(Level.WARNING, "Préférence de " + uuid + " non écrite en base : " + e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Reprend les joueurs désactivés de preferences.json dans la base, sans toucher à ceux qui y sont déjà.
     * Tourne hors du fil principal ; {@code done} reçoit (nombre ajouté, nombre déjà présent) ou l'erreur.
     */
    public void importFile(Path file, BiConsumer<int[], String> done) {
        submit(() -> {
            Set<UUID> players = new HashSet<>();
            try {
                if (Files.exists(file) && Files.size(file) > 0) {
                    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        Type type = new TypeToken<Map<UUID, Boolean>>() {
                        }.getType();
                        Map<UUID, Boolean> loaded = new Gson().fromJson(reader, type);
                        if (loaded != null) {
                            loaded.forEach((uuid, enabled) -> {
                                if (Boolean.FALSE.equals(enabled)) {
                                    players.add(uuid);
                                }
                            });
                        }
                    }
                }
                int added = db.importAll(players);
                done.accept(new int[]{added, players.size() - added}, null);
            } catch (IOException | JsonParseException | SQLException e) {
                done.accept(null, e.getMessage());
            }
        });
    }

    /** Futur terminé quand toutes les écritures déjà demandées sont faites (le fil d'écriture les traite dans l'ordre). */
    private CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            io.execute(() -> done.complete(null));
        } catch (RejectedExecutionException stopped) {
            done.complete(null);
        }
        return done;
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException stopped) {
            // plugin arrêté : plus rien à écrire
        }
    }
}
