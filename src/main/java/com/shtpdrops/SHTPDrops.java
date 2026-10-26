package com.shtpdrops;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Ancien point d'entrée de l'API (paquet d'avant la normalisation du 21/10/2026), gardé pour les plugins compilés
 * contre lui : EliteGenerator appelle com.shtpdrops.SHTPDrops.getApi() et ne démarre plus sans cette classe.
 */
public final class SHTPDrops {

    private static SHTPDropsAPI api;
    private static org.Cry_Legende.sHTPDrops.SHTPDrops owner;

    private SHTPDrops() {
    }

    public static synchronized SHTPDropsAPI getApi() {
        if (org.Cry_Legende.sHTPDrops.SHTPDrops.getApi() == null) {
            return null; // plugin désactivé
        }
        org.Cry_Legende.sHTPDrops.SHTPDrops plugin = JavaPlugin.getPlugin(org.Cry_Legende.sHTPDrops.SHTPDrops.class);
        // Nouvelle instance après un rechargement du plugin
        if (api == null || owner != plugin) {
            api = new SHTPDropsAPI(plugin);
            owner = plugin;
        }
        return api;
    }
}
