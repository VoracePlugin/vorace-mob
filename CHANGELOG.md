# Journal des changements

## 1.1.0 (novembre 2026) : sortie de Vorace

Les mobs de Vorace 1.1 deviennent un plugin à part, **vorace-mob**, qui dépend de
[vorace-core](https://github.com/VoracePlugin/vorace-core). Le comportement des mobs ne change pas.

- Plugin `VoraceMob` (`depend: [Vorace]`), fichiers dans `plugins/VoraceMob/` ; au premier démarrage, les dossiers
  `mobs`, `zones`, `apparitions` et le registre des apparitions de `plugins/Vorace` y sont copiés.
- Les mobs déjà dans le monde sont reconnus (mêmes clés de données `vorace:mob...`).
- Commande `/vmob` (alias `/voracemob`) au lieu de `/vorace mob`, mêmes sous-commandes ; permission `vorace.mob.admin`.
- API : `fr.vorace.mobs.VoraceMobAPI` au lieu des méthodes de mobs de `VoraceAPI` ; l'événement devient
  `fr.vorace.mobs.VoraceMobActionEvent`.
- `config.yml` : seulement `mobs.distance-reflexion` (`mobs.actif` n'a plus lieu d'être : on retire le plugin).

Ce que font les mobs (écrit pour Vorace 1.1, sur la branche `ameliorations-modeles` du fork d'Antoine) :

### Mobs

- Cerveau : besoins (faim, soif, fatigue), émotions (peur, colère) ; chaque action reçoit une note et la meilleure
  l'emporte : repos, errer, manger, boire, dormir, rejoindre le troupeau, rentrer, fuir, attaquer.
- Caractères paisible, craintif, territorial, agressif ; horaires diurne, nocturne, toujours.
- Troupeaux, maison et laisse, zones (rectangle, cercle, polygone) avec mangeoire, abreuvoir et abri.
- Points d'apparition : population gardée autour d'un endroit, morts remplacés après un délai, rien n'apparaît sans
  joueur à moins de 96 blocs.
- Volants (oiseaux, papillons) : vrais mobs qu'on peut taper, posés au sol ou en hauteur, envol quand on approche,
  vol de point en point, atterrissage dans la laisse ; les oiseaux qui marchent (pigeons) trottinent et picorent ;
  volée : toute la bande part ensemble ; ils ne se posent pas à côté d'un joueur.
- Décors vivants (`type: decor`) : modèle sans entité (bain d'oiseaux, nid) qui joue `envol` quand on approche et
  `retour` 15 s après le départ du dernier joueur.
- Mob coincé : repart quelques blocs plus loin, ou reposé sur un sol libre s'il n'a de chemin nulle part.
- Un mob monté ou tenu en laisse obéit au joueur.
- Les joueurs en créatif font vivre et effraient les mobs comme les autres, sans jamais être attaqués.
- IA au choix : `vorace` (le cerveau), `jeu` (IA du jeu gardée en laisse : poissons, abeilles) ou `aucune`.
- Performances : un mob à plus de 64 blocs de tout joueur ne réfléchit pas ; les autres deux fois par seconde,
  chacun à son tour.

Corrections trouvées sur la vraie map pendant les essais :
- le sol d'un oiseau se cherche sous lui en sautant les barrières (un plafond de barrières invisibles était pris
  pour le sol : les oiseaux ne marchaient ni ne s'envolaient) ;
- les joueurs en créatif étaient ignorés, donc aucun mob ne « réfléchissait » autour d'eux ;
- un mob qui broutait loin de son troupeau faisait des allers-retours ; il broute maintenant près de lui ;
- une fuite sans chemin prend une autre direction, sinon le mob se calme.

Testé sur un vrai serveur Paper 1.21.4 avec la map d'Orario (2 185 animaux sur 1 083 points d'apparition, 25 espèces).
L'historique détaillé de ces changements est celui de vorace-core (commits de novembre 2026).
