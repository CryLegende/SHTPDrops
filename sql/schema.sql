-- Table de SHTPDrops avec SHNetwork. Le plugin la crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à la créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

CREATE TABLE IF NOT EXISTS bo_net.shtpdrops_disabled (
    uuid CHAR(36) NOT NULL,                      -- uuid « hors ligne » du joueur qui a désactivé la téléportation des drops, le même sur tous les serveurs
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shtpdrops'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.shtpdrops_disabled TO 'shtpdrops'@'127.0.0.1';
