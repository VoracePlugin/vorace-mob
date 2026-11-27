#!/bin/bash
# Essai automatique de vorace-mob sur un vrai serveur Paper (CI) : un faux joueur (mineflayer) se connecte, des mobs
# sont invoqués (troupeau avec modèle animé, craintifs, volants, pigeons, zoo de toutes les sortes d'Orario) et on
# vérifie qu'ils pensent, fuient, s'envolent et bougent tout seuls, joueur en survie puis en créatif.
# Usage : outils/essai-serveur.sh <version de Paper> <dossier contenant Vorace-*.jar et VoraceMob-*.jar> [version du faux joueur]
set -uo pipefail
VERSION=${1:-1.21.4}
CORE=$(cd "$2" && ls "$PWD"/Vorace-*.jar | head -1)
MOB=$(cd "$2" && ls "$PWD"/VoraceMob-*.jar | head -1)
CLIENT=${3:-}
OUTILS=$(cd "$(dirname "$0")" && pwd)
mkdir -p essai-serveur/plugins && cd essai-serveur
cp "$CORE" "$MOB" plugins/
URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$VERSION/builds/latest" | python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])')
echo "Paper : $URL"
curl -fsSL -o paper.jar "$URL"
if [ -n "$CLIENT" ]; then
  VIA=$(curl -fsSL https://api.github.com/repos/ViaVersion/ViaVersion/releases/latest | python3 -c 'import json,sys; print([a["browser_download_url"] for a in json.load(sys.stdin)["assets"] if a["name"].endswith(".jar")][0])')
  echo "ViaVersion : $VIA"
  curl -fsSL -o plugins/ViaVersion.jar "$VIA"
fi
# un troupeau de golems paisibles (modèle animé d'exemple de vorace-core + cerveau + troupeau) et un lapin craintif
mkdir -p plugins/VoraceMob/mobs
cat > plugins/VoraceMob/mobs/essai.yml <<'MOBS'
golem_paisible:
  type: sheep
  modele: golem_rubis
  taille: [0.5, 0.6]
  caractere: paisible
  troupeau: { cohesion: 5 }
  laisse: 10
  nourriture: [grass_block, short_grass]
lapin_essai:
  type: rabbit
  caractere: craintif
  detection: 40
  laisse: 10
oiseau_essai:
  type: bat
  modele: golem_rubis
  taille: [0.3, 0.3]
  vol: oiseau
  envol: 40
  laisse: 14
  silencieux: true
cochon_essai:
  type: pig
  troupeau: { cohesion: 5 }
  laisse: 10
  nourriture: [grass_block, short_grass]
poule_essai:
  type: chicken
  caractere: craintif
  detection: 40
  laisse: 10
chat_essai:
  type: cat
  caractere: craintif
  detection: 40
  laisse: 10
chevre_essai:
  type: goat
  ia: jeu
  laisse: 8
pigeon_essai:
  type: bat
  modele: golem_rubis
  taille: [0.3, 0.3]
  vol: oiseau
  marche: 0.9
  envol: 1
  laisse: 10
  silencieux: true
lapin_createf:
  type: rabbit
  caractere: craintif
  detection: 40
  laisse: 10
MOBS
# zoo : un mob de chaque sorte réglé comme les animaux d'Orario (sans leurs modèles), pour vérifier qu'ils bougent tout seuls
cat > plugins/VoraceMob/mobs/zoo.yml <<'ZOO'
zoo_mouton: { type: sheep, caractere: paisible, troupeau: { cohesion: 6 }, laisse: 18, nourriture: [grass_block, short_grass] }
zoo_vache: { type: cow, caractere: paisible, troupeau: { cohesion: 7 }, laisse: 20, nourriture: [grass_block, short_grass] }
zoo_cochon: { type: pig, caractere: paisible, troupeau: { cohesion: 6 }, laisse: 12, nourriture: [grass_block, short_grass] }
zoo_cheval: { type: horse, caractere: paisible, troupeau: { cohesion: 8 }, laisse: 14, nourriture: [grass_block, short_grass] }
zoo_poule: { type: chicken, caractere: craintif, detection: 5, laisse: 10, nourriture: [grass_block, short_grass] }
zoo_chat: { type: cat, caractere: craintif, activite: toujours, detection: 5, laisse: 18, boit: false }
zoo_lapin: { type: rabbit, caractere: craintif, detection: 10, laisse: 16, vitesse: 1.2, nourriture: [short_grass, grass_block] }
zoo_renard: { type: fox, caractere: craintif, activite: toujours, detection: 12, laisse: 24, nourriture: [grass_block] }
zoo_cerf: { type: cow, caractere: craintif, detection: 16, laisse: 28, vitesse: 1.3, silencieux: true, nourriture: [grass_block, short_grass] }
zoo_sanglier: { type: pig, caractere: territorial, activite: toujours, laisse: 24, degats: 4, vie: 20, nourriture: [grass_block] }
zoo_ecureuil: { type: rabbit, caractere: craintif, detection: 8, laisse: 12, vitesse: 1.3, silencieux: true, boit: false, nourriture: [grass_block] }
zoo_chevre: { type: goat, caractere: territorial, ia: jeu, laisse: 24 }
zoo_pigeon: { type: bat, vol: oiseau, marche: 0.9, envol: 1, laisse: 20, silencieux: true }
zoo_papillon: { type: bat, vol: papillon, envol: 1, laisse: 10, silencieux: true }
ZOO
# étage 1 de Babel (vorace-mob 1.9.0) : crâne qui court et relève, munitions et recharge au dépôt, sortie de paroi, ronde
cat > plugins/VoraceMob/mobs/etage1.yml <<'E1'
e9_mosaique: { type: husk, caractere: agressif, activite: toujours, silencieux: true, combat: { niveau: 1, vie: 300, ia: { aggro: 3 }, coup-de-base: false,
  a-la-mort: { invocation: e9_crane, nombre: 1 } } }
e9_crane: { type: silverfish, caractere: craintif, activite: toujours, silencieux: true, combat: { niveau: 1, vie: 10, ia: { aggro: 0 }, coup-de-base: false,
  duree-de-vie: 600, fuite: { vers: tas_os, rayon: 10 }, si-arrive: { releve: e9_mosaique, vie: 0.5, delai: 20 } } }
e9_tireur: { type: pillager, caractere: agressif, activite: toujours, silencieux: true, combat: { niveau: 1, vie: 5000, ia: { role: distance, aggro: 20, distance: [3, 12] },
  coup-de-base: false, munitions: { nombre: 2, recharge: depot_essai, rayon: 20, blocs: [honeycomb_block] },
  competences: { tir: { forme: projectile, portee: [1, 18], signal: 6, actif: 1, recuperation: 4, recharge: 20, multiplicateur: 0.05, munitions: 2 },
    recharge: { forme: contact, portee: 0, signal: 40, actif: 1, recuperation: 0, recharge: 40, multiplicateur: 0, apres-munitions: 2 } } } }
e9_paroi: { type: vindicator, caractere: agressif, activite: toujours, silencieux: true, combat: { niveau: 1, vie: 100, ia: { aggro: 2 }, coup-de-base: false } }
e9_ronde: { type: vindicator, caractere: agressif, activite: toujours, silencieux: true, combat: { niveau: 1, vie: 100, ia: { aggro: 0 }, coup-de-base: false,
  ronde: { points: [[-6, -14], [-6, -24]], arret: 20 } } }
E1
echo "eula=true" > eula.txt
cat > server.properties <<PROPS
online-mode=false
level-type=minecraft\:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
generate-structures=false
spawn-protection=0
spawn-monsters=false
spawn-animals=false
view-distance=4
simulation-distance=4
PROPS
rm -f console && mkfifo console
exec 3<>console
java -Xmx2G -jar paper.jar --nogui < console > journal.txt 2>&1 &
SERVEUR=$!
cmd() { echo ">>> $1" >> commandes.txt; echo "$1" >&3; }
attendre() { # attendre <motif> <fichier> <secondes>
  for _ in $(seq 1 "$3"); do grep -q "$1" "$2" 2>/dev/null && return 0; sleep 1; done; return 1
}
if ! attendre 'Done (' journal.txt 400; then echo "Le serveur n'a pas démarré"; tail -80 journal.txt; exit 1; fi
node "$OUTILS/essai-bot.mjs" 250 $CLIENT > bot.txt 2>&1 &
BOT=$!
attendre 'BOT CONNECTE' bot.txt 60 || { echo "Le faux joueur ne s'est pas connecté"; cat bot.txt; }
sleep 2
# mobs : troupeau avec modèle et lapin craintif, à 8 blocs du faux joueur
cmd "vmob invoquer golem_paisible 3"
cmd "vmob invoquer lapin_essai"
for m in oiseau_essai poule_essai chat_essai chevre_essai; do cmd "vmob invoquer $m"; done
cmd "vmob invoquer cochon_essai 2"
sleep 5
cmd "vmob info"
sleep 3
cmd "vmob info"
# joueur en créatif : les animaux vivent quand même autour de lui (marche du pigeon, fuite du lapin)
cmd "gamemode creative Testeur"
cmd "vmob invoquer pigeon_essai 2"
cmd "vmob invoquer lapin_createf"
sleep 4
cmd "vmob info"
sleep 3
cmd "vmob info"
# zoo, joueur toujours en créatif et immobile : 45 s pour voir chaque sorte bouger toute seule
for m in mouton vache cochon cheval poule chat lapin renard cerf sanglier ecureuil chevre pigeon papillon; do cmd "vmob invoquer zoo_$m"; done
cmd "time set 1000"
sleep 22
cmd "vmob info zoo_"
sleep 23
cmd "vmob info zoo_"
# mobs de combat : joueur en survie (protégé), ils le repèrent, se placent, attaquent (coups annoncés, projectiles)
cmd "gamemode survival Testeur"
cmd "tp Testeur 1.5 -60 -6.5"
cmd "effect give Testeur resistance 120 4 true"
cmd "effect give Testeur regeneration 120 4 true"
cmd "vmob invoquer cr_meche_sourde 2"
cmd "vmob invoquer cr_grande_meche"
sleep 5
cmd "vmob info cr_"
sleep 5
cmd "vmob info cr_"
sleep 5
cmd "vmob info cr_"
cmd "vmob menace 32"
# zone sûre : le faux joueur est dans un cercle de 4 blocs (sure: true) ; les mobs de combat lâchent leur cible. Sans la
# zone, ils le reprennent pour cible (la preuve que c'est elle qui les arrête).
mkdir -p plugins/VoraceMob/zones
cat > plugins/VoraceMob/zones/refuge.yml <<'ZONE'
refuge: { monde: world, cercle: [1.5, -6.5, 4], sure: true }
ZONE
cmd "say ESSAI-ZS-1"
cmd "vmob recharger"
# un coup qui repousse a pu le sortir de la zone avant qu'elle existe : on l'y remet
cmd "tp Testeur 1.5 -60 -6.5"
sleep 5
cmd "tp Testeur 1.5 -60 -6.5"
sleep 5
cmd "vmob info cr_"
sleep 1
cmd "say ESSAI-ZS-2"
rm -f plugins/VoraceMob/zones/refuge.yml
cmd "vmob recharger"
# les mobs ont été chassés de la zone et errent : le joueur va à côté de l'un d'eux pour qu'ils puissent le revoir
cmd "execute at @e[type=husk,limit=1,sort=random] run tp Testeur ~3 ~ ~"
sleep 4
cmd "execute at @e[type=husk,limit=1,sort=random] run tp Testeur ~3 ~ ~"
sleep 6
cmd "vmob info cr_"
sleep 1
cmd "say ESSAI-ZS-3"
# étage 1 de Babel (1.9.0)
cmd "kill @e[type=husk]"
cmd "tp Testeur 7 -60 3"
cmd "effect give Testeur resistance 200 4 true"
cmd "effect give Testeur regeneration 200 4 true"
cmd "say ESSAI-E1-CRANE"
cmd "setblock 16 -60 3 bone_block"
cmd "vmob invoquer e9_mosaique"
sleep 3
cmd "tp @e[type=husk,limit=1] 12 -60 3"
sleep 1
cmd "kill @e[type=husk]"
for k in 1 2 3 4 5 6; do sleep 2; cmd "vmob info e9_"; done
cmd "say ESSAI-E1-MUNITIONS"
cmd "kill @e[type=husk]"
cmd "kill @e[type=silverfish]"
cmd "setblock 7 -60 -8 honeycomb_block"
cmd "vmob invoquer e9_tireur"
for k in 1 2 3 4 5 6 7 8 9 10 11 12; do sleep 2; cmd "vmob info e9_tireur"; done
cmd "say ESSAI-E1-PAROI"
cmd "kill @e[type=pillager]"
cmd "fill 22 -60 -1 22 -56 7 stone"
mkdir -p plugins/VoraceMob/apparitions
cat > plugins/VoraceMob/apparitions/etage1.yml <<'APP'
e9_point_paroi: { mob: e9_paroi, monde: world, centre: [19.5, -60, 3.5], rayon: 1, nombre: 1, reapparition: 5, apparition: paroi }
APP
cmd "vmob recharger"
cmd "vmob invoquer e9_ronde"
for k in 1 2 3 4 5 6 7 8; do sleep 2; cmd "vmob info e9_"; done
cmd "say ESSAI-E1-FIN"
wait $BOT
sleep 1
cmd "stop"
for _ in $(seq 1 60); do kill -0 $SERVEUR 2>/dev/null || break; sleep 1; done
kill $SERVEUR 2>/dev/null

echo "===== Faux joueur"; cat bot.txt
echo "===== Console (Vorace)"; grep -aiE 'vorace|mob|exception|error|at fr\.|game mode' journal.txt | grep -v 'Loading server plugin' | head -150
echo "===== Commandes envoyées, dans l'ordre"; cat commandes.txt
echo "===== Mobs du zoo (joueur en créatif)"; grep -a 'mob=zoo_' journal.txt
echo "===== Fin du journal"; tail -30 journal.txt

# verdict
ERREURS=$(grep -acE 'ERROR\]: \[(Vorace|VoraceMob)\]|at fr\.vorace\.' journal.txt)
echo "erreurs de Vorace dans la console : $ERREURS"
if [ "$ERREURS" -gt 0 ]; then echo "ECHEC : erreurs dans la console"; exit 1; fi
grep -aq 'Enabling VoraceMob' journal.txt || { echo "ECHEC : vorace-mob n'a pas démarré"; exit 1; }
grep -aq '3 golem_paisible invoqué(s) en troupeau' journal.txt || { echo "ECHEC : invocation des mobs"; exit 1; }
echo "===== Mobs de combat"; grep -a 'mob=cr_' journal.txt | tail -6
grep -aq 'mob=cr_meche_sourde .*combat=oui' journal.txt || { echo "ECHEC : la Mèche-sourde n'entre pas en combat"; exit 1; }
grep -aqE 'mob=cr_meche_sourde .*attaques=[1-9]' journal.txt || { echo "ECHEC : la Mèche-sourde n'attaque pas"; exit 1; }
grep -aqE 'mob=cr_grande_meche .*combat=oui' journal.txt || { echo "ECHEC : la Grande Mèche n'entre pas en combat"; exit 1; }
grep -aqE 'mob=cr_[a-z_]* .*effets=[1-9]' journal.txt || { echo "ECHEC : aucun effet en modèle 3D posé (fx_cire_...)"; exit 1; }
if grep -aq 'Mobs : erreur' journal.txt; then echo "ECHEC : erreur dans les mobs"; grep -a -A25 'Mobs : erreur' journal.txt | head -60; exit 1; fi
echo "===== Zone sûre"
phase() { awk -v a="$1" -v b="$2" 'index($0, a) {d = 1; next} index($0, b) {d = 0} d' journal.txt; }
DANS_ZONE=$(phase 'ESSAI-ZS-1' 'ESSAI-ZS-2' | grep -a 'mob=cr_')
SANS_ZONE=$(phase 'ESSAI-ZS-2' 'ESSAI-ZS-3' | grep -a 'mob=cr_')
echo "dans la zone sûre :"; echo "$DANS_ZONE"
echo "zone retirée :"; echo "$SANS_ZONE"
[ -n "$DANS_ZONE" ] || { echo "ECHEC : /vmob info n'a rien affiché pendant l'essai de la zone sûre"; exit 1; }
echo "$DANS_ZONE" | grep -aq 'combat=oui' && { echo "ECHEC : un mob de combat vise encore le joueur dans la zone sûre"; exit 1; }
echo "$SANS_ZONE" | grep -aq 'combat=oui' || { echo "ECHEC : zone retirée, les mobs ne reprennent pas le joueur pour cible (l'essai ne prouve rien)"; exit 1; }
MOBS_VUS=$(grep -ao 'mob=[a-z_]* action=[a-z]*' journal.txt | sort -u)
echo "mobs vus : $MOBS_VUS"
echo "$MOBS_VUS" | grep -q 'mob=golem_paisible' || { echo "ECHEC : les golems de l'essai ne pensent pas"; exit 1; }
echo "$MOBS_VUS" | grep -q 'mob=lapin_essai action=fuir' || { echo "ECHEC : le lapin craintif ne fuit pas le joueur"; exit 1; }
echo "$MOBS_VUS" | grep -q 'mob=oiseau_essai action=vole' || { echo "ECHEC : l'oiseau ne s'envole pas"; exit 1; }
echo "$MOBS_VUS" | grep -q 'mob=poule_essai action=fuir' || { echo "ECHEC : la poule ne fuit pas"; exit 1; }
echo "$MOBS_VUS" | grep -q 'mob=chat_essai action=fuir' || { echo "ECHEC : le chat ne fuit pas"; exit 1; }
for m in cochon_essai chevre_essai; do echo "$MOBS_VUS" | grep -q "mob=$m" || { echo "ECHEC : $m absent"; exit 1; }; done
echo "$MOBS_VUS" | grep -q 'mob=lapin_createf action=fuir' || { echo "ECHEC : en créatif, le lapin ne fuit pas (les mobs ne pensent pas)"; exit 1; }
PIED=$(grep -ao 'mob=pigeon_essai .*a-pied=[0-9.]*' journal.txt | grep -ao 'a-pied=[0-9.]*' | cut -d= -f2 | sort -g | tail -1)
echo "pigeon : ${PIED:-0} blocs à pied"
awk -v p="${PIED:-0}" 'BEGIN {exit !(p > 0.5)}' || { echo "ECHEC : en créatif, le pigeon ne marche pas"; exit 1; }
echo "===== Zoo (joueur en créatif, 45 s) : distance parcourue par chaque mob"
ZOO_KO=0
for m in mouton vache cochon cheval poule chat lapin renard cerf sanglier ecureuil chevre pigeon papillon; do
  D=$(grep -ao "mob=zoo_$m .*parcouru=[0-9.]*" journal.txt | grep -ao 'parcouru=[0-9.]*' | cut -d= -f2 | sort -g | tail -1)
  ACTIONS=$(grep -ao "mob=zoo_$m action=[a-z]*" journal.txt | cut -d= -f3 | sort -u | tr '\n' ' ')
  printf 'zoo_%-10s %6s blocs   actions : %s\n' "$m" "${D:-0}" "$ACTIONS"
  awk -v p="${D:-0}" 'BEGIN {exit !(p > 1.0)}' || ZOO_KO=1
done
[ $ZOO_KO = 0 ] || { echo "ECHEC : une sorte de mob ne bouge pas toute seule en 45 s"; exit 1; }
if grep -ao 'maison=[0-9.]*/[0-9]*' journal.txt | awk -F'[=/]' '$2 > $3 + 2 {bad=1} END {exit !bad}'; then echo "ECHEC : un mob est sorti de sa laisse"; exit 1; fi
echo "===== Étage 1 de Babel (1.9.0)"
CRANE=$(phase 'ESSAI-E1-CRANE' 'ESSAI-E1-MUNITIONS' | grep -a 'mob=e9_')
MUNI=$(phase 'ESSAI-E1-MUNITIONS' 'ESSAI-E1-PAROI' | grep -a 'mob=e9_tireur')
PAROI=$(phase 'ESSAI-E1-PAROI' 'ESSAI-E1-FIN' | grep -a 'mob=e9_')
echo "crâne qui court :"; echo "$CRANE"
echo "munitions :"; echo "$MUNI" | grep -ao 'mode=[a-z]* .*munitions=[0-9]*' | sed 's/ effets=.*munitions=/ munitions=/'
echo "paroi et ronde :"; echo "$PAROI" | grep -ao 'mob=e9_[a-z]* .*pos=[-0-9.,]*\|ronde=[0-9]*' | head -20
echo "$CRANE" | grep -aq 'mob=e9_crane' || { echo "ECHEC : la Mosaïque tuée ne fait pas naître de crâne"; exit 1; }
echo "$CRANE" | grep -aqE 'mob=e9_crane .*fuite=(en-route|arrive)' || { echo "ECHEC : le crâne ne trouve pas le bloc d'os"; exit 1; }
echo "$CRANE" | grep -aq 'mob=e9_mosaique .*sans-butin=oui' || { echo "ECHEC : le crâne ne relève pas la Mosaïque (sans butin)"; exit 1; }
echo "$MUNI" | grep -aq 'munitions=0' || { echo "ECHEC : le tireur n'use pas ses munitions"; exit 1; }
# rechargé : on le voit avec des munitions après 0, ou il tire encore après 0 (sans munitions, le tir ne part pas ;
# recharge + 2 tirs tiennent entre deux /vmob info)
echo "$MUNI" | awk '{match($0, /attaques=[0-9]+/); a = substr($0, RSTART + 9, RLENGTH - 9) + 0}
  /munitions=0/ && !z {z = 1; a0 = a} z && /munitions=[1-9]/ {ok = 1} z && a >= a0 + 3 {ok = 1} END {exit !ok}' \
  || { echo "ECHEC : le tireur ne recharge pas"; exit 1; }
echo "$MUNI" | grep -aq 'mode=depot' || { echo "ECHEC : le tireur ne marche pas au dépôt"; exit 1; }
echo "$PAROI" | grep -aq 'mob=e9_paroi' || { echo "ECHEC : rien ne sort de la paroi"; exit 1; }
echo "$PAROI" | grep -ao 'mob=e9_paroi .*pos=[-0-9.]*' | grep -aqE 'pos=21\.' || { echo "ECHEC : le mob de la paroi n'est pas collé à la paroi (x = 21,5)"; exit 1; }
[ "$(echo "$PAROI" | grep -ao 'mob=e9_ronde .*ronde=[0-9]*' | grep -ao 'ronde=[0-9]*' | sort -u | wc -l)" -ge 2 ] || { echo "ECHEC : la ronde ne passe pas d'un point à l'autre"; exit 1; }
echo "ESSAI REUSSI"
