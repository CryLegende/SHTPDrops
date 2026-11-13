<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHTPDrops

*Envoie les drops de minage et les loots de mobs directement dans l'inventaire (/tpdrops).*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Envoie les drops de minage et les loots de mobs directement dans l'inventaire (/tpdrops).

Utilisé sur : **Opale**.

## Dépendances

- Optionnelle : `SHNetwork` (mode réseau)

## Mode réseau

Avec **SHNetwork** installé (et `reseau.actif: true`, valeur par défaut), plusieurs serveurs de jeu partagent les préférences des joueurs :

- les joueurs qui ont désactivé la téléportation des drops sont dans MariaDB (table `shtpdrops_disabled`, une ligne par joueur) ; `/tpdrops` et l'API (`setTpDropsForPlayer`) ajoutent ou retirent cette seule ligne, sur un fil à part ;
- au départ d'un joueur, SHNetwork attend que ces écritures soient finies ;
- à son arrivée sur l'autre serveur, sa préférence est relue en base une fois le départ confirmé ; d'ici là la téléportation est activée (valeur par défaut, jamais écrite en base) ;
- `preferences.json` n'est plus lu ni écrit : `/shtpdrops import` (permission `shtpdrops.admin.import`, console comprise) le reprend une fois dans la base, sans écraser ce qui y est déjà.

L'API (`SHTPDrops.getApi()`, y compris l'ancien paquet `com.shtpdrops`) lit la mémoire du serveur : elle répond pour les joueurs qui y sont connectés. Sans SHNetwork, ou sans les clés `reseau` dans la configuration, le plugin se comporte comme avant : préférences locales dans `preferences.json`. Si la base est injoignable, le joueur garde la téléportation activée. La table se crée toute seule au démarrage, ou à la main avec `sql/schema.sql`.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/tpdrops` | — | Active ou désactive le ramassage automatique des drops pour soi. | `shtpdrops.use` |
| `/shtpdrops import` | `/tpdrops import` | Mode réseau : reprend `preferences.json` dans la base partagée. | `shtpdrops.admin.import` |
| `/shtpdrops reload` | — | Relit `config.yml` et `messages.yml` (un fichier illisible ne change rien). | `shtpdrops.admin.reload` |
| `/shtpdrops info <joueur>` | — | Dit pourquoi le ramassage est actif ou non pour un joueur en ligne. | `shtpdrops.admin.info` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shtpdrops.use` | Utiliser `/tpdrops` et profiter du ramassage automatique. | `op` |
| `shtpdrops.admin` | Toutes les sous-commandes de `/shtpdrops`. | `op` |
| `shtpdrops.admin.import` | `/shtpdrops import`. | `op` |
| `shtpdrops.admin.reload` | `/shtpdrops reload`. | `op` |
| `shtpdrops.admin.info` | `/shtpdrops info`. | `op` |

## Configuration

Fichiers par défaut, avec des valeurs d'exemple. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHTPDrops/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
