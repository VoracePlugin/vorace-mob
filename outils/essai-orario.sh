#!/bin/bash
# Essai sur la vraie map d'Orario (CI) : serveur Paper 1.21.4 avec le monde et les animaux d'Orario (extrait : quelques
# fichiers de région), un faux joueur en CRÉATIF emmené :
#   - sur deux places à pigeons (vus de 12 blocs, puis le joueur arrive à 1,5 bloc d'un pigeon posé : la volée part) ;
#   - près d'un bain d'oiseaux (de loin : présents ; à 1,5 bloc : partis ; relevé toutes les 2 s après son départ) ;
#   - près d'un point d'apparition de CHAQUE espèce présente dans l'extrait (relevé toutes les 4 s) ;
# puis le serveur est arrêté et redémarré, et on relève de nouveau plusieurs espèces (les animaux reprennent leur cerveau).
# Usage : outils/essai-orario.sh <zip du serveur Orario> <dossier contenant Vorace-*.jar et VoraceMob-*.jar>
# Les fichiers de mobs de l'extrait sont encore dans plugins/Vorace (avant la séparation) : vorace-mob les reprend tout seul.
set -uo pipefail
ZIP=$(cd "$(dirname "$1")" && echo "$PWD/$(basename "$1")")
JAR=$(cd "$2" && ls "$PWD"/Vorace-*.jar | head -1)
MOB=$(cd "$2" && ls "$PWD"/VoraceMob-*.jar | head -1)
OUTILS=$(cd "$(dirname "$0")" && pwd)
mkdir -p essai-orario && cd essai-orario
unzip -q -o "$ZIP"
SRV=$(dirname "$(find . -maxdepth 3 -name 'level.dat' -path '*Orario*' | head -1)")
cd "$SRV/.."
rm -f plugins/Vorace-*.jar plugins/VoraceMob-*.jar && cp "$JAR" "$MOB" plugins/
URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/1.21.4/builds/latest" | python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])')
curl -fsSL -o paper.jar "$URL"
echo "eula=true" > eula.txt
cat > server.properties <<PROPS
online-mode=false
level-name=Orario
spawn-protection=0
view-distance=5
simulation-distance=5
spawn-monsters=false
PROPS
# lieux : deux places à pigeons, un bain d'oiseaux, puis pour chaque espèce son point le plus peuplé (loin du bord de l'extrait)
python3 - <<'PY' > lieux.txt
import yaml, glob, os
ap = {}
for f in glob.glob('plugins/Vorace/apparitions/*.yml'):
    ap.update(yaml.safe_load(open(f)) or {})
