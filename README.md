# vorace-mob

Des **mobs qui vivent**, pour serveurs Paper (Minecraft 1.21.4 à 26.x) : le rôle de MythicMobs, gratuit et libre.
Fait partie de [VoracePlugin](https://github.com/VoracePlugin) et a besoin de
[vorace-core](https://github.com/VoracePlugin/vorace-core) (plugin `Vorace`), qui affiche les modèles animés.

> **Code écrit par une IA.** Comme vorace-core, vorace-mob est écrit par Claude (l'IA d'Anthropic) ; ORA et Antoine
> testent et décident de ce qui est ajouté.

Les mobs étaient dans Vorace 1.1 ; ils en sont sortis pour que vorace-core reste léger (voir [CHANGELOG.md](CHANGELOG.md)).

## Ce que ça fait

Des mobs qui vivent : chacun a des **besoins** (faim, soif, fatigue) qui montent avec le temps, des **émotions**
(peur, colère) et choisit tout seul quoi faire : se reposer, errer, manger, boire, dormir, rejoindre son troupeau,
rentrer chez lui, fuir ou attaquer. Chaque action reçoit une note selon la situation et la meilleure l'emporte
(une action commencée est menée à son terme, la peur passe avant la faim, la nuit on dort...).

```yaml
# plugins/VoraceMob/mobs/ferme.yml
mouton:
  type: sheep                    # entité du jeu qui porte le mob (hitbox, sons, butin)
  modele: [ferme/mouton, ferme/mouton_noir]   # modèles animés, un au hasard (rien = apparence du jeu)
  taille: [0.9, 1.1]
  caractere: paisible            # paisible, craintif, territorial ou agressif
  activite: diurne               # diurne, nocturne ou toujours
  troupeau: { cohesion: 6 }      # reste à moins de 6 blocs du centre de son troupeau
  laisse: 24                     # jamais à plus de 24 blocs de sa maison
  zone: paturage                 # et jamais hors de cette zone (zones/*.yml)
  nourriture: [grass_block, short_grass]      # blocs ou plantes qu'il va brouter ; « zone » = points de la zone
  animations: { manger: graze, dormir: sleep, alerte: bleat, ennui: [groom, stretch] }
```

- **Caractères** : un mob *paisible* ne fuit que si on le frappe ou qu'on lui fonce dessus ; un *craintif* fuit
  les joueurs qui approchent (deux fois moins loin s'ils sont accroupis) ; un *territorial* prévient, puis attaque
  si on reste trop près ou si on le frappe ; un *agressif* attaque dès qu'il remarque un joueur.
- **Troupeaux** : les membres restent groupés, et quand l'un prend peur tout le troupeau s'enfuit.
- **Maison et laisse** : un mob ne s'éloigne jamais de sa maison au-delà de sa laisse, même en fuyant (il part
  alors de biais). Avec une **zone** (rectangle, cercle ou polygone dans `zones/*.yml`), il n'en sort pas non plus.
- **Points d'intérêt** des zones : `nourriture` (mangeoire), `eau` (abreuvoir), `abri` (où dormir la nuit).
  Sinon le mob cherche lui-même l'herbe à brouter et une rive où boire autour de lui.
- **Animations** : les rôles `manger`, `boire`, `dormir` sont joués en boucle sur place, `alerte` par-dessus quand
  il prend peur ou se fâche, `ennui` de temps en temps au repos. Marche, course, dégâts et mort restent automatiques.
- **Points d'apparition** (`apparitions/*.yml`) : une population gardée autour d'un endroit, qui devient la maison
  de ces mobs ; un mob mort est remplacé après un délai. Rien n'apparaît sans joueur à moins de 96 blocs.
- **Volants** (`vol: oiseau` ou `vol: papillon`, `altitude: [4, 12]`) : de vrais mobs, qu'on peut taper, posés au sol,
  sur un toit ou une branche ; ils s'envolent quand un joueur approche (`envol: 5`, deux fois moins loin s'il est
  accroupi) ou de temps en temps, volent de point en point en virant, puis se posent ailleurs, toujours dans leur laisse.
  Les papillons volettent bas en zigzag. Animation de vol : rôle `vol` (par exemple `animations: { vol: fly }`).
  Avec `marche: 0.9` (blocs par seconde), un oiseau posé trottine au sol par petits pas sur du plat et s'arrête
  pour picorer (`ennui: [peck]`) : pigeons de place, moineaux. Son animation `walk` se joue toute seule. Quand un
  joueur fait s'envoler un oiseau, ceux de la même espèce posés à moins de 12 blocs, et toute la bande du même point
  d'apparition, partent avec lui (volée). Un oiseau perché là où il ne peut pas marcher repart au bout de quelques secondes.
- **Mob coincé** : un mob qui veut se déplacer mais ne fait aucun pas pendant 3 s (clôture, pente, recoin) part quelques
  blocs plus loin ; s'il n'a de chemin nulle part (né dans un trou), il est reposé sur un sol libre tout près.
- **Décors vivants** (`type: decor`) : un modèle posé sans entité (bain d'oiseaux, nid, mangeoire), qui joue son
  `idle` en boucle et ses animations `ennui` de temps en temps. Avec `envol: 4` il joue `envol` quand un joueur
  approche (deux fois plus loin s'il court, deux fois moins s'il est accroupi) ; s'il a aussi une animation `retour`,
  il reste en place (les oiseaux sont partis) et la joue 15 s après le départ du dernier joueur proche, sinon il
  disparaît et son point d'apparition le remplace plus tard.
- **Joueurs en créatif** : ils comptent comme les autres pour faire vivre les mobs et les effrayer (oiseaux,
  lapins...), mais ne sont jamais attaqués. Seuls les spectateurs et les joueurs invisibles sont ignorés.
- **IA** : `ia: vorace` (le cerveau remplace l'IA de déplacement du jeu), `ia: jeu` (l'IA du jeu, gardée en laisse :
  poissons, abeilles, grenouilles) ou `ia: aucune`.
- **Performances** : un mob loin de tout joueur (`mobs.distance-reflexion`, 64 blocs) ne réfléchit pas ; les autres
  réfléchissent deux fois par seconde, chacun à son tour. Les besoins sont gardés avec l'entité.
- `/vmob [liste|invoquer <mob> [nombre]|info [début du nom|*] [joueur]|apparitions|retirer [rayon]|recharger]` ; `info` donne pour
  chaque mob son action, ses besoins, sa distance à la maison et la distance parcourue depuis son apparition (depuis la console,
  le nom d'un joueur relève les mobs autour de lui).
- API : `fr.vorace.mobs.VoraceMobAPI` (`invoquerMob`, `mobVorace`, `actionMob`, `effrayerMob`) ; événement
  `fr.vorace.mobs.VoraceMobActionEvent` à chaque changement d'action.

## Installation

1. Mets `Vorace-x.y.z.jar` ([vorace-core](https://github.com/VoracePlugin/vorace-core)) **et** `VoraceMob-x.y.z.jar` dans `plugins/`.
2. Redémarre : des exemples sont créés dans `plugins/VoraceMob/mobs`, `zones` et `apparitions`.
3. Les modèles animés se rangent comme avant dans vorace-core (`plugins/Vorace/modeles-animes/`).

**Tu venais de Vorace 1.1 (mobs dans Vorace)** : au premier démarrage, vorace-mob copie tout seul `plugins/Vorace/mobs`,
`zones`, `apparitions` et le registre des apparitions dans `plugins/VoraceMob`. Les mobs déjà dans le monde sont
repris tels quels. Remplace seulement `/vorace mob ...` par `/vmob ...` dans tes scripts.

| Commande | Rôle |
|---|---|
| `/vmob` | liste des mobs, zones et points d'apparition |
| `/vmob invoquer <mob> [nombre]` | fait apparaître des mobs (en troupeau s'ils en ont un) |
| `/vmob info [début du nom\|*] [joueur]` | état des mobs proches : action, besoins, position, distance parcourue, animation |
| `/vmob apparitions` | points d'apparition et population |
| `/vmob retirer [rayon]` | retire le mob le plus proche |
| `/vmob recharger` | relit `config.yml`, les mobs, les zones et les apparitions |

Permission : `vorace.mob.admin` (op par défaut).

## Pour les autres plugins

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { compileOnly("com.github.VoracePlugin:vorace-mob:<version>") }
```

Puis `depend: [VoraceMob]` dans votre plugin.yml. `VoraceMobAPI.invoquerMob("mouton", endroit)`,
`VoraceMobAPI.actionMob(entite)`, et l'événement `VoraceMobActionEvent`.

## Compiler

```
gradle build tests                                   # vorace-core pris sur JitPack
gradle build tests -PvoraceCore=../vorace-core/build/libs/Vorace-1.3.0.jar   # ou un vorace-core compilé à côté
```

## Essais automatiques

- `gradle tests` : cerveau, troupeaux, volants, sans serveur.
- `outils/essai-serveur.sh` (workflow « Compilation ») : vrai serveur Paper avec vorace-core et vorace-mob, un faux
  joueur (mineflayer) et un « zoo » de mobs ; il vérifie qu'ils pensent, fuient, s'envolent et bougent tout seuls,
  joueur en survie puis en créatif.
- `outils/essai-orario.sh` (workflow « Essai Orario ») : propre au serveur Babel d'Antoine, sur un extrait de sa map
  rangé dans la branche `essai-orario-donnees` ; lancé par un commit marqué `[orario]`, sauté sans cette branche.

## Licence

MIT.
