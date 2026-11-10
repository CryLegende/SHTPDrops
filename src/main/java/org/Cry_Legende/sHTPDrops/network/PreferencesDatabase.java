package org.Cry_Legende.sHTPDrops.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.UUID;

/**
 * Joueurs qui ont désactivé la téléportation des drops, en MariaDB, partagés par tous les serveurs : une ligne par joueur
 * désactivé. Chargée seulement avec SHNetwork (HikariCP et le pilote MariaDB viennent de {@code libraries:}
 * dans plugin.yml).
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal.</p>
 */
public final class PreferencesDatabase {

    private static final String TABLE = "shtpdrops_disabled";

    private final HikariDataSource pool;

    public PreferencesDatabase(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHTPDrops");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : le plugin reste alors en mode local
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Crée la table si elle manque. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    public void createTable() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + TABLE + " LIMIT 1")) {
                return;
            } catch (SQLException missing) {
                // la table n'existe pas encore : création ci-dessous
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "PRIMARY KEY (uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    /** Vrai si le joueur a désactivé la téléportation des drops. */
    public boolean isDisabled(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + TABLE + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Désactive (ligne ajoutée) ou réactive (ligne retirée) la téléportation des drops d'un joueur, sans toucher aux autres. */
    public void setDisabled(UUID uuid, boolean disabled) throws SQLException {
        String sql = disabled
            ? "INSERT IGNORE INTO " + TABLE + " (uuid) VALUES (?)"
            : "DELETE FROM " + TABLE + " WHERE uuid = ?";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** Ajoute les joueurs qui n'y sont pas déjà ; rend le nombre de lignes ajoutées. */
    public int importAll(Collection<UUID> players) throws SQLException {
        int added = 0;
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + TABLE + " (uuid) VALUES (?)")) {
            for (UUID uuid : players) {
                ps.setString(1, uuid.toString());
                added += ps.executeUpdate();
            }
        }
        return added;
    }

    public void close() {
        pool.close();
    }
}
