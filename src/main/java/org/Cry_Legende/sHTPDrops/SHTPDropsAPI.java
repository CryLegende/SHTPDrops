package org.Cry_Legende.sHTPDrops;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * L'API publique pour le plugin SHTPDrops.
 * Permet à d'autres plugins d'interagir avec les fonctionnalités de SHTPDrops.
 */
public class SHTPDropsAPI {

    private final SHTPDrops plugin;

    /**
     * Le constructeur de l'API n'est pas destiné à être utilisé publiquement.
     * Obtenez l'instance via SHTPDrops.getApi().
     *
     * @param plugin L'instance du plugin principal.
     */
    protected SHTPDropsAPI(SHTPDrops plugin) {
        this.plugin = plugin;
    }

    /**
     * Vérifie si la téléportation des drops est actuellement active pour un joueur.
     * Cette méthode prend en compte la permission, le gamemode, le monde et la préférence personnelle du joueur.
     *
     * @param player Le joueur à vérifier.
     * @return true si la téléportation des drops est active pour ce joueur, false sinon.
     */
    public boolean isTpDropsActive(Player player) {
        // La méthode shouldCancelEvent retourne true si l'on doit annuler,
        // donc nous retournons l'inverse pour savoir si la fonctionnalité est active.
        return !plugin.shouldCancelEvent(player, null);
    }

    /**
     * Vérifie si un joueur a personnellement activé ou désactivé la fonctionnalité via la commande /tpdrops.
     *
     * @param player Le joueur dont on veut vérifier la préférence.
     * @return true si le joueur a la fonctionnalité activée (par défaut), false s'il l'a désactivée.
     */
    public boolean hasPlayerToggledTpDropsOn(Player player) {
        return plugin.getPlayerPreferences().getOrDefault(player.getUniqueId(), true);
    }

    /**
     * Active ou désactive la téléportation des drops pour un joueur spécifique.
     * Cela a le même effet que si le joueur utilisait la commande /tpdrops.
     *
     * @param player Le joueur pour qui changer la préférence.
     * @param enable true pour activer, false pour désactiver.
     */
    public void setTpDropsForPlayer(Player player, boolean enable) {
        plugin.setPlayerPreference(player.getUniqueId(), enable);
    }

    /**
     * Vérifie si un matériau est dans la liste noire de SHTPDrops.
     *
     * @param material Le matériau à vérifier.
     * @return true si le matériau est dans la liste noire.
     */
    public boolean isMaterialBlacklisted(Material material) {
        return plugin.getBlacklist().contains(material);
    }

    /**
     * Vérifie si un monde est dans la liste noire de SHTPDrops.
     *
     * @param world Le monde à vérifier.
     * @return true si le monde est dans la liste noire.
     */
    public boolean isWorldBlacklisted(World world) {
        return plugin.getWorldBlacklist().contains(world.getName());
    }

    /**
     * Récupère une vue non modifiable de la liste des matériaux blacklistés.
     *
     * @return Un Set non modifiable des matériaux.
     */
    public Set<Material> getBlacklistedMaterials() {
        return Collections.unmodifiableSet(plugin.getBlacklist());
    }

    /**
     * Récupère une vue non modifiable de la liste des mondes blacklistés.
     *
     * @return Un Set non modifiable des noms de mondes.
     */
    public Set<String> getBlacklistedWorlds() {
        return Collections.unmodifiableSet(plugin.getWorldBlacklist());
    }
}
