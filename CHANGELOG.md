# Journal des changements

## 1.11.0 (étage 1 de Babel : embuscades, invocations, soins d'allié, aura, neutres)

- **Un seul butin par mob** (choix d'Antoine) : le butin par membre à 10 % de la 1.9.0 est retiré, il dupliquait le
  butin des élites et des boss dans un groupe. Le gagnant seul tire la table ; `getButinDesMembres()` reste, vide.
- **Embuscade** (`cache: { eveil: 3 }` d'un point d'apparition) : la vague due attend, cachée, qu'un joueur passe à 3
  blocs du point (en plus de son rayon), puis sort d'un coup avec son animation d'apparition (dépôts d'ossements et leurs
  Squelettes rampants). Vérifié toutes les 5 tics.
- **Neutre** (`neutre: true` du mob) : il n'attaque que si on le frappe (Glaneur, Lame-orpheline).
- **Attaques, en plus** :
  - `invocation: { mob, nombre, max, total }` : au coup, ces mobs naissent autour de lui et prennent sa cible ; `max`
    vivants à la fois, `total` sur tout le combat (âmes enragées de la lanterne du Passeur : 3, 6, 24) ;
  - `phase-max: 1` : seulement jusqu'à cette phase ;
  - `soin-allie: { cible, seuil, soin }` : la Refonte de la Moulière soigne un allié de la famille `cire` sous 30 % de Vie,
    de 40 % ; elle ne part que s'il y en a un ;
  - `interrompu-par: 2` : 2 coups reçus pendant le signal l'interrompent (condition `interrompu_<attaque>` notée).
- **Mécaniques** : `famille: cire` ; `eclair-a-la-mort: { rayon, aveuglement }` (l'essaim de phalènes aveugle en mourant) ;
  `aura: { rayon, degats, purification }` (l'Os-violon : les mobs à 10 blocs frappent × 1,2 ; une Purification l'éteint,
  `VoraceMobAPI.purifier(entite, secondes)`, appelée par Matsuri Cœur 0.44.1).

## 1.10.0 (rang porté par les objets de butin)

- Fichiers `rangs/*.yml` : pour un objet de Vorace, son rang de base et son spectre (`objets: { e1_crane_fele: { base:
  peu_commun, spectre: { commun: 20, peu_commun: 70, rare: 10 } } }`). Chaque exemplaire tombé tire son rang ; s'il n'est
  pas au rang de base, il porte `vorace:rang` et `vorace:rang_base` (lus par Matsuri Économie 0.4.0 : × 2 par rang
  au-dessus, × 0,5 en dessous), son nom prend la couleur du rang et sa description le nom du rang (« Rare » au lieu de
  « Peu commune »). Au rang de base, rien ne change (il s'empile avec les autres). `VoraceMobAPI.rangObjet(objet)`.

## 1.9.0 (étage 1 de Babel : butin spécial, butin de groupe, crâne qui court, munitions, ronde, paroi)

- **Butin spécial** (`combat.butin-special`) :
  - `chasseur: true` : pièce prélevable du Chasseur ; elle ne tombe pas, elle est rendue dans
    `VoraceMobMortEvent.getPiecesPrelevables()` (joueur → objets) pour le plugin des métiers ;
  - `si: <condition>` : seulement si la condition a été notée pendant le combat (`garde_brisee` est notée par vorace-mob,
    les autres par `VoraceMobAPI.noterCondition(entite, condition)`) ; `si: par_...` se compte : chance × le nombre de
    fois, 1 au plus ;
  - `premier-kill: true` : une fois par joueur et par mob (`donnees/premiers-kills.yml`).
- **Butin de groupe** : élites, élites ++, mini-boss et boss : chaque membre du combat qui a fait au moins 10 % des
  contributions (dégâts, plus les soins et protections notés par `VoraceMobAPI.contribuer(entite, joueur, montant)`)
  tire sa table, réservée à lui (`VoraceMobMortEvent.getButinDesMembres()`). Monstres normaux : le gagnant seul.
- **Second coup** (`second-coup: { tic, forme, angle, rayon, multiplicateur }` d'une attaque) : le Double coup de massue
  du Squelette-mosaïque frappe deux fois.
- **Munitions** (`combat.munitions: { nombre, recharge, rayon, blocs }`, attaques `munitions:` et `apres-munitions:`) :
  la Mèche-sourde tire 3 fois, puis marche au dépôt de cire le plus proche (point de zone `depot_de_cire`, sinon bloc
  de rayon de miel à 12 blocs ; sur place sans dépôt) et recharge. `VoraceMobAPI.munitions`, `retirerMunitions` (une
  branche coupée).
- **Crâne qui court** : `a-la-mort` part de l'os `depart` du modèle, `delai` tics après la mort (le crâne saute de la
  Mosaïque) ; `duree-de-vie` (il disparaît sans butin), `fuite: { vers: tas_os, rayon: 8 }` (point de zone, sinon bloc
  d'os), `si-arrive: { releve, vie, delai }` : arrivé, le squelette se relève là avec 50 % de sa Vie, sans butin ni XP
  (`VoraceMobMortEvent.isSansButin()`, `VoraceMobAPI.sansButin`).
- **Tertre** (`combat.tertre: { assis, regen, attente, lever }`) : le Gardien est assis au calme (animation `assis`), se
  lève à l'engagement (`se-lever`), ne récupère pas toute sa Vie en rentrant : 1 % par seconde après 10 s de calme.
- **Ronde** (`ronde:` d'un point d'apparition ou de `combat:`) : aller-retour de point en point au calme, arrêt aux deux
  bouts (`arret` en tics) ; sa maison suit (la laisse part du dernier point de sa ronde). Animation `marche` du mob.
- **Sortie de paroi** (`apparition: paroi` d'un point) : le mob sort de la paroi la plus proche (fissure lumineuse de
  30 tics, figé pendant ce temps), jamais à moins de 8 blocs d'un joueur.
- **Animation d'apparition** : le rôle `apparition` des animations du mob est joué à chaque apparition.
- **Phases** : `animations: { garde: idle_a_pied, marche-combat: marche_a_pied }` dans une phase change son repos et sa
  marche à partir de là (le Passeur à pied, puis gardant sa lanterne).
- Animations en boucle propres au mob (rôles `marche`, `garde`... de ses animations ou de ses phases) : elles se
  remplacent l'une l'autre ; avant, une boucle au nom propre au mob restait jouée.
- `/vmob info` : munitions, conditions notées, mob relevé.

## 1.8.0 (étage 1 de Babel : mécaniques du bestiaire et points d'apparition)

- **Mécaniques des monstres** (bloc `combat:`) :
  - `a-la-mort: { invocation: e1_crane_qui_court, nombre: 1 }` : à sa mort, ces mobs naissent à sa place (même niveau,
    sans point d'apparition) ;
  - `garde: { reduction, angle, brise-apres, ouverture, animation }` : un coup reçu de face (dans l'angle) perd
    `reduction` de ses dégâts (son de bouclier) ; après `brise-apres` coups gardés, la garde se brise : `ouverture` tics
    sans garde et figé, son animation jouée. `VoraceMobAPI.gardeBrisee(entite)` (butin « si: garde_brisee » plus tard) ;
  - `point-faible: { dos, multiplicateur, fige }` : frappé dans le dos (à plus de 120° de son regard), dégâts ×
    `multiplicateur` et figé `fige` tics ;
  - `jamais-mortel: true` (pantins d'Orario) : `VoraceMobAPI.jamaisMortel(entite)`, pour le plugin du combat.
- **Points d'apparition** :
  - `chance: 0.35` : monstre rare, une chance par tour (raté : on attend le tour suivant) ;
  - `groupe: gl02` : les points du même groupe partagent le départ de leur cycle (un groupe mêlé revient en une vague) ;
  - `xp-multiplicateur: 1.5` et `butin-bonus: { objet, nombre }` : XP et un objet en plus pour les mobs de ce point ;
  - `pause-si-salle: passeur` (salle de Matsuri Donjon occupée), `pause-si-joueur: 24` (un joueur à 24 blocs : rien
    n'apparaît sous ses yeux), `pause-max: 1200` (jamais plus de 1 200 s sans vague) ;
  - `entraide: 0` : distance d'entraide des mobs de ce point (0 : on les prend un par un).
- Pas encore lus : apparition par une paroi, ronde de la Faucheuse, munitions de la Mèche-sourde, `butin-special`,
  `releve`, `second-coup`, `hors-donjon` et les autres clés « à apprendre » du bestiaire.

## 1.7.0 (étage 1 de Babel : taille du groupe et butin de vorace-item)

- **Vie et dégâts selon la taille du groupe** (élites, élites ++, boss) : `vie-groupe: k` et `degats-groupe: {1: 0.4,
  2: 0.7}` dans `combat:`. À l'engagement, le joueur qui l'engage et les joueurs à 30 blocs sont comptés ; chaque
  nouveau joueur qui le frappe s'ajoute. Vie maximale = Vie × (1 + k × (joueurs − 1)), au même pourcentage si elle
  monte en plein combat ; la barre de boss suit. Puissance de ses coups (coup de base, attaques, flaques) × le facteur
  du nombre de joueurs (× 1 s'il n'est pas écrit). Au retour au calme, tout revient à un joueur. Sans ces clés, rien ne
  change. Pour l'étage 1 : Passeur `vie: 12500, vie-groupe: 1.0, degats-groupe: {1: 0.4, 2: 0.7}` (même durée seul
  qu'à six), élites `0.7`, Faucheuse `0.8`, Armure veuve `0.85`.
- **API** : `VoraceMobAPI.joueursDuCombat(entite)` et `imposerJoueurs(entite, n)` (joueurs de l'arène d'un boss, pour le
  plugin du donjon ; 0 rend le comptage à vorace-mob). `/vmob info` affiche `joueurs=`.
- **Butin** : lignes `objet: item:<modèle>` (modèles de vorace-item : armes, armures, flèches), jusqu'ici ignorées. Un
  exemplaire au rang de base du modèle et à une place tirée au hasard dans sa fourchette ; les objets qui ne s'empilent
  pas tombent en autant d'exemplaires que le nombre tiré. `softdepend: [VoraceItem]`, appelé sans dépendance de
  compilation : sans vorace-item, la ligne ne donne rien (signalée par `/vmob butin`).

## 1.6.1 (retours des essais en jeu du Seuil)

- Un coup de mob ne repousse plus le joueur par le recul du jeu : seulement par son réglage `repousse`. Avant, chaque
  projectile, chaque flaque et chaque coup de base poussait le joueur (et l'éloignait des mobs à distance).
- `animation-frappe: <tic>` sur une compétence : le tic de l'animation où le geste frappe. L'animation part plus tard pour que
  ce geste tombe à la fin du signal. Exemples corrigés : l'Éclaboussure giclait 5 tics avant le coup, le Cierge abattu
  s'abattait 8 tics avant.
- Point d'apparition modifié (autre mob, autre niveau) : les anciens mobs du point, au repos, sont remplacés tout de suite
  au lieu de rester jusqu'à leur mort.

## 1.6.0 (novembre 2026) : le donjon (zone sûre, niveau et cycle des apparitions)

Pour le plugin du donjon de Babel, mais utile à tout serveur.
- **Zone sûre** : `sure: true` dans une zone (`zones/*.yml`). Aucun mob de combat n'y apparaît (un point d'apparition
  ne pose rien dedans) ni n'y entre ; un mob de combat qui s'y retrouve (recul, invocation) retourne à sa dernière
  place hors de la zone ; un joueur qui s'y trouve n'est plus une cible et n'est pas touché par leurs attaques (contact,
  zones, projectiles). Les animaux, volants et décors y vivent normalement.
- **Points d'apparition** : `niveau:` (niveau imposé aux mobs de combat du point) et `cycle:` (en secondes) : au lieu
  d'un remplacement mob par mob après `reapparition`, tous les mobs manquants reviennent ensemble `cycle` secondes après la
  première mort. Le cycle repart à zéro au redémarrage du serveur. `/vmob apparitions` les affiche.
- **API** : `VoraceMobAPI.invoquerMob(id, endroit, niveau)`, `retirerMob(entite)` (sans butin ni XP, barre et modèle
  retirés) et `zoneSure(endroit)`. `/vmob retirer` passe par la même méthode.

## 1.5.1 (novembre 2026) : équilibre des groupes

Retour de l'essai de la 1.5.0 : un groupe de trois Mèches-sourdes ne se laissait plus approcher.
- **Chacun son tour** : à plusieurs sur le même joueur (rangs normal et élite), `ia.cadence` tics au moins entre deux
  attaques du groupe sur lui (24 par défaut, le coup de base n'est pas compté) et `ia.repit` tics entre deux attaques
  lourdes (80 par défaut ; `lourde:` d'une attaque, par défaut les salves, plusieurs zones, flaques, repousse et bond).
- **Il se dégage seulement s'il est frappé** : `presse: 2` (par défaut pour un bond en arrière) : l'attaque n'est
  possible qu'après 2 coups reçus en 3 s.
- **Recul** : `ia.recul` (0,85 par défaut) et `ia.souffle` (40 tics) : après 2 s de recul il est essoufflé et tient sa
  place 3 s.
- **Mode spectateur** (joueur à terre, caméra d'un ultime) : le mob invisible qui porte un modèle n'apparaît plus en
  transparence ; il est caché à ce joueur le temps du mode spectateur, avec sa barre de vie (le modèle reste visible).
- Exemple `mobs/combat.yml` adouci (l'ancien est gardé en `combat.yml.ancien`) : Goutte ardente moins fréquente et qui
  anticipe moins, Pluie de suif à deux cercles, flaques plus courtes, Éclaboussure plus lente, moins forte et plus rare.

## 1.5.0 (novembre 2026) : retours de l'essai du cerveau de combat

- **Plus de nom au-dessus des mobs**, seulement leur barre de vie, en combat (`combat.nom-visible: true` pour le nom et
  le niveau). Le nom reste sur la barre de boss des élites ++ et des boss.
- **Barre de vie refaite** : quatre affichages d'objets tournés vers le joueur (cadre sombre, or, violet ou rouge selon le
  rang, Vie, traîne des derniers dégâts, vide), toujours éclairés, qui chevauchent le mob. La Vie et la traîne glissent.
  Plus de fond gris ni de texte : la barre ne se perd plus derrière les flammes d'un modèle.
- **Vraie barre de boss** : barre dessinée (cadre du rang, Vie, traîne) par-dessus celle du jeu, nom et niveau centrés
  au-dessus.
- **Effets des attaques en modèles animés** (`effets:` d'une attaque : `marque`, `projectile`, `impact`, `pluie`,
  `flaque`) à la place des particules ; les modèles d'exemple `fx_cire_marque`, `fx_cire_dalle`, `fx_cire_goutte`,
  `fx_cire_impact`, `fx_cire_pluie`, `fx_cire_flaque` sont installés dans `plugins/Vorace/modeles-animes`.
- **Attaques plus dures** :
  - projectiles : `vitesse` (blocs par tic), `anticipation` (il vise où la cible sera), touche de plein fouet un joueur
    sur sa route, `salve` et `intervalle` (2e tir plus loin sur la route, 3e sur la cible), `depart` en liste ;
  - zones : `zones` et `dispersion` (plusieurs cercles : sur la cible, sur sa route, autour) ;
  - `flaque` : l'impact laisse une flaque brûlante (un coup à `flaque-multiplicateur` toutes les 10 tics) ;
  - `repousse` (les joueurs touchés sont repoussés) et `bond` (le mob bondit après son coup, en arrière si négatif) ; une
    attaque à `bond` négatif passe avant les autres : collé, il se dégage ;
  - déplacements en combat plus vifs (recul et pas chassés).
- Exemple `mobs/combat.yml` refait (l'ancien est gardé en `combat.yml.ancien`) : Goutte ardente rapide, Salve ardente,
  Pluie de suif à trois cercles, Éclaboussure avec bond en arrière.
- Un mob invoqué à un niveau au-dessus de son niveau de base apparaît avec toute sa Vie.

## 1.4.0 (novembre 2026) : cerveau de combat

Retours de l'essai de la 1.3.0 :
- **Barre de vie dessinée** (glyphes installés dans Vorace au démarrage) : fond sombre, Vie rouge en dégradé avec crans
  de 10 %, **traîne claire** des derniers dégâts qui rattrape la Vie, cadre selon le rang (fer, or, violet, rouge à
  pointes) ; fond gris de l'étiquette retiré. Plus de « Vorace » devant le nom.
- Le coup de base tombe **quand l'animation frappe** (signal de 9 tics) au lieu de lancer l'animation au moment du coup.
- Le **niveau compte** : progression par niveau d'écart avec le niveau de base (`progression:`, par défaut +10 % de
  Vie, +6 % de Puissance, +5 % de défense, +10 % d'XP par niveau).

Cerveau de combat (bloc `combat.ia`, `combat.competences`, `combat.coup-de-base`) :
- **Menace** : premier joueur vu (distance d'aggro, en vue) à 10, 1 par dégât, le plus proche × 1,1, changement de
  cible à 110 % (cible à 5 blocs ou moins) ou 130 %, hors de vue plus de 5 s : −10 %/s, 8 mobs au plus par joueur (le
  plus ancien oublie), mort, départ ou changement de monde : oublié ; les mobs du même type autour le rejoignent
  (`entraide`). Trop loin de chez lui ou plus personne : il **rentre** en courant et récupère sa Vie.
- **Déplacements** : rôle `contact` (va au corps à corps) ou `distance` (garde sa bande de distance : recule face à la
  cible, se rapproche par le chemin, **tourne autour en pas chassés** avec des pauses, s'écarte des alliés, saute les
  marches, ne se jette pas dans un trou). Regarde toujours sa cible. Petit sursaut d'alerte quand il te repère.
- **Attaques annoncées** : signal (animation, zone marquée au sol qui se remplit), actif, récupération, recharge ;
  formes contact, projectile en cloche (impact marqué pendant le vol, bloqué par les murs), zone posée sur la cible,
  cercle autour de lui, cône, ligne ; os de départ du projectile ; altération du coup ; poids ; seulement sous X % de Vie
  ou à partir d'une phase.
- **Contrôles** (API, pour le plugin du combat) : `entraver` (étourdi, enraciné : une attaque en train d'être annoncée
  est interrompue), `provoquer`, `aveugler`, `fuir` ; réactions aux compétences de vorace-core (saisi, plaqué,
  soulevé, projeté) subies, puis `get_up` pour se relever.
- **Élites ++ et boss** : barre de boss (élite ++ violette, mini-boss et boss rouges), phases à des seuils de Vie
  (annonce, animation, vitesse, invocations), attaques annoncées en sous-titre, **Fixation** (`fixation: hasard`,
  `loin`, `proche` : cible choisie par le script, marquée au-dessus de sa tête), **enrage** (`enrage: 300` : dégâts ×
  1,5 et plus rapide).
- **Butin** (`combat.butin`) : objets du jeu ou de Vorace (`vorace:<id>`), chance, nombre ; à 50,01 % des dégâts, sinon
  au joueur qui en a fait le plus ; objets réservés au gagnant ; événement `VoraceMobMortEvent` (dégâts, gagnant, XP,
  objets) pour le plugin du combat ; XP du jeu sinon.
- Animations de combat reconnues : `idle_combat`, `walk_combat`, `run`, `walk_back`, `strafe_left`, `strafe_right`,
  `flee`, `alert`, `get_up` (renommables dans `animations:`).
- Commandes : `/vmob menace [rayon]` (cible, mode, attaque, table de menace du mob le plus proche), `/vmob butin <mob>`,
  `/vmob point <mob> [nombre] [rayon] [réapparition]` (point d'apparition à ta position, `apparitions/points.yml`),
  `/vmob point retirer <id>`. API : `nom`, `enCombat`, `cible`, `ajouterMenace`, `menaceDeSoin`, `entraver`,
  `provoquer`, `aveugler`, `fuir`, `infliger(entité, montant, source)`.
- Exemple `mobs/combat.yml` : la Mèche-sourde complète (Goutte ardente, Pluie de suif, coup de chandelier, cristaux) et
  la Grande Mèche, essai d'élite ++ (phases, invocations, Fixation, Cierge abattu, enrage). L'exemple de la 1.3.0 est
  remplacé s'il n'a pas été modifié.

## 1.3.0 (novembre 2026) : mobs de combat

Un bloc `combat:` fait d'un mob un **mob de combat** (voir `mobs/combat.yml`, installé au démarrage) :

- **Vie** gardée par vorace-mob (elle peut dépasser les 1 024 de santé du jeu) ; la santé du jeu suit la même proportion
  sur 100 pour la mort et le tueur. Le jeu ne le soigne pas tout seul ; son armure du jeu est à 0.
- **Niveau** fixe ou tiré dans une fourchette (`niveau: [8, 10]`), **rang** (normal, elite, elite++, mini-boss, boss) et
  **stats libres** (`stats:`), lues par le plugin du combat du serveur.
- **Nom, niveau et barre de vie** au-dessus du mob, au point `tag_` de son modèle : nom coloré selon le rang, barre
  (verte, jaune, rouge) et Vie visibles pendant le combat et 10 s après. Le nom du jeu au survol est caché.
- Dégâts qui ne passent pas par le plugin du combat (feu, chute, serveur sans plugin de combat) : 1 point du jeu = 1 %
  de la Vie. Rien du jeu ne tombe à sa mort (le butin viendra des tables de butin).
- API : `estCombattant`, `niveau`, `rang`, `stat`, `stats`, `vie`, `vieMax`, `infliger`, `soigner`, `coupEnCours`
  (record `CoupMob` : Puissance, physique ou magique, type, multiplicateur, altération) pendant le coup du mob.
- `/vmob invoquer <mob> [nombre] [niveau]` (niveau imposé, auto-complété), `/vmob info` montre niveau, Vie et rang.

## 1.2.0 (novembre 2026) : Paper 1.21.4 au minimum

VoraceMob demande maintenant un serveur **Paper 1.21.4 ou plus récent** (`api-version: '1.21.4'`), comme
vorace-core 1.3.0 : il ne se charge plus sur 1.21 à 1.21.3. La dernière version pour ces serveurs est la 1.1.0.

- Le plugin est compilé contre l'API Paper 1.21.4 (au lieu de 1.21.1), y compris la dépendance JitPack.
- Le comportement des mobs, la commande `/vmob`, la config et l'API ne changent pas.

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
