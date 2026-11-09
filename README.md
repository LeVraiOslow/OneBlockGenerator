# ObGenerator

Générateurs tycoon pour Paper et Folia 26.2 : Moteur + ressources, déverrouillage, production, 10 niveaux.

## Installation

Dépose `ObGenerator-1.0.0.jar` dans `plugins/`, démarre le serveur, puis règle `plugins/ObGenerator/`.
Vérifie en premier `engine.item` dans `config.yml` : il doit pointer vers l'identifiant Nexo de ton Moteur.

| Fichier | Rôle |
|---|---|
| `config.yml` | Moteur, limites, stockage, hologrammes, formats, sons |
| `messages.yml` | Textes |
| `menus.yml` | Menus et items (`material: nexo:mon_item` accepté partout) |
| `profiles.yml` | Les 10 niveaux : production, exigences, durées d'évolution |
| `generators/*.yml` | Les générateurs ; la doc des options est en tête de `blocs.yml` |

## Commandes (`obgenerator.admin`)

`/generateur` (alias `/generator`, `/gen`, `/obgen`) : `give`, `moteur`, `list`, `info`, `setlevel`, `reload`.

## Placeholders

`%obgenerator_placed%`, `%obgenerator_unlocked%`, `%obgenerator_levels%`, `%obgenerator_max_level_count%`.

## Compiler

JDK 25, puis `gradle build`. Vault, SuperiorSkyblock2 et PlaceholderAPI sont téléchargés automatiquement via Maven (aucun jar à copier). Le jar est généré dans `build/libs/`.
