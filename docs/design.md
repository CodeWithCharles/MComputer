# Projet : Mod Minecraft inspiré d'OpenComputers

## Contexte

Envie de coder un mod Minecraft pour passer le temps / apprendre, inspiré du mod culte **OpenComputers**. Après exploration des repos existants (OpenComputers original, forks OC2R, portages 1.20.1), constat que tous les projets de portage repérés sont inactifs. Décision : **repartir de zéro** en s'inspirant du concept plutôt que de porter/forker le code existant (Scala, architecture datée).

## Décision clé : repartir de zéro

**Avantages retenus :**
- Code en Java, stack moderne, 100% maîtrisée
- Choix d'architecture propres dès le départ
- Scope contrôlable (pas obligé de tout réimplémenter)
- Plus motivant sur la durée qu'un débogage de code tiers

**Ce qu'on garde du concept original (MIT, inspiration libre, pas de copie de code) :**
- Un bloc "Computer" modulaire avec des composants qu'on insère/retire pour activer des fonctionnalités
- L'idée centrale : le computer boot une sorte de "mini OS" scriptable en Lua

**Ce qu'on jette :**
- Robots / drones autonomes et tout ce qui touche au mouvement/pathfinding physique
- Réseau de câbles/composants distribués dans le monde (tout reste dans le bloc)
- Écrans 3D, hologrammes, imprimante 3D, intégrations avec d'autres mods — tout ça, plus tard si le projet vit

## Scope du MVP

### Le bloc Computer
- Bloc placé dans le monde avec un inventaire interne pour les composants
- État on/off, gère la séquence de boot

### Composants modulaires (cartes insérées dans le computer)
- CPU (vitesse d'exécution / tier)
- RAM (limite mémoire du script Lua)
- Disque dur / disquette (stockage persistant, filesystem simple)
- Carte graphique + écran (bloc lié, affichage texte/pixels)
- Carte réseau → pas MVP, plus tard

### Le "mini Linux"
- VM Lua sandboxée côté serveur (probablement LuaJ en Java pour le MVP, plus simple à intégrer qu'un binding natif type Eris)
- OS minimal scripté en Lua (façon OpenOS) : shell basique, filesystem virtuel, commandes de base (`ls`, `edit`, `run`)
- Persistance : état repris là où il s'est arrêté au rechargement du chunk

### Étapes de développement suggérées
1. Bloc Computer qui boot (on/off, écran de boot statique, pas de composants)
2. VM Lua intégrée — exécuter un script hardcodé, output dans les logs serveur
3. Écran + terminal basique — rendu texte in-game, le script Lua peut écrire dessus
4. Composants modulaires réels — inventaire du computer, CPU/RAM qui influencent la VM
5. Système de fichiers persistant — sauvegarde/chargement, shell minimal en Lua

*Arriver à l'étape 3 donne déjà un résultat jouable et satisfaisant.*

## Persistance du filesystem

**Comment fait l'original (référence) :** OpenComputers utilise un système "SaveHandler" qui stocke l'état de la machine dans des **fichiers externes** (pas directement dans le NBT du bloc) pour éviter les limitations de taille du NBT, organisés hiérarchiquement par dimension/chunk.

**Approche retenue pour le MVP :**
- Le NBT du bloc/tile entity garde seulement des métadonnées légères (UUID du "disque", état on/off, composants insérés)
- Le contenu réel du filesystem (fichiers, scripts Lua) est stocké à part, dans un fichier séparé du dossier de sauvegarde du monde (ex: `world/data/tonmod/disks/<uuid>.dat`)
- Chargement lazy : le fichier n'est lu que quand la VM a besoin d'accéder au FS, pas à chaque tick
- Pas besoin d'un vrai filesystem avec blocs/inodes pour le MVP — un simple dictionnaire chemin → contenu suffit

## Stack technique retenue

| Élément | Choix |
|---|---|
| Version Minecraft | 26.x (nouvelle numérotation Mojang, ex-1.21.x) |
| Loader | Fabric (Loader + API) — plus léger que NeoForge, meilleur pour itérer vite en solo |
| Build system | Gradle + Fabric Loom (setup standard) |
| Multi-version | StoneCutter (permet de cibler plusieurs versions MC dans une seule codebase, pensé pour les projets Loom/Fabric en Java) |
| Java | JDK 25 (recommandé par Fabric pour la branche 26.1+) |
| Multi-loader (Architectury) | **Non retenu** pour l'instant — complexité inutile pour un MVP solo. À reconsidérer plus tard si besoin de NeoForge en plus |

## Pistes explorées et écartées

- **MightyPirates/OpenComputers** (original) — Scala, pas de plan de portage officiel vers versions récentes
- **North-Western-Development/oc2r** — fork actif d'OpenComputers II (VM RISC-V/Linux, pas le concept Lua sandboxé original) — écarté car ce n'est pas la même approche que l'OC classique
- **SirDavidLudwig/OpenComputers-Reimagined** — tentative de réécriture Architectury du mod original — inactif
- **TheRealM18 / North-Western-Development — OpenComputers-1.20.1-port** — portage direct de l'original vers 1.20.1 — inactif

## Prochaines étapes possibles
- Setup du squelette de projet (structure Gradle, `fabric.mod.json`, config StoneCutter)
- Premier bloc Computer basique (étape 1 du plan)
- Design plus détaillé de la VM Lua et de l'intégration LuaJ