def dedans(c):
    rx, rz = int(c[0] // 512), int(c[2] // 512)
    return os.path.exists(f'Orario/region/r.{rx}.{rz}.mca') and 48 <= c[0] % 512 <= 464 and 48 <= c[2] % 512 <= 464
ok = {k: v for k, v in ap.items() if dedans(v['centre'])}
def ligne(nom, k):
    v = ok[k]; c = v['centre']
    print(f"{nom} {k} {v['mob']} {c[0]:.1f} {c[1]:.1f} {c[2]:.1f} {v.get('nombre', 1)}")
places = sorted((k for k in ok if k.startswith('pigeons_place_') and ok[k].get('nombre', 1) >= 4), key=lambda k: -ok[k]['nombre'])
for i, k in enumerate(places[:2]):
    ligne(f'PIGEONS{i + 1}', k)
ant = min((k for k in ok if k.startswith('pigeons_place_')), key=lambda k: (ok[k]['centre'][0] - 245) ** 2 + (ok[k]['centre'][2] - 229) ** 2, default=None)
if ant:
    ligne('ANTOINE', ant)
bains = sorted(k for k in ok if ok[k]['mob'] == 'bain_oiseaux')
if bains:
    ligne('BAIN', bains[0])
especes = sorted({v['mob'] for v in ok.values()} - {'bain_oiseaux'})
for m in especes:
    k = max((k for k in ok if ok[k]['mob'] == m), key=lambda k: (ok[k].get('nombre', 1), k))
    ligne('ESPECE', k)
PY
cat lieux.txt
rm -f console && mkfifo console
exec 3<>console
demarrer() {
  local avant
  avant=$(grep -c 'Done (' journal.txt 2>/dev/null || true)
  java -Xmx3G -jar paper.jar --nogui < console >> journal.txt 2>&1 &
  SERVEUR=$!
  for _ in $(seq 1 400); do [ "$(grep -c 'Done (' journal.txt 2>/dev/null || true)" -gt "${avant:-0}" ] && break; sleep 1; done
}
cmd() { echo "$(date -u +%T) >>> $1" >> commandes.txt; echo "$1" >&3; }
note() { echo "$(date -u +%T) == $1" >> commandes.txt; }
releve() { # releve <début du nom> <fois> <écart>
  for _ in $(seq 1 "$2"); do cmd "vmob info $1 Testeur"; sleep "$3"; done
}
aller() { cmd "tp Testeur $1 $2 $3"; }
plus() { python3 -c "print(round($1 + $2, 1))"; }
connecter() { # connecter <fichier> <durée en s>
  node "$OUTILS/essai-orario-bot.mjs" "$2" > "$1" 2>&1 &
  BOT=$!
  for _ in $(seq 1 60); do grep -q 'BOT CONNECTE' "$1" && break; sleep 1; done
  cmd "gamemode creative Testeur"
  cmd "time set 1000"
  cmd "gamerule doDaylightCycle false"
}
pigeon_vise() { # position (x+1.5, y+1, z) d'un pigeon posé de la place $1, lue dans le relevé qui vient d'être fait
  python3 - "$1" <<'PY'
import re, sys
t = open('journal.txt', errors='replace').read()
dernier = t.split('mob(s) de Vorace charg')[-1]
p = [m.groups() for m in re.finditer(r'mob=pigeon action=(marche|picore|pose) .*?apparition=' + re.escape(sys.argv[1]) + r' .*?pos=([-0-9.]+),([-0-9.]+),([-0-9.]+)', dernier)]
if p:
    a, x, y, z = p[0]
    print(float(x) + 1.5, float(y) + 1, z)
PY
}

: > journal.txt
demarrer
connecter bot.txt 3000

# 1. deux places à pigeons
for P in PIGEONS1 PIGEONS2; do
  read -r _ ID MOB X Y Z N <<< "$(grep "^$P " lieux.txt)" || continue
  [ -n "${ID:-}" ] || continue
  note "$P $ID ($N pigeons) à $X $Z"
  aller "$(plus "$X" 12)" "$(plus "$Y" 2)" "$Z"
  sleep 20
  releve pigeon 4 5
  releve pigeon 1 1
  CIBLE=$(pigeon_vise "$ID")
  [ -n "$CIBLE" ] || CIBLE="$X $(plus "$Y" 1) $Z"
  note "pigeon visé ($ID) : $CIBLE"
  aller $CIBLE
  sleep 1
  releve pigeon 3 1
  aller "$(plus "$X" 40)" "$(plus "$Y" 2)" "$Z"
  sleep 4
done

# 1 bis. la place de la capture d'Antoine (pigeon immobile en 245 64 229) : 60 s d'observation, relevé toutes les 5 s
read -r _ ID MOB X Y Z N <<< "$(grep '^ANTOINE ' lieux.txt)"
if [ -n "${ID:-}" ]; then
  note "ANTOINE $ID ($N pigeons) à $X $Z : joueur à 14 blocs, immobile"
  aller "$(plus "$X" 14)" "$(plus "$Y" 2)" "$Z"
  sleep 3
  releve pigeon 13 5
fi

# 2. bain d'oiseaux : présents de loin, partis à 1,5 bloc, relevé toutes les 2 s après le départ du joueur
read -r _ ID MOB X Y Z N <<< "$(grep '^BAIN ' lieux.txt)"
note "BAIN $ID à $X $Z"
aller "$(plus "$X" 9)" "$(plus "$Y" 2)" "$Z"
sleep 15
releve bain 2 3
aller "$(plus "$X" 1.5)" "$(plus "$Y" 1)" "$Z"
sleep 2
releve bain 2 2
aller "$(plus "$X" 12)" "$(plus "$Y" 2)" "$Z"
note "BAIN joueur reparti"
releve bain 14 2

# 3. chaque espèce : le joueur à 10 blocs, 12 s pour l'apparition, puis 5 relevés toutes les 4 s
while read -r QUOI ID MOB X Y Z N; do
  [ "$QUOI" = ESPECE ] || continue
  note "ESPECE $MOB $ID ($N) à $X $Y $Z"
  aller "$(plus "$X" 10)" "$(plus "$Y" 3)" "$Z"
  sleep 12
  releve "$MOB" 9 4
done < lieux.txt
kill $BOT 2>/dev/null; sleep 2

# 4. redémarrage : les animaux enregistrés dans le monde reprennent leur cerveau
cmd "stop"
for _ in $(seq 1 90); do kill -0 $SERVEUR 2>/dev/null || break; sleep 1; done
echo "===== REDEMARRAGE" >> journal.txt
note "REDEMARRAGE"
demarrer
connecter bot2.txt 600
for M in mouton vache cerf chat_ville pigeon papillons lapin; do
  read -r _ ID MOB X Y Z N <<< "$(grep "^ESPECE [^ ]* $M " lieux.txt | head -1)" || continue
  [ -n "${ID:-}" ] || continue
  note "APRES REDEMARRAGE $MOB $ID à $X $Z"
  aller "$(plus "$X" 10)" "$(plus "$Y" 3)" "$Z"
  sleep 12
  releve "$MOB" 4 4
done
kill $BOT 2>/dev/null
cmd "stop"
for _ in $(seq 1 90); do kill -0 $SERVEUR 2>/dev/null || break; sleep 1; done

echo "===== Faux joueur"; cat bot.txt bot2.txt | grep -v '^\s*at \|^Error:' | head -40
echo "===== Commandes"; cat commandes.txt
echo "===== Mobs (console)"; grep -aE 'mob=|[0-9]+ mob\(s\)|REDEMARRAGE|Aucun mob' journal.txt
echo "===== Erreurs"; grep -aiE 'exception|at fr\.vorace|Mobs : erreur' journal.txt | head -20
echo "===== Bilan"
python3 - <<'PY'
import re, collections, math
j = open('journal.txt', errors='replace').read().split('===== REDEMARRAGE')
lignes = [j[0], j[1] if len(j) > 1 else '']
cmds = open('commandes.txt').read()
def secs(h):
    a, b, c = map(int, h.split(':')); return a * 3600 + b * 60 + c
def relevés(t):
    """[(heure, [(mob, action, parcouru, apparition, pos)])] pour chaque relevé"""
    res = []
    for bloc in re.split(r'(?=\[\d\d:\d\d:\d\d INFO\]: Vorace » \d+ mob\(s\) de Vorace charg)', t):
        m = re.match(r'\[(\d\d:\d\d:\d\d)', bloc)
        if not m: continue
        res.append((m.group(1), re.findall(r'mob=(\S+) action=(\S+) .*?apparition=(\S+) parcouru=([0-9.]+)', bloc)))
    return res
def individus(t):
    """{id: [(secondes, mob, action, parcouru, (x, z))]}"""
    r = collections.defaultdict(list)
    for h, bloc in [(m.group(1), m.group(0)) for m in re.finditer(r'\[(\d\d:\d\d:\d\d) INFO\]: Vorace » id=\S+ mob=[^\n]*', t)]:
        m = re.search(r'id=(\S+) mob=(\S+) action=(\S+) .*?parcouru=([0-9.]+).*?pos=([-0-9.]+),[-0-9.]+,([-0-9.]+)', bloc)
        if m:
            r[m.group(1)].append((secs(h), m.group(2), m.group(3), float(m.group(4)), (float(m.group(5)), float(m.group(6)))))
    return r
def resume(t):
    r = collections.defaultdict(list)
    for _, lst in relevés(t):
        for mob, act, app, d in lst: r[mob].append((act, float(d)))
    return r
avant, apres = resume(lignes[0]), resume(lignes[1])
ok = True
def observe(t):
    """par espèce : [(trajet pendant l'observation, déplacement net, durée, part des relevés en mouvement)] par individu"""
    r = collections.defaultdict(list)
    for i, obs in individus(t).items():
        obs.sort()
        if len(obs) < 2: continue
        traj = sum(math.dist(a[4], b[4]) for a, b in zip(obs, obs[1:]))
        net = math.dist(obs[0][4], obs[-1][4])
        bouge = sum(1 for a, b in zip(obs, obs[1:]) if math.dist(a[4], b[4]) > 0.3) / (len(obs) - 1)
        r[obs[0][1]].append((traj, net, obs[-1][0] - obs[0][0], bouge))
    return r
print('espèce : actions vues ; PENDANT L\'OBSERVATION, par individu : trajet relevé (positions successives), part des relevés en')
print('mouvement ; le meilleur et le moins mobile ; nombre d\'individus')
for phase, t, res in (('avant', lignes[0], avant), ('après redémarrage', lignes[1], apres)):
    if phase != 'avant': print('après redémarrage :')
    ob = observe(t)
    for k, v in sorted(res.items()):
        o = ob.get(k, [])
        if o:
            b = max(o); m = min(o)
            txt = f'meilleur {b[0]:5.1f} blocs en {b[2]:3d} s ({b[3]:.0%} en mouvement), moins mobile {m[0]:5.1f} blocs en {m[2]:3d} s ({m[3]:.0%})'
        else:
            txt = ''
        print(f'  {k:13s} {", ".join(sorted(set(a for a, _ in v))):40s} {txt}  [{len(o)}]')
attendues = [l.split()[2] for l in open('lieux.txt') if l.startswith('ESPECE ')]
manquent = [m for m in attendues if m not in avant]
if manquent: print('ECHEC : espèces jamais relevées :', manquent); ok = False
immobiles = [m for m in attendues if m in avant and max(d for _, d in avant[m]) < 2]
if immobiles: print('ECHEC : espèces immobiles (moins de 2 blocs) :', immobiles); ok = False
if not apres: print('ECHEC : aucun mob relevé après le redémarrage'); ok = False
elif max(d for v in apres.values() for _, d in v) < 2: print('ECHEC : immobiles après redémarrage'); ok = False
# pigeons : nombre par place, volée à l'approche
places = collections.Counter()
for _, lst in relevés(lignes[0]):
    c = collections.Counter(app for mob, _, app, _ in lst if mob == 'pigeon')
    for k, n in c.items(): places[k] = max(places[k], n)
print('pigeons vus en même temps, par place :', {k: v for k, v in sorted(places.items()) if k.startswith('pigeons_place')})
for mv in re.finditer(r'(\d\d:\d\d:\d\d) == pigeon visé \((\S+)\)', cmds):
    h0, place = secs(mv.group(1)), mv.group(2)
    vols = [(h, sum(1 for mob, a, app, _ in lst if app == place and a == 'vole'), sum(1 for mob, a, app, _ in lst if app == place))
            for h, lst in relevés(lignes[0]) if 0 <= secs(h) - h0 <= 5]
    print(f'volée ({place}) : pigeons en vol / relevés juste après l\'arrivée du joueur :', vols)
    if not any(v >= 2 for _, v, _ in vols): print('ECHEC : la volée ne part pas à l\'approche'); ok = False
# bain : présents, partis, retour
mb = re.search(r'(\d\d:\d\d:\d\d) == BAIN joueur reparti', cmds)
etats = [(h, a) for h, lst in relevés(lignes[0]) for mob, a, _, _ in lst if mob == 'bain_oiseaux']
print('bain d\'oiseaux :', etats)
fx = re.findall(r'\[(\d\d:\d\d:\d\d) INFO\]: Vorace » id=\S+ mob=bain_oiseaux action=(\S+) .*?animation=(\S+) sons=(\d+) particules=(\d+)', lignes[0])
if fx:
    print('bain d\'oiseaux, animation jouée par Vorace et effets émis depuis son apparition (heure, état, animation, sons, particules) :', fx)
    if int(fx[-1][4]) == 0: print('ECHEC : le bain d\'oiseaux n\'émet aucune particule'); ok = False
if mb:
    h0 = secs(mb.group(1))
    retour = [secs(h) - h0 for h, a in etats if secs(h) >= h0 and a == 'presents']
    print('bain : retour des oiseaux', (f'{retour[0]} s après le départ du joueur' if retour else 'jamais'))
    if 'partis' not in [a for _, a in etats] or not retour: print('ECHEC : les oiseaux du bain ne partent pas ou ne reviennent pas'); ok = False
# chaque individu : jamais figé (même place, rien parcouru) plus de 15 s, sauf au repos, en train de manger, boire ou dormir
figes = []
for phase, t in (('avant', lignes[0]), ('après redémarrage', lignes[1])):
    ind = individus(t)
    for i, obs in ind.items():
        obs.sort()
        mob = obs[0][1]
        if mob == 'bain_oiseaux': continue
        # suites de relevés consécutifs où il VEUT se déplacer (ni repos, ni repas, ni sommeil) sans bouger d'un pouce
        REPOS = {'repos', 'manger', 'boire', 'dormir', 'pose', 'presents', 'partis'}
        deb = None
        for k in range(len(obs) + 1):
            fin = k == len(obs) or obs[k][2] in REPOS or (deb is not None and (abs(obs[k][3] - obs[deb][3]) > 0.3 or math.dist(obs[k][4], obs[deb][4]) > 0.3))
            if fin:
                if deb is not None and obs[k - 1][0] - obs[deb][0] >= 10:
                    figes.append((phase, mob, i, obs[k - 1][0] - obs[deb][0], sorted({a for _, _, a, _, _ in obs[deb:k]})))
                deb = k if k < len(obs) and obs[k][2] not in REPOS else None
            elif deb is None:
                deb = k
    print(f'individus suivis ({phase}) :', len(ind), '; par espèce :', dict(collections.Counter(o[0][1] for o in ind.values())))
if figes: print('ECHEC : individus qui veulent se déplacer mais restent figés 10 s ou plus (phase, espèce, id, durée, actions) :', figes); ok = False
else: print('aucun individu figé : aucun mob ne reste 10 s sans bouger alors qu\'il veut se déplacer (errer, rejoindre, rentrer, fuir, marcher...)')
mv = re.search(r'(\d\d:\d\d:\d\d) == ANTOINE (\S+)', cmds)
if mv:
    place = mv.group(2)
    ind = individus(lignes[0])
    print(f'place de la capture d\'Antoine ({place}), 60 s : déplacement de chaque pigeon (parcouru pendant l\'observation, actions vues)')
    for i, obs in sorted(ind.items()):
        o = [x for x in obs if x[1] == 'pigeon' and secs(mv.group(1)) <= x[0] <= secs(mv.group(1)) + 75]
        if len(o) >= 3 and re.search(re.escape(i) + r' mob=pigeon [^\n]*apparition=' + re.escape(place), lignes[0]):
            print(f'   {i} : {o[-1][3] - o[0][3]:5.1f} blocs en {o[-1][0] - o[0][0]} s ; {sorted({x[2] for x in o})}')
print('ESSAI ORARIO OK' if ok else 'ESSAI ORARIO EN ECHEC')
raise SystemExit(0 if ok else 1)
PY
