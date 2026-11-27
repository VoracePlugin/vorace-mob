package fr.vorace.mobs;

import fr.vorace.Messages;
import fr.vorace.modeles.Modeles;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Le cerveau de combat des mobs de combat ({@link Combat}) : aggro et table de menace, déplacements (s'approcher,
 * garder ses distances, reculer, tourner autour de la cible en pas chassés, s'écarter des alliés), attaques annoncées
 * (signal, actif, récupération, recharge) calées sur les animations, zones marquées au sol, projectiles en cloche,
 * contrôles (étourdi, enraciné, effrayé, provoqué, aveuglé), phases, enrage et barre de boss, retour au point de départ.
 *
 * <p>Règles de la menace : le premier joueur vu entre dans la table avec 10 ; 1 de menace par dégât ; le plus proche
 * compte × 1,1 ; il ne change de cible que si un autre dépasse la cible actuelle de 10 % (cible à 5 blocs ou moins) ou de
 * 30 % (plus loin) ; Provocation : cible forcée, puis 110 % de la plus haute à la fin ; hors de vue plus de 5 s : −10 % par
 * seconde ; Aveuglement : menace de la cible ÷ 2 ; 8 mobs au plus par joueur (le plus ancien l'oublie) ; mort ou départ :
 * oublié ; plus personne ou trop loin de chez lui : il rentre, sa Vie est rendue.
 */
final class IACombat {

    /** État de combat d'un mob. */
    static final class Etat {
        final Map<UUID, Double> menace = new HashMap<>();
        final Map<UUID, Long> horsVue = new HashMap<>();
        final Map<UUID, Double> degats = new HashMap<>();
        final Map<String, Long> prete = new HashMap<>();
        final List<UUID> invocations = new ArrayList<>();
        final Set<UUID> spectateurs = new HashSet<>();
        UUID cible, provocateur, fuiteSource, cibleAttaque;
        boolean engage, rentre, frappe, auSol, enrage;
        long debut, t0, libreA, immobile, inactif, fuite, provocation, aveugle, finMouvement, prochainChemin, vu;
        Competence enCours;
        Location visee;
        Vector direction;
        int sens = 1, phase, attaques, tirs;
        /** Attaque en cours : centres des zones, marques au sol (modèles), bond et pluie déjà faits. */
        final List<Location> zones = new ArrayList<>();
        final List<Modeles.Instance> marques = new ArrayList<>();
        boolean bondFait, pluieFaite;
        long bondJusqua;
        /** Recul : début de la série en cours, dernier tic de recul, essoufflé jusqu'à ce tic. */
        long reculDepuis, reculDernier, essouffle;
        /** Tic où jouer l'animation de l'attaque en cours quand elle est retardée (« animation-frappe ») ; 0 : déjà jouée. */
        long animA;
        /** Tics des derniers coups reçus (pressé : 3 coups en 2 s). */
        final java.util.ArrayDeque<Long> coups = new java.util.ArrayDeque<>();
        String mode = "garde", anim;
        double vitessePhase = 1, bonus = 1;
        BossBar barre;
        /** Taille du groupe ({@code vie-groupe}, {@code degats-groupe}) : joueurs verrouillés dans ce combat. */
        final Set<UUID> verrouilles = new HashSet<>();
        /** Garde de face ({@link Mecaniques.Garde}) : coups gardés d'affilée, brisée jusqu'à ce tic, brisée au moins une fois. */
        int coupsGardes;
        long gardeBriseeJusqua;
        boolean gardeBrisee;
        /** Nombre de joueurs imposé par un autre plugin (arène d'un boss), 0 : compté par vorace-mob. */
        int joueursImposes;
        /** Conditions notées pendant le combat (butin {@code si:}) : nom → nombre de fois ({@code garde_brisee}...). */
        final Map<String, Integer> conditions = new HashMap<>();
        /** Soins et protections apportés par chaque joueur pendant ce combat ({@link VoraceMobAPI#contribuer}). */
        final Map<UUID, Double> soutiens = new HashMap<>();
        /** Munitions restantes ({@code combat.munitions}) ; −1 : pas encore comptées. Second coup de l'attaque fait. */
        int munitions = -1;
        boolean secondFait;
        /** Dépôt où il va recharger (null : sur place ou pas encore cherché) ; dépôt cherché. */
        Location depot;
        boolean depotCherche;
        /** Tic du retour au calme (tertre : regain après une attente). */
        long calmeDepuis;
        /** Mobs invoqués par ses attaques pendant ce combat ; purifié (aura éteinte) jusqu'à ce tic. */
        int invoques;
        long purifieJusqua;

        /** Joueurs comptés pour la Vie et les dégâts (1 au moins). */
        int joueurs() { return Math.max(1, Math.max(joueursImposes, verrouilles.size())); }
    }

    /** Un projectile en vol, avec son modèle et la marque de son point d'impact. */
    private static final class Projectile {
        final Mobs.Suivi s;
        final Competence c;
        final Vector depart, arrivee;
        final double hauteur;
        final int duree;
        int t;
        Modeles.Instance fx, marque;

        Projectile(Mobs.Suivi s, Competence c, Vector depart, Vector arrivee, double hauteur, int duree) {
            this.s = s; this.c = c; this.depart = depart; this.arrivee = arrivee; this.hauteur = hauteur; this.duree = duree;
        }

        Vector position(double f) {
            Vector p = depart.clone().multiply(1 - f).add(arrivee.clone().multiply(f));
            return p.setY(p.getY() + 4 * hauteur * f * (1 - f));
        }
    }

    /** Une flaque laissée par un impact : elle brûle ceux qui restent dedans (un coup toutes les 10 tics). */
    private static final class Flaque {
        final Mobs.Suivi s;
        final Competence c;
        final Location centre;
        final long fin;
        final Map<UUID, Long> touches = new HashMap<>();

        Flaque(Mobs.Suivi s, Competence c, Location centre, long fin) {
            this.s = s; this.c = c; this.centre = centre; this.fin = fin;
        }
    }

    /** Noms d'animations par défaut des rôles de combat (changeables dans {@code animations:} du mob). */
    static final Map<String, String> ANIMS = Map.of("garde", "idle_combat", "marche-combat", "walk_combat", "course", "run",
        "recul", "walk_back", "droite", "strafe_right", "gauche", "strafe_left", "fuite", "flee", "alerte", "alert", "releve", "get_up");

    private final VoraceMob plugin;
    private final Mobs mobs;
    private final Random hasard = new Random();
    /** joueur → mobs engagés sur lui, du plus ancien au plus récent (8 au plus). */
    private final Map<UUID, LinkedHashSet<UUID>> parJoueur = new HashMap<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final List<Flaque> flaques = new ArrayList<>();
    /** joueur en combat → {x, z, vx, vz} : son allure lissée, pour viser où il va. */
    private final Map<UUID, double[]> allures = new HashMap<>();
    /** joueur → {tic à partir duquel un mob du groupe peut l'attaquer, tic à partir duquel une attaque lourde peut partir}. */
    private final Map<UUID, long[]> rythme = new HashMap<>();
    private final Set<String> boucles = new HashSet<>();

    IACombat(VoraceMob plugin, Mobs mobs) {
        this.plugin = plugin;
        this.mobs = mobs;
        boucles.addAll(ANIMS.values());
        for (String r : List.of("repos", "marche", "course")) boucles.addAll(Modeles.AUTO.getOrDefault(r, List.of()));
    }

    private long tic() { return mobs.tics(); }

    /** Joueurs touchés par un mob : tic du coup. Le recul du jeu de ce coup (et son paquet de vitesse) est annulé. */
    private final Map<UUID, Integer> sansRecul = new HashMap<>();

    /** Vrai si ce joueur vient d'être touché par un mob (ce tic ou le précédent) : le recul du jeu ne s'applique pas. */
    boolean sansRecul(Entity e) {
        Integer t = sansRecul.get(e.getUniqueId());
        if (t == null) return false;
        if (Bukkit.getCurrentTick() - t > 1) { sansRecul.remove(e.getUniqueId()); return false; }
        return true;
    }

    // ================================================================== menace

    static boolean valide(Player p, Entity mob) {
        return p != null && p.isOnline() && !p.isDead() && p.getWorld().equals(mob.getWorld())
            && p.getGameMode() != GameMode.SPECTATOR && p.getGameMode() != GameMode.CREATIVE;
    }

    /** Cible valable pour ce mob : {@link #valide} et hors d'une zone sûre (elle n'est ni visée ni touchée). */
    boolean cibleValide(Player p, Entity mob) {
        return valide(p, mob) && !mobs.zoneSure(p.getLocation());
    }

    /** Ajoute de la menace (et engage le mob s'il ne l'était pas). */
    void ajouterMenace(Mobs.Suivi s, Player p, double montant) {
        if (s.ia == null || s.ia.rentre || !cibleValide(p, s.e) || s.vie <= 0) return;
        boolean nouveau = !s.ia.menace.containsKey(p.getUniqueId());
        s.ia.menace.merge(p.getUniqueId(), Math.max(0, montant), Double::sum);
        if (nouveau) inscrire(s, p);
        if (!s.ia.engage) engager(s, p);
    }

    /** Le joueur prend ce mob dans sa liste ; au-delà de 8, le plus ancien l'oublie. */
    private void inscrire(Mobs.Suivi s, Player p) {
        LinkedHashSet<UUID> l = parJoueur.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashSet<>());
        if (l.contains(s.e.getUniqueId())) return;
        while (l.size() >= 8) {
            UUID vieux = l.iterator().next();
            l.remove(vieux);
            Mobs.Suivi o = mobs.suivi(Bukkit.getEntity(vieux));
            if (o != null && o.ia != null) {
                o.ia.menace.remove(p.getUniqueId());
                if (p.getUniqueId().equals(o.ia.cible)) o.ia.cible = null;
            }
        }
        l.add(s.e.getUniqueId());
    }

    private void desinscrire(Mobs.Suivi s) {
        for (UUID j : s.ia.menace.keySet()) {
            LinkedHashSet<UUID> l = parJoueur.get(j);
            if (l != null) { l.remove(s.e.getUniqueId()); if (l.isEmpty()) parJoueur.remove(j); }
        }
    }

    /** Un joueur meurt ou part : tous les mobs l'oublient. */
    void oublier(Player p) {
        LinkedHashSet<UUID> l = parJoueur.remove(p.getUniqueId());
        if (l == null) return;
        for (UUID m : l) {
            Mobs.Suivi o = mobs.suivi(Bukkit.getEntity(m));
            if (o != null && o.ia != null) {
                o.ia.menace.remove(p.getUniqueId());
                if (p.getUniqueId().equals(o.ia.cible)) o.ia.cible = null;
            }
        }
    }

    /** Entre en combat : petit sursaut d'alerte, les mobs du même type autour le rejoignent. */
    private void engager(Mobs.Suivi s, Player p) {
        Etat e = s.ia;
        e.engage = true;
        e.debut = tic();
        e.cible = p.getUniqueId();
        e.vu = tic();
        s.dernierCombat = System.currentTimeMillis();
        Modeles.Instance m = modele(s);
        String alerte = anim(s, m, "alerte");
        Mecaniques.Tertre tertre = s.d.combat().meca().tertre();
        String lever = tertre != null && tertre.assis() ? anim(s, m, "se-lever") : null;
        if (m != null && lever == null && m.animation() != null && m.animation().equals(s.d.animation("assis"))) m.arreterAnimation();
        if (lever != null) { m.jouer(lever, false); e.libreA = tic() + Math.max(1, tertre.lever()); e.immobile = Math.max(e.immobile, e.libreA); }
        else if (alerte != null) { m.jouer(alerte, false); e.libreA = tic() + 14; }
        else e.libreA = tic() + 8;
        Mecaniques.Munitions mu = s.d.combat().meca().munitions();
        e.munitions = mu == null ? -1 : mu.nombre();
        if (s.e instanceof Mob mob) { mob.getPathfinder().stopPathfinding(); regarder(mob, p.getLocation()); }
        verrouiller(s, p);
        Apparition app = mobs.apparition(s.apparition);
        double r = app != null && app.entraide() >= 0 ? app.entraide() : s.d.combat().ia().entraide();
        if (r > 0) {
            for (Mobs.Suivi o : mobs.suivis()) {
                if (o == s || o.ia == null || o.ia.engage || o.ia.rentre || !o.d.combattant() || !o.e.isValid()) continue;
                if (!o.d.id().equals(s.d.id()) && (s.troupeau == null || !s.troupeau.equals(o.troupeau))) continue;
                if (!o.e.getWorld().equals(s.e.getWorld()) || o.e.getLocation().distanceSquared(s.e.getLocation()) > r * r) continue;
                ajouterMenace(o, p, 5);
            }
        }
    }

    /**
     * Taille du groupe, à l'engagement : le joueur qui l'engage et tous les joueurs à {@link Combat#RAYON_GROUPE} blocs
     * sont verrouillés ; la Vie maximale suit ({@link Combats#ajusterGroupe}).
     */
    private void verrouiller(Mobs.Suivi s, Player p) {
        Combat c = s.d.combat();
        if (!c.suitLeGroupe()) return;
        s.ia.verrouilles.add(p.getUniqueId());
        double r2 = Combat.RAYON_GROUPE * Combat.RAYON_GROUPE;
        for (Player j : s.e.getWorld().getPlayers()) {
            if (cibleValide(j, s.e) && j.getLocation().distanceSquared(s.e.getLocation()) <= r2) s.ia.verrouilles.add(j.getUniqueId());
        }
        mobs.combats().ajusterGroupe(s);
    }

    /** Un nouveau joueur qui le frappe en plein combat rejoint le groupe compté (+1 joueur). */
    private void rejoindre(Mobs.Suivi s, Player p) {
        if (!s.ia.engage || !s.d.combat().suitLeGroupe() || !s.ia.verrouilles.add(p.getUniqueId())) return;
        mobs.combats().ajusterGroupe(s);
    }

    /** Un autre plugin impose le nombre de joueurs (arène d'un boss) ; 0 rend le comptage à vorace-mob. */
    void imposerJoueurs(Mobs.Suivi s, int joueurs) {
        if (s.ia == null) return;
        s.ia.joueursImposes = Math.max(0, joueurs);
        mobs.combats().ajusterGroupe(s);
    }

    /** Fin du combat : il oublie tout, rentre chez lui et récupère sa Vie. */
    void rentrer(Mobs.Suivi s) {
        Etat e = s.ia;
        desinscrire(s);
        e.menace.clear(); e.horsVue.clear(); e.degats.clear(); e.prete.clear();
        e.cible = null; e.provocateur = null; e.enCours = null; e.engage = false; e.rentre = true; e.phase = 0;
        e.vitessePhase = 1; e.bonus = 1; e.enrage = false;
        e.verrouilles.clear(); e.joueursImposes = 0;
        e.coupsGardes = 0; e.gardeBriseeJusqua = 0; e.gardeBrisee = false;
        e.conditions.clear(); e.soutiens.clear();
        e.munitions = -1; e.depot = null; e.depotCherche = false; e.secondFait = false; e.invoques = 0;
        e.calmeDepuis = tic();
        mobs.combats().ajusterGroupe(s);   // retour à 1 joueur
        cacherBarre(s);
        retirerMarques(e);
        e.bondJusqua = 0;
        e.coups.clear();
        for (UUID u : e.invocations) { Entity x = Bukkit.getEntity(u); if (x != null && x.isValid()) x.remove(); }
        e.invocations.clear();
        // tertre (Gardien) : il ne récupère pas tout en rentrant, seulement peu à peu au calme (Comportements)
        if (s.d.combat().meca().tertre() == null) mobs.combats().soigner(s, s.vieMax);
        e.prochainChemin = 0;
        e.debut = tic();
    }

    /** Note une condition du butin ({@code garde_brisee}, {@code par_branche_coupee}...) : {@code fois} de plus. */
    void noter(Mobs.Suivi s, String condition, int fois) {
        if (s.ia == null || condition == null || fois <= 0) return;
        s.ia.conditions.merge(condition.toLowerCase(java.util.Locale.ROOT).trim(), fois, Integer::sum);
    }

    /** Soins ou protection apportés par un joueur dans ce combat (part du butin des élites et des boss). */
    void contribuer(Mobs.Suivi s, Player p, double montant) {
        if (s.ia == null || p == null || montant <= 0 || !s.ia.engage) return;
        s.ia.soutiens.merge(p.getUniqueId(), montant, Double::sum);
    }

    /** Toutes les 10 tics, hors combat : le premier joueur vu à sa distance d'aggro devient sa cible. */
    boolean penser(Mobs.Suivi s) {
        if (s.ia == null) return false;
        if (s.ia.engage || s.ia.rentre) return true;
        if (!(s.e instanceof Mob mob) || s.vie <= 0) return false;
        if (s.d.neutre()) return false;   // neutre : il n'attaque que si on le frappe
        double r = s.d.combat().ia().aggro();
        Player meilleur = null;
        double best = r * r;
        Location l = s.e.getLocation();
        for (Player p : s.e.getWorld().getPlayers()) {
            if (!cibleValide(p, s.e) || p.isInvisible()) continue;
            double d = p.getLocation().distanceSquared(l);
            if (d < best && mob.hasLineOfSight(p)) { best = d; meilleur = p; }
        }
        if (meilleur == null) return false;
        ajouterMenace(s, meilleur, 10);
        return true;
    }

    /** Recalcule la cible (toutes les 10 tics en combat). */
    private Player choisirCible(Mobs.Suivi s, Mob mob) {
        Etat e = s.ia;
        long t = tic();
        Location l = mob.getLocation();
        // nettoyage et perte de vue
        Iterator<Map.Entry<UUID, Double>> it = e.menace.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Double> x = it.next();
            Player p = Bukkit.getPlayer(x.getKey());
            if (!cibleValide(p, mob) || p.getLocation().distanceSquared(l) > Math.pow(s.d.combat().ia().laisse() * 2, 2)) {
                it.remove();
                LinkedHashSet<UUID> lj = parJoueur.get(x.getKey());
                if (lj != null) lj.remove(mob.getUniqueId());
                continue;
            }
            if (mob.hasLineOfSight(p)) e.horsVue.remove(x.getKey());
            else {
                long depuis = e.horsVue.computeIfAbsent(x.getKey(), k -> t);
                if (t - depuis > 100) x.setValue(x.getValue() * Math.pow(0.9, 0.5));  // −10 % par seconde (pas de 0,5 s)
            }
        }
        if (e.menace.isEmpty()) return null;
        // Provocation : cible forcée
        if (e.provocateur != null) {
            Player p = Bukkit.getPlayer(e.provocateur);
            if (t < e.provocation && cibleValide(p, mob)) { e.cible = p.getUniqueId(); return p; }
            if (p != null && e.menace.containsKey(p.getUniqueId())) {
                double max = e.menace.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
                e.menace.put(p.getUniqueId(), max * 1.1);
            }
            e.provocateur = null;
        }
        UUID proche = null;
        double dp = Double.MAX_VALUE;
        for (UUID u : e.menace.keySet()) {
            Player p = Bukkit.getPlayer(u);
            double d = p.getLocation().distanceSquared(l);
            if (d < dp) { dp = d; proche = u; }
        }
        UUID meilleur = null;
        double best = -1;
        for (Map.Entry<UUID, Double> x : e.menace.entrySet()) {
            double v = x.getValue() * (x.getKey().equals(proche) ? 1.1 : 1);
            if (v > best) { best = v; meilleur = x.getKey(); }
        }
        Player actuelle = e.cible == null ? null : Bukkit.getPlayer(e.cible);
        if (actuelle != null && e.menace.containsKey(e.cible) && !e.cible.equals(meilleur)) {
            double va = e.menace.get(e.cible) * (e.cible.equals(proche) ? 1.1 : 1);
            double seuil = actuelle.getLocation().distanceSquared(l) <= 25 ? 1.1 : 1.3;
            if (best < va * seuil) meilleur = e.cible;
        }
        e.cible = meilleur;
        return Bukkit.getPlayer(meilleur);
    }

    // ================================================================== garde et point faible (bestiaire)

    /**
     * Dégâts d'un coup reçu de {@code source} après la garde de face et le point faible du mob ({@code combat.garde},
     * {@code combat.point-faible}). Un coup gardé compte pour briser la garde ; un coup dans le dos peut le figer.
     */
    double garde(Mobs.Suivi s, double montant, Player source) {
        Mecaniques m = s.d.combat().meca();
        if (s.ia == null || source == null || (m.garde() == null && m.pointFaible() == null)) return montant;
        Location ml = s.e.getLocation(), pl = source.getLocation();
        double angle = Mecaniques.angle(regardMob(s), pl.getX() - ml.getX(), pl.getZ() - ml.getZ());
        if (m.dos(angle)) {
            if (m.pointFaible().fige() > 0) entraver(s, m.pointFaible().fige() * 50L, false, false);
            return montant * m.pointFaible().multiplicateur();
        }
        if (!m.devant(angle) || tic() < s.ia.gardeBriseeJusqua) return montant;
        Mecaniques.Garde g = m.garde();
        s.ia.coupsGardes++;
        s.e.getWorld().playSound(ml, Sound.ITEM_SHIELD_BLOCK, 0.8f, 1f);
        if (g.briseApres() > 0 && s.ia.coupsGardes >= g.briseApres()) {
            s.ia.coupsGardes = 0;
            s.ia.gardeBrisee = true;
            noter(s, "garde_brisee", 1);
            s.ia.gardeBriseeJusqua = tic() + g.ouverture();
            s.e.getWorld().playSound(ml, Sound.ITEM_SHIELD_BREAK, 1f, 1f);
            Modeles.Instance mi = modele(s);
            if (g.animation() != null && mi != null && mi.modele().animations().containsKey(g.animation())) mi.jouer(g.animation(), false);
            if (g.ouverture() > 0) entraver(s, g.ouverture() * 50L, false, false);
        }
        return montant * (1 - g.reduction());
    }

    /** Où regarde le mob : son modèle s'il en a un (il tourne seul), sinon l'entité. */
    private float regardMob(Mobs.Suivi s) {
        return s.e.getLocation().getYaw();
    }

    // ================================================================== contrôles (plugin du combat)

    void entraver(Mobs.Suivi s, long ms, boolean bouger, boolean agir) {
        if (s.ia == null) return;
        long fin = tic() + Math.max(1, ms / 50);
        if (!bouger) s.ia.immobile = Math.max(s.ia.immobile, fin);
        if (!agir) {
            s.ia.inactif = Math.max(s.ia.inactif, fin);
            Competence c = s.ia.enCours;
            if (c != null && c.interruptible() && tic() - s.ia.t0 < c.signal()) {
                // interrompue pendant le signal : elle ne part pas, sa recharge repart à moitié
                s.ia.prete.put(c.id(), tic() + c.recharge() / 2);
                s.ia.enCours = null;
                retirerMarques(s.ia);
                Modeles.Instance m = modele(s);
                if (m != null && c.animation() != null && c.animation().equals(m.animation())) m.arreterAnimation();
            }
        }
        if (!bouger && s.e instanceof Mob mob) { mob.getPathfinder().stopPathfinding(); arreter(mob); }
    }

    void provoquer(Mobs.Suivi s, Player p, long ms) {
        if (s.ia == null || !cibleValide(p, s.e)) return;
        ajouterMenace(s, p, 0);
        s.ia.provocateur = p.getUniqueId();
        s.ia.provocation = tic() + Math.max(1, ms / 50);
        s.ia.cible = p.getUniqueId();
    }

    void aveugler(Mobs.Suivi s, long ms) {
        if (s.ia == null) return;
        s.ia.aveugle = tic() + Math.max(1, ms / 50);
        if (s.ia.cible != null) s.ia.menace.computeIfPresent(s.ia.cible, (k, v) -> v / 2);
        s.ia.cible = null;
    }

    void fuir(Mobs.Suivi s, Entity source, long ms) {
        if (s.ia == null) return;
        s.ia.fuite = tic() + Math.max(1, ms / 50);
        s.ia.fuiteSource = source == null ? null : source.getUniqueId();
        s.ia.enCours = null;
    }

    // ================================================================== chaque tic

    void tic(Mobs.Suivi s) {
        Etat e = s.ia;
        if (e == null || (!e.engage && !e.rentre) || !(s.e instanceof Mob mob) || !mob.isValid() || mob.isDead() || s.vie <= 0) return;
        long t = tic();
        Modeles.Instance m = modele(s);
        Combat cb = s.d.combat();

        if (e.rentre) {
            Location maison = new Location(mob.getWorld(), s.mx, s.my, s.mz);
            if (mob.getLocation().distanceSquared(maison) < 4 || t - e.debut > 20 * 60) {
                e.rentre = false;
                mob.getPathfinder().stopPathfinding();
                jouerBoucle(s, m, null);
            } else {
                if (t >= e.prochainChemin) { mob.getPathfinder().moveTo(maison, 1.3 * cb.ia().vitesse()); e.prochainChemin = t + 20; }
                jouerBoucle(s, m, "course");
            }
            return;
        }

        // cible, laisse, phases
        Player cible = e.cible == null ? null : Bukkit.getPlayer(e.cible);
        if (t % 10 == 0 || !cibleValide(cible, mob)) cible = choisirCible(s, mob);
        double maisonD = Geometrie.distance(mob.getLocation().getX(), mob.getLocation().getZ(), s.mx, s.mz);
        if (cible == null || maisonD > cb.ia().laisse()) { rentrer(s); return; }
        if (t < e.aveugle && e.enCours == null) cible = null;   // aveuglé : il cherche
        if (t % 5 == 0) { phases(s, mob, m); barre(s, mob); }
        if (cb.enrage() > 0 && !e.enrage && t - e.debut > cb.enrage() * 20L) {
            e.enrage = true;
            e.bonus *= 1.5;
            e.vitessePhase *= 1.2;
            annoncer(s, "<dark_red>" + nom(s) + " entre en rage !");
        }
        s.dernierCombat = System.currentTimeMillis();

        // réactions aux compétences des joueurs (saisi, plaqué...) : il les subit, puis se relève
        String cur = m == null ? null : m.animation();
        String reaction = reaction(cur);
        if (reaction != null) {
            if (reaction.equals("plaque") || reaction.equals("projete")) e.auSol = true;
            if (!e.auSol || t < e.immobile) { arreter(mob); return; }
        }
        if (e.auSol && t >= e.immobile) {
            e.auSol = false;
            String rel = anim(s, m, "releve");
            if (rel != null) { m.jouer(rel, false); e.libreA = Math.max(e.libreA, t + 22); }
        }

        // effrayé : il fuit
        if (t < e.fuite) {
            Entity src = e.fuiteSource == null ? null : Bukkit.getEntity(e.fuiteSource);
            if (src == null) src = cible;
            if (src == null) { arreter(mob); return; }
            Vector loin = mob.getLocation().toVector().subtract(src.getLocation().toVector()).setY(0);
            if (loin.lengthSquared() < 1e-4) loin = new Vector(1, 0, 0);
            loin.normalize();
            if (!deplacer(mob, loin, vitesse(s, mob) * 1.1)) deplacer(mob, new Vector(-loin.getZ(), 0, loin.getX()), vitesse(s, mob));
            regarder(mob, mob.getLocation().add(loin));
            jouerBoucle(s, m, "fuite");
            return;
        }

        // attaque en cours
        if (e.enCours != null) {
            suivreAttaque(s, mob, m, cible);
            return;
        }
        if (t < e.bondJusqua) { if (cible != null) regarder(mob, cible.getLocation()); return; }   // bond en cours : on le laisse filer
        if (t < e.immobile) { arreter(mob); if (cible != null) regarder(mob, cible.getLocation()); jouerBoucle(s, m, "garde"); }
        if (t < e.libreA || cible == null) {
            if (cible != null && t >= e.immobile) regarder(mob, cible.getLocation());
            if (t >= e.immobile && cible != null) bouger(s, mob, m, cible, t);
            return;
        }

        // plus de munitions : il marche à son dépôt (la recharge part une fois arrivé)
        if (e.munitions == 0 && t >= e.immobile && t >= e.inactif && allerAuDepot(s, mob, m, t)) return;

        // nouvelle attaque ?
        if (t >= e.inactif) {
            Competence c = choisirAttaque(s, mob, cible);
            if (c != null) { demarrer(s, mob, m, c, cible); return; }
        }
        if (t >= e.immobile) bouger(s, mob, m, cible, t);
    }

    private static String reaction(String anim) {
        if (anim == null) return null;
        for (String r : Modeles.REACTIONS) if (Modeles.AUTO.getOrDefault(r, List.of()).contains(anim)) return r;
        if (anim.equals("knockdown") || anim.equals("slammed")) return "plaque";
        if (anim.equals("lifted") || anim.equals("launched")) return "souleve";
        return null;
    }

    // ================================================================== déplacements

    /** Vitesse de base en blocs par tic (suit l'attribut de vitesse : un Ralentissement le ralentit). */
    private double vitesse(Mobs.Suivi s, Mob mob) {
        AttributeInstance a = Mobs.attribut(mob, "movement_speed");
        double ratio = 1;
        if (a != null && s.vitesseBase > 0) ratio = Math.max(0.2, Math.min(2, a.getValue() / s.vitesseBase));
        return 0.13 * s.d.combat().ia().vitesse() * s.ia.vitessePhase * ratio;
    }

    private void bouger(Mobs.Suivi s, Mob mob, Modeles.Instance m, Player cible, long t) {
        Etat e = s.ia;
        Combat.IA ia = s.d.combat().ia();
        Location l = mob.getLocation(), c = cible.getLocation();
        double d = Geometrie.distance(l.getX(), l.getZ(), c.getX(), c.getZ());
        boolean vue = t - e.vu < 10 || mob.hasLineOfSight(cible);
        if (vue) e.vu = t;
        double min = ia.distMin(), max = ia.distMax();
        if (!ia.distance()) {
            Competence b = s.d.combat().coupDeBase();
            max = b != null ? Math.max(1.6, b.porteeMax() - 0.4) : 2;
            min = 0;
        }
        Vector versCible = c.toVector().subtract(l.toVector()).setY(0);
        if (versCible.lengthSquared() < 1e-4) versCible = new Vector(0, 0, 1);
        versCible.normalize();
        Vector separation = separation(s, mob);

        if (d > max + 0.5 || !vue) {
            // trop loin ou cible cachée : il se rapproche par le chemin
            if (t >= e.prochainChemin || !mob.getPathfinder().hasPath()) {
                mob.getPathfinder().moveTo(cible, (d > max + 8 ? 1.35 : 1.0) * ia.vitesse() * e.vitessePhase);
                e.prochainChemin = t + 10;
            }
            e.mode = "approche";
            jouerBoucle(s, m, d > max + 8 ? "course" : "marche-combat");
            return;
        }
        mob.getPathfinder().stopPathfinding();
        regarder(mob, c);
        if (ia.distance() && d < min - 0.3 && t < e.essouffle) {
            // essoufflé : il ne recule plus, il tient sa place face à sa cible (c'est le moment de le frapper)
            arreter(mob);
            e.mode = "essouffle";
            jouerBoucle(s, m, "garde");
            return;
        }
        if (ia.distance() && d < min - 0.3) {
            // trop près : il recule face à la cible, ou se décale si un mur le bloque ; au bout de son souffle, il s'arrête
            if (t - e.reculDernier > 20) e.reculDepuis = t;
            e.reculDernier = t;
            if (ia.souffle() > 0 && t - e.reculDepuis >= ia.souffle()) {
                e.essouffle = t + ia.souffle() * 3L / 2;
                e.reculDernier = 0;
                arreter(mob);
                return;
            }
            Vector recul = versCible.clone().multiply(-1).add(separation).normalize();
            if (deplacer(mob, recul, vitesse(s, mob) * ia.recul())) { e.mode = "recul"; jouerBoucle(s, m, "recul"); return; }
            Vector cote = new Vector(-versCible.getZ() * e.sens, 0, versCible.getX() * e.sens);
            if (!deplacer(mob, cote, vitesse(s, mob))) { e.sens = -e.sens; arreter(mob); jouerBoucle(s, m, "garde"); return; }
            jouerBoucle(s, m, e.sens > 0 ? "gauche" : "droite");
            return;
        }
        // à bonne distance : il tourne autour de sa cible en pas chassés, avec des pauses
        if (t >= e.finMouvement) {
            boolean tourne = !e.mode.equals("tourne") && hasard.nextDouble() < (ia.distance() ? 0.7 : ia.feinte());
            e.mode = tourne ? "tourne" : "garde";
            if (tourne && hasard.nextDouble() < 0.45) e.sens = -e.sens;
            e.finMouvement = t + (tourne ? 18 + hasard.nextInt(28) : 10 + hasard.nextInt(24));
        }
        if (e.mode.equals("tourne")) {
            Vector cote = new Vector(-versCible.getZ() * e.sens, 0, versCible.getX() * e.sens);
            // correction pour rester au milieu de sa bande de distance
            double milieu = (min + max) / 2;
            Vector dir = cote.clone().add(versCible.clone().multiply(Math.max(-0.6, Math.min(0.6, (d - milieu) * 0.25)))).add(separation);
            if (dir.lengthSquared() > 1e-4 && deplacer(mob, dir.normalize(), vitesse(s, mob))) {
                jouerBoucle(s, m, e.sens > 0 ? "gauche" : "droite");
                return;
            }
            e.sens = -e.sens;
            e.finMouvement = t + 6;
        }
        if (separation.lengthSquared() > 0.01) deplacer(mob, separation.normalize(), vitesse(s, mob) * 0.6);
        else arreter(mob);
        e.mode = "garde";
        jouerBoucle(s, m, "garde");
    }

    /** Pousse un peu loin des alliés trop proches (ils ne s'empilent pas). */
    private Vector separation(Mobs.Suivi s, Mob mob) {
        Vector v = new Vector();
        for (Entity o : mob.getNearbyEntities(1.6, 1, 1.6)) {
            if (!(o instanceof Mob) || mobs.suivi(o) == null) continue;
            Vector x = mob.getLocation().toVector().subtract(o.getLocation().toVector()).setY(0);
            double l = x.length();
            if (l < 1e-3) x = new Vector(hasard.nextDouble() - 0.5, 0, hasard.nextDouble() - 0.5);
            v.add(x.normalize().multiply((1.6 - Math.min(1.6, l)) / 1.6));
        }
        return v;
    }

    /**
     * Fait un pas dans la direction (vitesse en blocs par tic). Saute une marche d'un bloc ; refuse d'avancer dans un mur
     * ou au bord d'un trou de plus de 2 blocs. Faux s'il ne peut pas.
     */
    private boolean deplacer(Mob mob, Vector dir, double vitesse) {
        Location l = mob.getLocation();
        World w = l.getWorld();
        double nx = l.getX() + dir.getX() * 0.75, nz = l.getZ() + dir.getZ() * 0.75;
        int y = l.getBlockY();
        Block pieds = w.getBlockAt((int) Math.floor(nx), y, (int) Math.floor(nz));
        Block tete = pieds.getRelative(0, 1, 0);
        double vy = mob.getVelocity().getY();
        if (!pieds.isPassable() || !tete.isPassable()) {
            Block audessus = tete.getRelative(0, 1, 0);
            if (pieds.isPassable() || !tete.isPassable() || !audessus.isPassable() || !mob.isOnGround()) return false;
            vy = 0.42;   // marche d'un bloc
        } else {
            Block dessous = pieds.getRelative(0, -1, 0);
            if (dessous.isPassable() && dessous.getRelative(0, -1, 0).isPassable() && dessous.getRelative(0, -2, 0).isPassable()) return false;
        }
        mob.setVelocity(new Vector(dir.getX() * vitesse, vy, dir.getZ() * vitesse));
        return true;
    }

    private static void arreter(Mob mob) {
        Vector v = mob.getVelocity();
        if (Math.abs(v.getX()) > 0.01 || Math.abs(v.getZ()) > 0.01) mob.setVelocity(new Vector(v.getX() * 0.3, v.getY(), v.getZ() * 0.3));
    }

    static void regarder(Mob mob, Location ou) {
        Location l = mob.getLocation();
        double dx = ou.getX() - l.getX(), dz = ou.getZ() - l.getZ();
        if (dx * dx + dz * dz < 1e-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mob.setRotation(yaw, 0);
        try { mob.setBodyYaw(yaw); } catch (Throwable ignore) { }
        mob.lookAt(ou.getX(), ou.getY() + 1.4, ou.getZ());
    }

    // ================================================================== animations

    private Modeles.Instance modele(Mobs.Suivi s) {
        return plugin.modeles() == null ? null : plugin.modeles().instance(s.e);
    }

    /** Nom de l'animation d'un rôle de combat (garde, recul...) si le modèle l'a, sinon null. */
    private String anim(Mobs.Suivi s, Modeles.Instance m, String role) {
        if (m == null) return null;
        String n = s.ia == null ? null : animDePhase(s.d.combat(), s.ia.phase, role);
        if (n != null && m.modele().animations().containsKey(n)) return n;
        n = s.d.animation(role);
        if (n == null) n = ANIMS.get(role);
        return n != null && m.modele().animations().containsKey(n) ? n : null;
    }

    /** Rôles joués en boucle : leurs animations (du mob ou de ses phases) se remplacent l'une l'autre. */
    static final List<String> ROLES_BOUCLE = List.of("garde", "marche", "marche-combat", "course", "recul", "droite", "gauche",
        "fuite", "assis");

    /** Vrai si {@code anim} est une boucle de ce mob : un rôle de {@link #ROLES_BOUCLE} dans ses animations ou ses phases. */
    static boolean boucleDuMob(Mobs.Suivi s, String anim) {
        for (String r : ROLES_BOUCLE) if (anim.equals(s.d.animation(r))) return true;
        if (s.d.combattant()) for (Combat.Phase ph : s.d.combat().phases()) if (ph.animations().containsValue(anim)) return true;
        return false;
    }

    /** Rôle remplacé par la phase en cours (ou une phase d'avant) : {@code phases[k].animations} ; null sinon. */
    static String animDePhase(Combat cb, int phase, String role) {
        for (int k = Math.min(phase, cb.phases().size()) - 1; k >= 0; k--) {
            String n = cb.phases().get(k).animations().get(role);
            if (n != null) return n;
        }
        return null;
    }

    /** Animation de déplacement en boucle, par-dessus (un coup reçu la coupe) ; rien si une autre animation joue. */
    private void jouerBoucle(Mobs.Suivi s, Modeles.Instance m, String role) {
        if (m == null) return;
        String voulu = role == null ? null : anim(s, m, role);
        String cur = m.animation();
        // attaque, coup reçu, réaction : on la laisse finir (une boucle propre au mob ou à sa phase, posée ici, se remplace)
        boolean boucle = cur != null && (boucles.contains(cur) || cur.equals(s.ia.anim) || boucleDuMob(s, cur));
        if (cur != null && !boucle) return;
        if (voulu == null) {
            if (ANIMS.containsValue(cur) || cur != null && cur.equals(s.ia.anim)) m.arreterAnimation();
            s.ia.anim = null;
            return;
        }
        if (voulu.equals(cur)) return;
        m.jouerParDessus(voulu, true);
        s.ia.anim = voulu;
    }

    // ================================================================== attaques

    private Competence choisirAttaque(Mobs.Suivi s, Mob mob, Player cible) {
        Etat e = s.ia;
        Combat cb = s.d.combat();
        long t = tic();
        Location l = mob.getLocation(), c = cible.getLocation();
        double d = Geometrie.distance(l.getX(), l.getZ(), c.getX(), c.getZ());
        double ratio = s.vie / Math.max(1, s.vieMax);
        boolean vue = mob.hasLineOfSight(cible);
        List<Competence> pretes = new ArrayList<>();
        double total = 0;
        // à plusieurs sur le même joueur (rangs normal et élite) : chacun son tour, une attaque lourde à la fois
        long[] ry = enGroupe(cb, cible) ? rythme.get(cible.getUniqueId()) : null;
        boolean attendre = ry != null && t < ry[0], sansLourde = ry != null && t < ry[1];
        int recus = coupsRecus(e.coups, t, 60);
        // plus de munitions : la recharge passe avant tout, une fois arrivé au dépôt (ou sur place sans dépôt)
        if (e.munitions == 0) {
            for (Competence a : cb.competences()) {
                if (a.rechargeMunitions() && t >= e.prete.getOrDefault(a.id(), 0L)) return e.depot == null || auDepot(mob, e.depot) ? a : null;
            }
        }
        for (Competence a : attendre ? List.<Competence>of() : cb.competences()) {
            if (t < e.prete.getOrDefault(a.id(), 0L) || e.phase < a.phase() || ratio > a.vieMax()) continue;
            if (a.rechargeMunitions() || (a.munitions() && e.munitions == 0)) continue;
            if (a.phaseMax() >= 0 && e.phase > a.phaseMax()) continue;
            if (a.invocation() != null && !peutInvoquer(e, a.invocation())) continue;
            if (a.soinAllie() != null) {
                // soin d'un allié : seulement s'il y en a un à soigner, à sa portée (la cible joueur ne compte pas)
                if (allieABlesse(s, a) == null) continue;
                pretes.add(a);
                total += a.poids();
                continue;
            }
            if (recus < a.presse() || (sansLourde && a.lourde())) continue;
            boolean ok = switch (a.forme()) {
                case CERCLE -> d <= a.porteeMax() + 0.3;
                case CONE, LIGNE -> d <= a.rayon() && d >= a.porteeMin() && vue;
                case CONTACT -> d <= a.porteeMax() + 0.3 && d >= a.porteeMin();
                default -> d >= a.porteeMin() && d <= a.porteeMax() + 0.3 && vue;
            };
            if (!ok) continue;
            pretes.add(a);
            total += a.poids();
        }
        // une attaque qui le dégage (bond en arrière) passe avant tout : frappé de près, il se dégage
        for (Competence a : pretes) if (a.bond() < 0) return a;
        if (!pretes.isEmpty() && total > 0) {
            double r = hasard.nextDouble() * total;
            for (Competence a : pretes) { r -= a.poids(); if (r <= 0) return a; }
            return pretes.get(pretes.size() - 1);
        }
        Competence b = cb.coupDeBase();
        if (b != null && t >= e.prete.getOrDefault(b.id(), 0L) && d <= b.porteeMax() + 0.3 && d >= b.porteeMin()) return b;
        return null;
    }

    /** Vrai si ce mob partage son rythme d'attaque : rang normal ou élite, et au moins deux mobs sur ce joueur. */
    private boolean enGroupe(Combat cb, Player cible) {
        if (cb.rang() != Combat.Rang.NORMAL && cb.rang() != Combat.Rang.ELITE) return false;
        Set<UUID> l = parJoueur.get(cible.getUniqueId());
        return l != null && l.size() >= 2;
    }

    /** Nombre de coups reçus dans les {@code tics} derniers tics. */
    static int coupsRecus(java.util.Collection<Long> coups, long t, int tics) {
        int n = 0;
        for (long c : coups) if (t - c <= tics) n++;
        return n;
    }

    /** Tics à partir desquels le groupe peut de nouveau attaquer ce joueur : {une attaque, une attaque lourde}. */
    static long[] rythmeApres(long[] avant, long t, Competence c, int cadence, int repit) {
        long[] r = avant == null ? new long[2] : avant;
        r[0] = Math.max(r[0], t + cadence);
        if (c.lourde()) r[1] = Math.max(r[1], t + c.total() + repit);
        return r;
    }

    private void demarrer(Mobs.Suivi s, Mob mob, Modeles.Instance m, Competence c, Player cible) {
        Etat e = s.ia;
        long t = tic();
        Combat cbt = s.d.combat();
        if (c != cbt.coupDeBase() && (cbt.rang() == Combat.Rang.NORMAL || cbt.rang() == Combat.Rang.ELITE))
            rythme.put(cible.getUniqueId(), rythmeApres(rythme.get(cible.getUniqueId()), t, c, cbt.ia().cadence(), cbt.ia().repit()));
        if (c.presse() > 0) e.coups.clear();
        e.enCours = c;
        e.t0 = t;
        e.frappe = false;
        e.secondFait = false;
        if (c.munitions() && e.munitions > 0) e.munitions--;
        Player vise = cible;
        if (c.fixation() != null) {
            Player f = fixation(s, mob, c.fixation());
            if (f != null) vise = f;
        }
        e.cibleAttaque = vise.getUniqueId();
        mob.getPathfinder().stopPathfinding();
        arreter(mob);
        regarder(mob, vise.getLocation());
        Location l = mob.getLocation();
        e.direction = vise.getLocation().toVector().subtract(l.toVector()).setY(0);
        if (e.direction.lengthSquared() < 1e-4) e.direction = l.getDirection().setY(0);
        e.direction.normalize();
        e.tirs = 0;
        e.bondFait = false;
        e.pluieFaite = false;
        e.zones.clear();
        retirerMarques(e);
        switch (c.forme()) {
            case ZONE -> {
                // le premier cercle sur la cible, le deuxième sur sa route, les autres autour
                Location c0 = sol(vise.getLocation());
                e.zones.add(c0);
                for (int k = 1; k < c.zones(); k++) {
                    Location z = null;
                    if (k == 1) {
                        z = predire(vise, c.signal(), c.anticipation());
                        if (z.distanceSquared(vise.getLocation()) < c.rayon() * c.rayon() * 0.5) z = null;
                    }
                    if (z == null) {
                        double a = hasard.nextDouble() * Math.PI * 2, r = c.dispersion() * (0.6 + 0.4 * hasard.nextDouble());
                        z = vise.getLocation().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                    }
                    e.zones.add(sol(z));
                }
                e.visee = c0;
            }
            case CERCLE -> {
                e.visee = sol(l);
                e.zones.add(e.visee);
            }
            case CONE, LIGNE -> e.visee = sol(l);
            default -> e.visee = null;
        }
        // marques au sol en modèles animés (sans modèle : particules, pendant le signal)
        if (c.effets().marque() != null) {
            if (c.forme() == Competence.Forme.ZONE || c.forme() == Competence.Forme.CERCLE) {
                for (Location z : e.zones) ajouter(e.marques, marqueFx(c, z, c.rayon(), c.signal(), 0));
            } else if (c.forme() == Competence.Forme.LIGNE) {
                double cote = Math.max(0.5, c.largeur());
                int n = (int) Math.max(1, Math.ceil(c.rayon() / cote));
                for (int k = 0; k < n; k++) {
                    Location z = e.visee.clone().add(e.direction.clone().multiply((k + 0.5) * cote));
                    ajouter(e.marques, marqueFx(c, z, cote, c.signal(), yaw(e.direction)));
                }
            }
        }
        e.animA = 0;
        if (m != null) {
            if (c.animation() != null && m.modele().animations().containsKey(c.animation())) {
                // geste retardé : il frappe à la fin du signal, quand le coup touche
                if (c.departAnimation() > 0) e.animA = t + c.departAnimation();
                else m.jouer(c.animation(), false);
            } else if (c.forme() == Competence.Forme.CONTACT) m.jouerAuto("attaque");
        }
        if (c.annonce() != null) annoncer(s, c.annonce());
        if (c.fixation() != null && vise.isOnline()) vise.sendActionBar(Messages.mini("<red>" + nom(s) + " te prend pour cible !"));
        s.dernierCombat = System.currentTimeMillis();
    }

    private Player fixation(Mobs.Suivi s, Mob mob, String regle) {
        List<Player> l = new ArrayList<>();
        for (UUID u : s.ia.menace.keySet()) { Player p = Bukkit.getPlayer(u); if (cibleValide(p, mob)) l.add(p); }
        if (l.isEmpty()) return null;
        Location ici = mob.getLocation();
        return switch (regle.toLowerCase(java.util.Locale.ROOT)) {
            case "loin" -> l.stream().max((a, b) -> Double.compare(a.getLocation().distanceSquared(ici), b.getLocation().distanceSquared(ici))).get();
            case "proche" -> l.stream().min((a, b) -> Double.compare(a.getLocation().distanceSquared(ici), b.getLocation().distanceSquared(ici))).get();
            default -> l.get(hasard.nextInt(l.size()));
        };
    }

    private void suivreAttaque(Mobs.Suivi s, Mob mob, Modeles.Instance m, Player cible) {
        Etat e = s.ia;
        Competence c = e.enCours;
        long t = tic(), dt = t - e.t0;
        Player vise = Bukkit.getPlayer(e.cibleAttaque);
        if (!cibleValide(vise, mob)) vise = cible;
        if (e.animA > 0 && t >= e.animA) {
            e.animA = 0;
            if (m != null && c.animation() != null && m.modele().animations().containsKey(c.animation())) m.jouer(c.animation(), false);
        }
        boolean bond = t < e.bondJusqua;
        if (!bond) arreter(mob);
        // il suit sa cible du regard tant qu'il vise (contact, projectile) ; une zone posée ne bouge plus
        if (vise != null && !bond && (c.forme() == Competence.Forme.CONTACT || c.forme() == Competence.Forme.PROJECTILE)) {
            regarder(mob, vise.getLocation());
            Vector dir = vise.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() > 1e-4) e.direction = dir.normalize();
        }
        if (dt < c.signal()) {
            if (e.marques.isEmpty() && dt % 2 == 0) marquer(s, mob, c, (double) dt / Math.max(1, c.signal()));
            if (c.forme() == Competence.Forme.PROJECTILE && c.effets().projectile() == null && dt % 3 == 0) {
                Location main = depart(s, mob, c, 0);
                main.getWorld().spawnParticle(Particle.FLAME, main, 2, 0.08, 0.08, 0.08, 0.01);
            }
            if (c.fixation() != null && vise != null && dt % 4 == 0) {
                vise.getWorld().spawnParticle(Particle.DUST, vise.getLocation().add(0, 2.4, 0), 6, 0.15, 0.15, 0.15, 0,
                    new Particle.DustOptions(Color.fromRGB(0xFF2020), 1.4f));
            }
            // la pluie commence à tomber un peu avant le coup : elle touche le sol quand il tombe
            if (c.forme() == Competence.Forme.ZONE && c.effets().pluie() != null && !e.pluieFaite && dt >= c.signal() - 7) {
                e.pluieFaite = true;
                for (Location z : e.zones) fx(c.effets().pluie(), z, hasard.nextFloat() * 360, "pluie", c.rayon());
            }
            return;
        }
        // les tirs d'une salve partent un par un
        int dus = (int) Math.min(c.salve(), (dt - c.signal()) / c.intervalle() + 1);
        while (e.tirs < dus) {
            executer(s, mob, c, vise, e.tirs);
            e.tirs++;
        }
        if (e.tirs >= c.salve() && c.bond() != 0 && !e.bondFait) {
            e.bondFait = true;
            bondir(s, mob, c);
        }
        if (c.effets().pluie() == null && dt < c.signal() + c.actif() && (c.forme() == Competence.Forme.ZONE || c.forme() == Competence.Forme.CERCLE)) {
            for (Location z : e.zones) effetZone(z, c);
        }
        if (c.secondCoup() != null && !e.secondFait && dt >= c.secondCoup().tic()) {
            e.secondFait = true;
            secondCoup(s, mob, c);
        }
        if (dt >= c.total()) {
            e.prete.put(c.id(), e.t0 + Math.max(c.recharge(), c.total()));
            if (c.rechargeMunitions() && s.d.combat().meca().munitions() != null) {
                // recharge finie : toutes ses munitions, et il cherchera un autre dépôt la prochaine fois
                e.munitions = s.d.combat().meca().munitions().nombre();
                e.depot = null;
                e.depotCherche = false;
            }
            e.enCours = null;
            e.marques.clear();
            e.libreA = tic() + hasard.nextInt(5);
        }
    }

    /**
     * Deuxième coup d'une attaque ({@code second-coup}) : sa forme part du mob, dans la direction de l'attaque ; dégâts ×
     * son multiplicateur (au lieu de celui de l'attaque).
     */
    private void secondCoup(Mobs.Suivi s, Mob mob, Competence c) {
        Competence.SecondCoup sc = c.secondCoup();
        double part = c.multiplicateur() > 0 ? sc.multiplicateur() / c.multiplicateur() : sc.multiplicateur();
        Location l = mob.getLocation();
        World w = l.getWorld();
        Vector dir = s.ia.direction == null ? l.getDirection().setY(0).normalize() : s.ia.direction;
        double cos = Math.cos(Math.toRadians(sc.largeur() / 2));
        for (Player p : w.getPlayers()) {
            if (!cibleValide(p, mob) || Math.abs(p.getLocation().getY() - l.getY()) >= 2.5) continue;
            Vector v = p.getLocation().toVector().subtract(l.toVector()).setY(0);
            double d = v.length();
            boolean touche = switch (sc.forme()) {
                case CERCLE -> d <= sc.rayon() + 0.3;
                case LIGNE -> {
                    double avant = v.dot(dir), cote = Math.abs(v.getX() * -dir.getZ() + v.getZ() * dir.getX());
                    yield avant >= -0.3 && avant <= sc.rayon() && cote <= sc.largeur() / 2 + 0.3;
                }
                case CONTACT -> d <= sc.rayon() + 0.7 && (d < 0.8 || v.clone().normalize().dot(dir) > 0.45);
                default -> d <= sc.rayon() && (d < 0.8 || v.clone().normalize().dot(dir) >= cos);
            };
            if (touche) toucher(s, mob, c, p, part);
        }
        w.playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.7f);
    }

    /** Vrai s'il est arrivé à son dépôt de recharge. */
    private static boolean auDepot(Mob mob, Location depot) {
        return depot.getWorld().equals(mob.getWorld()) && Geometrie.distance(mob.getLocation().getX(), mob.getLocation().getZ(),
            depot.getX(), depot.getZ()) <= 1.8 && Math.abs(mob.getLocation().getY() - depot.getY()) < 2.5;
    }

    /**
     * Plus de munitions : il cherche (une fois) son dépôt, le point « recharge » ou le bloc le plus proche à {@code rayon}
     * blocs, et y marche ; vrai tant qu'il marche. Sans dépôt, il recharge sur place.
     */
    private boolean allerAuDepot(Mobs.Suivi s, Mob mob, Modeles.Instance m, long t) {
        Etat e = s.ia;
        Mecaniques.Munitions mu = s.d.combat().meca().munitions();
        if (mu == null) return false;
        if (!e.depotCherche) {
            e.depotCherche = true;
            e.depot = mobs.endroitProche(mob.getLocation(), mu.recharge(), mu.rayon(), mu.blocs());
        }
        if (e.depot == null || auDepot(mob, e.depot)) return false;
        if (t >= e.prochainChemin || !mob.getPathfinder().hasPath()) {
            if (!mob.getPathfinder().moveTo(e.depot, 1.1 * s.d.combat().ia().vitesse() * e.vitessePhase)) { e.depot = null; return false; }
            e.prochainChemin = t + 10;
        }
        e.mode = "depot";
        jouerBoucle(s, m, "marche-combat");
        return true;
    }

    /** Vrai s'il peut encore invoquer : {@code max} vivants à la fois, {@code total} sur le combat. */
    private static boolean peutInvoquer(Etat e, Competence.Invocation inv) {
        if (inv.total() > 0 && e.invoques >= inv.total()) return false;
        if (inv.max() <= 0) return true;
        int vivants = 0;
        for (UUID u : e.invocations) { Entity x = Bukkit.getEntity(u); if (x != null && x.isValid() && !x.isDead()) vivants++; }
        return vivants < inv.max();
    }

    /** Ses invocations naissent autour de lui et prennent sa cible (dans les limites de {@code max} et {@code total}). */
    private void invoquer(Mobs.Suivi s, Mob mob, Competence.Invocation inv, Player cible) {
        Etat e = s.ia;
        e.invocations.removeIf(u -> { Entity x = Bukkit.getEntity(u); return x == null || !x.isValid() || x.isDead(); });
        for (int k = 0; k < inv.nombre(); k++) {
            if ((inv.max() > 0 && e.invocations.size() >= inv.max()) || (inv.total() > 0 && e.invoques >= inv.total())) break;
            double a = 2 * Math.PI * k / inv.nombre() + hasard.nextDouble();
            Location ou = Mobs.sol(mob.getWorld(), mob.getLocation().getX() + Math.cos(a) * 2.5, mob.getLocation().getY(),
                mob.getLocation().getZ() + Math.sin(a) * 2.5, false);
            if (ou == null) ou = mob.getLocation();
            Entity x = mobs.invoquer(inv.mob(), ou, null, null, s.niveau);
            if (x == null) continue;
            e.invocations.add(x.getUniqueId());
            e.invoques++;
            Mobs.Suivi o = mobs.suivi(x);
            if (o != null && cible != null) ajouterMenace(o, cible, 10);
        }
    }

    /** L'allié de sa famille le plus blessé sous le seuil de la compétence, à sa portée ; null s'il n'y en a pas. */
    private Mobs.Suivi allieABlesse(Mobs.Suivi s, Competence c) {
        Competence.SoinAllie sa = c.soinAllie();
        double portee = Math.max(2, c.porteeMax() + 1), best = sa.seuil();
        Mobs.Suivi choisi = null;
        for (Mobs.Suivi o : mobs.suivis()) {
            if (o == s || !o.d.combattant() || o.vie <= 0 || !o.e.isValid() || !o.e.getWorld().equals(s.e.getWorld())) continue;
            if (!sa.cible().isEmpty() && !sa.cible().equals(o.d.combat().meca().famille())) continue;
            if (o.e.getLocation().distanceSquared(s.e.getLocation()) > portee * portee) continue;
            double r = o.vie / Math.max(1, o.vieMax);
            if (r < best) { best = r; choisi = o; }
        }
        return choisi;
    }

    /** Bonus de dégâts des auras alliées (Os-violon) autour de ce mob : × (1 + la plus forte). */
    double aura(Mobs.Suivi s) {
        double bonus = 0;
        long t = tic();
        for (Mobs.Suivi o : mobs.suivis()) {
            Mecaniques.Aura au = o == s || !o.d.combattant() ? null : o.d.combat().meca().aura();
            if (au == null || o.vie <= 0 || !o.e.isValid() || !o.e.getWorld().equals(s.e.getWorld())) continue;
            if (au.purification() && o.ia != null && t < o.ia.purifieJusqua) continue;
            if (o.e.getLocation().distanceSquared(s.e.getLocation()) > au.rayon() * au.rayon()) continue;
            bonus = Math.max(bonus, au.degats());
        }
        return 1 + bonus;
    }

    /** Purification posée sur ce mob : son aura s'éteint {@code ms}. */
    void purifier(Mobs.Suivi s, long ms) {
        if (s.ia == null) return;
        s.ia.purifieJusqua = Math.max(s.ia.purifieJusqua, tic() + Math.max(1, ms / 50));
    }

    /** Après son coup, le mob bondit (en arrière si {@code bond} est négatif) ; il cherche un côté libre si un mur le gêne. */
    private void bondir(Mobs.Suivi s, Mob mob, Competence c) {
        Vector dir = s.ia.direction.clone().multiply(c.bond() < 0 ? -1 : 1);
        double dist = Math.abs(c.bond());
        for (double angle : new double[] {0, 50, -50, 90, -90}) {
            Vector d = tourner(dir, angle);
            if (!libre(mob, d, Math.min(dist, 3))) continue;
            mob.getPathfinder().stopPathfinding();
            mob.setVelocity(new Vector(d.getX() * dist / 7.2, 0.4, d.getZ() * dist / 7.2));
            s.ia.bondJusqua = tic() + 13;
            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.4f, 1.6f);
            return;
        }
    }

    private static Vector tourner(Vector v, double degres) {
        double a = Math.toRadians(degres), c = Math.cos(a), s = Math.sin(a);
        return new Vector(v.getX() * c - v.getZ() * s, 0, v.getX() * s + v.getZ() * c);
    }

    /** La voie est libre sur {@code dist} blocs (pieds et tête), avec du sol au bout. */
    private static boolean libre(Mob mob, Vector dir, double dist) {
        Location l = mob.getLocation();
        World w = l.getWorld();
        for (double k = 1; k <= dist + 0.01; k += 1) {
            Block pieds = w.getBlockAt((int) Math.floor(l.getX() + dir.getX() * k), l.getBlockY(), (int) Math.floor(l.getZ() + dir.getZ() * k));
            if (!pieds.isPassable() || !pieds.getRelative(0, 1, 0).isPassable()) return false;
            if (k + 1 > dist + 0.01) {
                Block d = pieds.getRelative(0, -1, 0);
                if (d.isPassable() && d.getRelative(0, -1, 0).isPassable() && d.getRelative(0, -2, 0).isPassable()) return false;
            }
        }
        return true;
    }

    /** Point de départ du tir n° k : l'os du modèle prévu pour lui, sinon au-dessus de ses yeux. */
    private Location depart(Mobs.Suivi s, Mob mob, Competence c, int k) {
        Location o = null;
        String os = c.depart(k);
        if (os != null && plugin.modeles() != null) {
            Modeles.Instance m = modele(s);
            if (m != null) o = m.positionOs(os);
        }
        return o != null ? o : mob.getEyeLocation().add(0, 0.4, 0);
    }

    private void executer(Mobs.Suivi s, Mob mob, Competence c, Player vise, int k) {
        if (k == 0) s.ia.attaques++;
        if (k == 0 && c.invocation() != null) invoquer(s, mob, c.invocation(), vise);
        if (c.soinAllie() != null) {
            Mobs.Suivi al = allieABlesse(s, c);
            if (al != null) {
                mobs.combats().soigner(al, al.vieMax * c.soinAllie().soin());
                al.e.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, al.e.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0);
                al.e.getWorld().playSound(al.e.getLocation(), Sound.BLOCK_HONEY_BLOCK_PLACE, 1f, 0.7f);
            }
            return;
        }
        Location l = mob.getLocation();
        World w = l.getWorld();
        switch (c.forme()) {
            case CONTACT -> {
                if (vise == null) return;
                Vector vers = vise.getLocation().toVector().subtract(l.toVector()).setY(0);
                double d = vers.length();
                boolean devant = d < 0.8 || vers.normalize().dot(s.ia.direction) > 0.45;
                if (d <= c.porteeMax() + 0.7 && devant && Math.abs(vise.getLocation().getY() - l.getY()) < 2.5) toucher(s, mob, c, vise, 1);
                w.playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 0.8f);
            }
            case PROJECTILE -> {
                if (vise != null) lancer(s, mob, c, vise, k);
            }
            case ZONE, CERCLE -> {
                Set<UUID> touches = new HashSet<>();
                for (Location centre : s.ia.zones) {
                    for (Player p : w.getPlayers()) {
                        if (!cibleValide(p, mob) || touches.contains(p.getUniqueId())) continue;
                        Location pl = p.getLocation();
                        if (Geometrie.distance(pl.getX(), pl.getZ(), centre.getX(), centre.getZ()) <= c.rayon() + 0.3
                            && Math.abs(pl.getY() - centre.getY()) < 2.5) {
                            touches.add(p.getUniqueId());
                            toucher(s, mob, c, p, 1);
                        }
                    }
                    if (c.forme() == Competence.Forme.CERCLE) fx(c.effets().impact(), centre, hasard.nextFloat() * 360, "impact", c.rayon());
                    if (c.flaque() > 0) flaque(s, c, centre);
                    w.playSound(centre, Sound.BLOCK_LAVA_EXTINGUISH, 0.8f, 0.7f);
                }
            }
            case CONE -> {
                double cos = Math.cos(Math.toRadians(c.largeur() / 2));
                for (Player p : w.getPlayers()) {
                    if (!cibleValide(p, mob)) continue;
                    Vector v = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                    double d = v.length();
                    if (d <= c.rayon() && (d < 0.8 || v.normalize().dot(s.ia.direction) >= cos) && Math.abs(p.getLocation().getY() - l.getY()) < 2.5) toucher(s, mob, c, p, 1);
                }
                w.playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.6f);
            }
            case LIGNE -> {
                Vector dir = s.ia.direction;
                for (Player p : w.getPlayers()) {
                    if (!cibleValide(p, mob)) continue;
                    Vector v = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                    double avant = v.dot(dir), cote = Math.abs(v.getX() * -dir.getZ() + v.getZ() * dir.getX());
                    if (avant >= -0.3 && avant <= c.rayon() && cote <= c.largeur() / 2 + 0.3 && Math.abs(p.getLocation().getY() - l.getY()) < 2.5) toucher(s, mob, c, p, 1);
                }
                w.playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.5f);
            }
        }
    }

    /**
     * Lance un projectile. Il vise où la cible sera à l'arrivée si elle garde son allure ({@code anticipation}). Dans une
     * salve, le 2e tir anticipe plus loin et le 3e vise la cible elle-même : continuer tout droit ou s'arrêter ne suffit
     * pas, il faut changer de direction.
     */
    private void lancer(Mobs.Suivi s, Mob mob, Competence c, Player vise, int k) {
        Location a = depart(s, mob, c, k);
        double ant = k % 3 == 0 ? c.anticipation() : k % 3 == 1 ? Math.min(2, c.anticipation() * 1.8 + 0.2) : 0;
        int duree = (int) Math.max(4, Math.round(a.distance(vise.getLocation()) / c.vitesse()));
        Location b = sol(predire(vise, duree, ant));
        double dist = a.distance(b);
        duree = (int) Math.max(4, Math.min(40, Math.round(dist / c.vitesse())));
        Projectile p = new Projectile(s, c, a.toVector(), b.toVector().add(new Vector(0, 0.1, 0)), 0.4 + dist * 0.07, duree);
        Vector dir = b.toVector().subtract(a.toVector()).setY(0);
        p.fx = fx(c.effets().projectile(), a, dir.lengthSquared() > 1e-4 ? yaw(dir) : 0, "vol", Math.max(0.8, c.rayon() / 1.5));
        p.marque = marqueFx(c, b, c.rayon(), duree, 0);
        projectiles.add(p);
        a.getWorld().playSound(a, Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.2f);
    }

    /**
     * Le coup d'une attaque touche un joueur : le plugin du combat lit {@link CoupMob} pendant l'événement.
     * {@code part} : part des dégâts (1 pour le coup, moins pour une flaque).
     */
    void toucher(Mobs.Suivi s, Mob mob, Competence c, Player p, double part) {
        CoupMob coup = CoupMob.de(s.d.combat(), s.niveau, c, s.ia.bonus * part * s.d.combat().facteurDegats(s.ia.joueurs()) * aura(s));
        s.coupEnCours = coup;
        s.sansAnimAuto = true;
        // le recul du jeu ne s'applique pas : un coup ne repousse que par son réglage « repousse » (plus bas)
        sansRecul.put(p.getUniqueId(), Bukkit.getCurrentTick());
        try {
            p.setNoDamageTicks(0);
            p.damage(Math.max(1, coup.puissance() * coup.multiplicateur() / 20.0), mob);
        } finally {
            s.coupEnCours = null;
            s.sansAnimAuto = false;
        }
        if (c.repousse() > 0 && part >= 1) sansRecul.remove(p.getUniqueId());
        if (c.repousse() > 0 && part >= 1 && p.isValid() && !p.isDead()) {
            Vector v = p.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
            if (v.lengthSquared() < 1e-4) v = new Vector(1, 0, 0);
            p.setVelocity(v.normalize().multiply(c.repousse() * 0.6).setY(0.36));
        }
        s.dernierCombat = System.currentTimeMillis();
    }

    // ================================================================== effets en modèles animés

    /** Nombre d'effets en modèles posés depuis le démarrage (pour /vmob info et les essais). */
    static int effetsPoses;

    private static float yaw(Vector d) {
        return (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
    }

    /** Fait apparaître un modèle d'effet le temps d'une animation ; null s'il n'existe pas (on garde alors les particules). */
    private Modeles.Instance fx(String modele, Location ou, float yaw, String animation, double taille) {
        if (modele == null || ou == null || plugin.modeles() == null) return null;
        try {
            Modeles.Instance i = plugin.modeles().apparition(modele, ou, yaw, animation, (float) taille);
            if (i != null) effetsPoses++;
            return i;
        } catch (Throwable ex) {
            return null;
        }
    }

    private void retirerFx(Modeles.Instance i) {
        try {
            if (i != null && i.base() != null && i.base().isValid()) plugin.modeles().retirer(i);
        } catch (Throwable ignore) { }
    }

    private static void ajouter(List<Modeles.Instance> l, Modeles.Instance i) {
        if (i != null) l.add(i);
    }

    private void retirerMarques(Etat e) {
        for (Modeles.Instance i : e.marques) retirerFx(i);
        e.marques.clear();
    }

    /** Durée (tics) de l'animation « t<tics> » d'une marque : la plus longue qui ne dépasse pas le signal. */
    static int dureeMarque(int tics) {
        return tics <= 40 ? Math.max(4, tics - tics % 2) : Math.min(80, tics - tics % 4);
    }

    /** Durée (tics) de l'animation « d<tics> » d'une flaque : la plus proche de celle demandée. */
    static int dureeFlaque(int tics) {
        int mieux = 20;
        for (int d : new int[] {20, 30, 40, 50, 60, 80, 100, 120, 160, 200}) if (Math.abs(d - tics) < Math.abs(mieux - tics)) mieux = d;
        return mieux;
    }

    /** Marque au sol (modèle de 1 bloc de rayon, agrandi) qui se remplit pendant {@code tics}. */
    private Modeles.Instance marqueFx(Competence c, Location centre, double taille, int tics, float yaw) {
        return fx(c.effets().marque(), centre, yaw, "t" + dureeMarque(tics), taille);
    }

    private void flaque(Mobs.Suivi s, Competence c, Location centre) {
        int n = dureeFlaque(c.flaque());
        fx(c.effets().flaque(), centre, hasard.nextFloat() * 360, "d" + n, c.rayon());
        flaques.add(new Flaque(s, c, centre, tic() + n - 5));
    }

    // ================================================================== allure des joueurs

    private void suivreJoueurs() {
        allures.keySet().retainAll(parJoueur.keySet());
        rythme.keySet().retainAll(parJoueur.keySet());
        for (UUID u : parJoueur.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            Location l = p.getLocation();
            double[] a = allures.get(u);
            if (a == null) {
                allures.put(u, new double[] {l.getX(), l.getZ(), 0, 0});
                continue;
            }
            double dx = l.getX() - a[0], dz = l.getZ() - a[1];
            if (dx * dx + dz * dz > 4) { dx = 0; dz = 0; }   // téléporté
            a[2] = a[2] * 0.6 + dx * 0.4;
            a[3] = a[3] * 0.6 + dz * 0.4;
            a[0] = l.getX();
            a[1] = l.getZ();
        }
    }

    /** Où sera le joueur dans {@code tics} s'il garde son allure (anticipation 0 : où il est ; 7 blocs d'avance au plus). */
    Location predire(Player p, double tics, double anticipation) {
        Location l = p.getLocation();
        double[] a = allures.get(p.getUniqueId());
        if (a == null || anticipation <= 0) return l;
        double dx = a[2] * tics * anticipation, dz = a[3] * tics * anticipation;
        double d = Math.hypot(dx, dz);
        if (d > 7) { dx *= 7 / d; dz *= 7 / d; }
        return l.add(dx, 0, dz);
    }

    // ================================================================== projectiles et flaques (chaque tic, pour tous les mobs)

    void ticProjectiles() {
        suivreJoueurs();
        Iterator<Projectile> it = projectiles.iterator();
        while (it.hasNext()) {
            Projectile pr = it.next();
            if (!pr.s.e.isValid() || !(pr.s.e instanceof Mob mob)) {
                retirerFx(pr.fx);
                retirerFx(pr.marque);
                it.remove();
                continue;
            }
            World w = mob.getWorld();
            pr.t++;
            double f = Math.min(1, (double) pr.t / pr.duree);
            Location ici = pr.position(f).toLocation(w);
            if (pr.fx != null && pr.fx.base() != null && pr.fx.base().isValid()) {
                Location lf = ici.clone();
                lf.setYaw(pr.fx.base().getLocation().getYaw());
                pr.fx.base().teleport(lf);
            } else {
                w.spawnParticle(Particle.FLAME, ici, 3, 0.06, 0.06, 0.06, 0.005);
                w.spawnParticle(Particle.DUST, ici, 2, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(Color.fromRGB(pr.c.couleur()), 1.3f));
            }
            if (pr.marque == null && pr.t % 2 == 0) cercle(w, pr.arrivee.toLocation(w), pr.c.rayon(), pr.c.couleur(), f);
            boolean mur = f > 0.25 && f < 1 && !w.getBlockAt(ici).isPassable();
            // touche de plein fouet un joueur sur sa route
            boolean direct = false;
            if (f > 0.15 && f < 1) {
                for (Player j : w.getPlayers()) {
                    if (!cibleValide(j, mob)) continue;
                    Location jl = j.getLocation();
                    if (Math.abs(jl.getX() - ici.getX()) < 0.75 && Math.abs(jl.getZ() - ici.getZ()) < 0.75
                        && ici.getY() > jl.getY() - 0.3 && ici.getY() < jl.getY() + 2.1) { direct = true; break; }
                }
            }
            if (f >= 1 || mur || direct) {
                it.remove();
                impact(pr, mob, f >= 1 ? pr.arrivee.toLocation(w) : ici);
            }
        }
        if (flaques.isEmpty()) return;
        long t = tic();
        Iterator<Flaque> fi = flaques.iterator();
        while (fi.hasNext()) {
            Flaque fl = fi.next();
            if (t >= fl.fin || !fl.s.e.isValid() || !(fl.s.e instanceof Mob mob) || mob.isDead()) { fi.remove(); continue; }
            World w = fl.centre.getWorld();
            if (fl.c.effets().flaque() == null && t % 4 == 0) {
                w.spawnParticle(Particle.FLAME, fl.centre, (int) Math.max(3, fl.c.rayon() * 3), fl.c.rayon() * 0.45, 0.05, fl.c.rayon() * 0.45, 0.005);
            }
            for (Player j : w.getPlayers()) {
                if (!cibleValide(j, mob)) continue;
                Location jl = j.getLocation();
                if (Geometrie.distance(jl.getX(), jl.getZ(), fl.centre.getX(), fl.centre.getZ()) > fl.c.rayon() * 0.85
                    || Math.abs(jl.getY() - fl.centre.getY()) > 1.5) continue;
                if (t - fl.touches.getOrDefault(j.getUniqueId(), -100L) < 10) continue;
                fl.touches.put(j.getUniqueId(), t);
                toucher(fl.s, mob, fl.c, j, fl.c.flaqueMult());
            }
        }
    }

    private void impact(Projectile pr, Mob mob, Location ou) {
        Competence c = pr.c;
        World w = ou.getWorld();
        retirerFx(pr.fx);
        retirerFx(pr.marque);
        Location bas = sol(ou);
        if (fx(c.effets().impact(), bas, hasard.nextFloat() * 360, "impact", c.rayon()) == null) {
            w.spawnParticle(Particle.LAVA, ou, 10, c.rayon() * 0.4, 0.2, c.rayon() * 0.4, 0);
            w.spawnParticle(Particle.FLAME, ou, 24, c.rayon() * 0.5, 0.15, c.rayon() * 0.5, 0.03);
            w.spawnParticle(Particle.LARGE_SMOKE, ou, 6, 0.4, 0.2, 0.4, 0.01);
        }
        w.playSound(ou, Sound.BLOCK_LAVA_POP, 1f, 0.9f);
        for (Player j : w.getPlayers()) {
            if (!cibleValide(j, mob)) continue;
            Location jl = j.getLocation();
            if (Geometrie.distance(jl.getX(), jl.getZ(), ou.getX(), ou.getZ()) <= c.rayon() + 0.35
                && Math.abs(jl.getY() - bas.getY()) < 2.6) toucher(pr.s, mob, c, j, 1);
        }
        if (c.flaque() > 0) flaque(pr.s, c, bas);
    }

    void vider() {
        for (Projectile pr : projectiles) { retirerFx(pr.fx); retirerFx(pr.marque); }
        projectiles.clear();
        flaques.clear();
        for (Mobs.Suivi s : mobs.suivis()) if (s.ia != null) { cacherBarre(s); retirerMarques(s.ia); }
        parJoueur.clear();
        allures.clear();
        rythme.clear();
    }

    /** Un coup reçu d'un joueur (pour savoir s'il est pressé). */
    void coupRecu(Mobs.Suivi s, Player p) {
        if (s.ia == null) return;
        rejoindre(s, p);
        long t = tic();
        s.ia.coups.addLast(t);
        while (s.ia.coups.size() > 8) s.ia.coups.removeFirst();
        // « interrompu-par » : assez de coups pendant le signal l'interrompent (la Refonte de la Moulière)
        Competence c = s.ia.enCours;
        if (c != null && c.interrompuPar() > 0 && t - s.ia.t0 < c.signal() && coupsRecus(s.ia.coups, t, (int) (t - s.ia.t0)) >= c.interrompuPar()) {
            s.ia.prete.put(c.id(), t + c.recharge() / 2);
            s.ia.enCours = null;
            retirerMarques(s.ia);
            Modeles.Instance m = modele(s);
            if (m != null && c.animation() != null && c.animation().equals(m.animation())) m.arreterAnimation();
            s.e.getWorld().playSound(s.e.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1f);
            noter(s, "interrompu_" + c.id(), 1);
        }
    }

    // ================================================================== zones marquées

    /** Sol sous un point (le bloc plein le plus haut à 3 blocs ou moins en dessous). */
    static Location sol(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ();
        for (int y = l.getBlockY() + 1; y >= l.getBlockY() - 4; y--) {
            if (!w.getBlockAt(x, y, z).isPassable() && w.getBlockAt(x, y + 1, z).isPassable()) return new Location(w, l.getX(), y + 1.05, l.getZ());
        }
        return new Location(w, l.getX(), l.getY() + 0.05, l.getZ());
    }

    private void marquer(Mobs.Suivi s, Mob mob, Competence c, double progres) {
        World w = mob.getWorld();
        switch (c.forme()) {
            case ZONE, CERCLE -> cercle(w, s.ia.visee, c.rayon(), c.couleur(), progres);
            case CONE -> {
                Location o = s.ia.visee;
                double a0 = Math.atan2(s.ia.direction.getZ(), s.ia.direction.getX()), demi = Math.toRadians(c.largeur() / 2);
                Particle.DustOptions bord = new Particle.DustOptions(Color.fromRGB(c.couleur()), 1.1f);
                Particle.DustOptions plein = new Particle.DustOptions(Color.fromRGB(c.couleur()), 0.8f);
                for (double a = -demi; a <= demi + 1e-6; a += demi / 6) {
                    double ca = Math.cos(a0 + a), sa = Math.sin(a0 + a);
                    w.spawnParticle(Particle.DUST, o.clone().add(ca * c.rayon(), 0, sa * c.rayon()), 1, 0, 0, 0, 0, bord);
                    for (double r = 0.8; r <= c.rayon() * progres; r += 0.9) w.spawnParticle(Particle.DUST, o.clone().add(ca * r, 0, sa * r), 1, 0, 0, 0, 0, plein);
                }
            }
            case LIGNE -> {
                Location o = s.ia.visee;
                Vector d = s.ia.direction, cote = new Vector(-d.getZ(), 0, d.getX()).multiply(c.largeur() / 2);
                Particle.DustOptions bord = new Particle.DustOptions(Color.fromRGB(c.couleur()), 1.1f);
                Particle.DustOptions plein = new Particle.DustOptions(Color.fromRGB(c.couleur()), 0.8f);
                for (double a = 0; a <= c.rayon(); a += 0.5) {
                    Location p = o.clone().add(d.clone().multiply(a));
                    w.spawnParticle(Particle.DUST, p.clone().add(cote), 1, 0, 0, 0, 0, bord);
                    w.spawnParticle(Particle.DUST, p.clone().subtract(cote), 1, 0, 0, 0, 0, bord);
                    if (a <= c.rayon() * progres) w.spawnParticle(Particle.DUST, p, 1, cote.length() * 0.4, 0, cote.length() * 0.4, 0, plein);
                }
            }
            default -> { }
        }
    }

    /** Cercle marqué au sol : le bord, et l'intérieur qui se remplit avec {@code progres} (0 à 1). */
    static void cercle(World w, Location centre, double rayon, int couleur, double progres) {
        if (centre == null || rayon <= 0) return;
        Particle.DustOptions bord = new Particle.DustOptions(Color.fromRGB(couleur), 1.2f);
        Particle.DustOptions plein = new Particle.DustOptions(Color.fromRGB(couleur), 0.7f);
        int n = (int) Math.max(12, rayon * 10);
        for (int k = 0; k < n; k++) {
            double a = 2 * Math.PI * k / n;
            w.spawnParticle(Particle.DUST, centre.clone().add(Math.cos(a) * rayon, 0, Math.sin(a) * rayon), 1, 0, 0, 0, 0, bord);
        }
        double r = rayon * Math.max(0, Math.min(1, progres));
        if (r > 0.3) {
            int m = (int) Math.max(8, r * 8);
            for (int k = 0; k < m; k++) {
                double a = 2 * Math.PI * k / m;
                w.spawnParticle(Particle.DUST, centre.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, plein);
            }
        }
    }

    private void effetZone(Location centre, Competence c) {
        World w = centre.getWorld();
        w.spawnParticle(Particle.FALLING_LAVA, centre.clone().add(0, 3, 0), (int) Math.max(4, c.rayon() * 3), c.rayon() * 0.6, 0.4, c.rayon() * 0.6, 0);
        w.spawnParticle(Particle.DUST, centre, (int) Math.max(6, c.rayon() * 4), c.rayon() * 0.6, 0.05, c.rayon() * 0.6, 0,
            new Particle.DustOptions(Color.fromRGB(c.couleur()), 1.5f));
    }

    // ================================================================== élites ++ et boss

    private String nom(Mobs.Suivi s) {
        return Messages.echapper(s.d.nom() != null ? s.d.nom() : s.d.id());
    }

    private List<Player> joueursDuCombat(Mobs.Suivi s) {
        List<Player> l = new ArrayList<>();
        for (UUID u : s.ia.menace.keySet()) { Player p = Bukkit.getPlayer(u); if (p != null && p.isOnline()) l.add(p); }
        for (Player p : s.e.getWorld().getPlayers()) if (!l.contains(p) && p.getLocation().distanceSquared(s.e.getLocation()) < 24 * 24) l.add(p);
        return l;
    }

    /** Texte d'une attaque annoncée ou d'un changement de phase, sous le titre des joueurs du combat. */
    void annoncer(Mobs.Suivi s, String texte) {
        Title titre = Title.title(Component.empty(), Messages.mini(texte),
            Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(400)));
        for (Player p : joueursDuCombat(s)) {
            p.showTitle(titre);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
        }
    }

    private void phases(Mobs.Suivi s, Mob mob, Modeles.Instance m) {
        Combat cb = s.d.combat();
        double ratio = s.vie / Math.max(1, s.vieMax);
        while (s.ia.phase < cb.phases().size() && ratio <= cb.phases().get(s.ia.phase).seuil()) {
            Combat.Phase ph = cb.phases().get(s.ia.phase);
            s.ia.phase++;
            s.ia.vitessePhase *= ph.vitesse();
            if (ph.annonce() != null) annoncer(s, ph.annonce());
            if (ph.animation() != null && m != null && m.modele().animations().containsKey(ph.animation())) {
                m.jouer(ph.animation(), false);
                s.ia.libreA = tic() + 20;
            }
            if (ph.invocation() != null && ph.nombre() > 0) {
                Player cible = s.ia.cible == null ? null : Bukkit.getPlayer(s.ia.cible);
                for (int k = 0; k < ph.nombre(); k++) {
                    double a = 2 * Math.PI * k / ph.nombre() + hasard.nextDouble();
                    Location ou = Mobs.sol(mob.getWorld(), mob.getLocation().getX() + Math.cos(a) * 3, mob.getLocation().getY(),
                        mob.getLocation().getZ() + Math.sin(a) * 3, false);
                    if (ou == null) ou = mob.getLocation();
                    Entity inv = mobs.invoquer(ph.invocation(), ou, null, null, s.niveau);
                    if (inv == null) continue;
                    s.ia.invocations.add(inv.getUniqueId());
                    Mobs.Suivi o = mobs.suivi(inv);
                    if (o != null && cible != null) ajouterMenace(o, cible, 10);
                }
            }
        }
    }

    private boolean aBarre(Mobs.Suivi s) {
        Combat.Rang r = s.d.combat().rang();
        return r == Combat.Rang.ELITE_PP || r.boss();
    }

    private void barre(Mobs.Suivi s, Mob mob) {
        if (!aBarre(s)) return;
        Etat e = s.ia;
        Component titre = titreBoss(s);
        float p = (float) Math.max(0, Math.min(1, s.vie / Math.max(1, s.vieMax)));
        if (e.barre == null) {
            e.barre = BossBar.bossBar(titre, p, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        } else {
            if (!titre.equals(e.barre.name())) e.barre.name(titre);
            e.barre.progress(p);
        }
        Set<UUID> voient = new HashSet<>();
        for (Player j : joueursDuCombat(s)) {
            if (j.getLocation().distanceSquared(mob.getLocation()) > 48 * 48) continue;
            voient.add(j.getUniqueId());
            if (e.spectateurs.add(j.getUniqueId())) j.showBossBar(e.barre);
        }
        for (Iterator<UUID> it = e.spectateurs.iterator(); it.hasNext(); ) {
            UUID u = it.next();
            if (voient.contains(u)) continue;
            Player j = Bukkit.getPlayer(u);
            if (j != null) j.hideBossBar(e.barre);
            it.remove();
        }
    }

    /** Largeur (pixels) d'une couche de la barre de boss : deux moitiés de 96 px (avance 97) collées. */
    static final int LARGEUR_BOSS = 193;
    private static final int PALIERS_BOSS = 88;
    private Boolean glyphesBoss;

    private static String couche(String nom, String gauche, String droite) {
        return "<glyphe:vmob_boss_" + nom + "_g" + gauche + "><decalage:-1><glyphe:vmob_boss_" + nom + "_d" + droite + ">";
    }

    /** Glyphes de la barre de boss : cadre du rang (avec son fond), traîne, Vie, superposés. */
    static String barreBoss(double vie, double traine, double vieMax, Combat.Rang rang) {
        int v = vie <= 0 || vieMax <= 0 ? 0 : Math.max(1, Math.min(PALIERS_BOSS, (int) Math.round(PALIERS_BOSS * vie / vieMax)));
        int t = Math.max(v, vieMax <= 0 ? 0 : Math.min(PALIERS_BOSS, (int) Math.round(PALIERS_BOSS * traine / vieMax)));
        int m = PALIERS_BOSS / 2;
        String retour = "<decalage:-" + LARGEUR_BOSS + ">";
        return "<white>" + couche("cadre_" + Combats.cadre(rang), "", "") + retour
            + couche("traine", String.format("_%02d", Math.min(m, t)), String.format("_%02d", Math.max(0, t - m))) + retour
            + couche("vie", String.format("_%02d", Math.min(m, v)), String.format("_%02d", Math.max(0, v - m)));
    }

    /**
     * Titre de la barre de boss : la barre dessinée (glyphes sans ombre, par-dessus la barre du jeu) et, centré au-dessus,
     * le nom et le niveau. Sans les glyphes (Vorace pas encore redémarré) : le nom seul, sur la barre du jeu.
     */
    private Component titreBoss(Mobs.Suivi s) {
        Combat.Rang r = s.d.combat().rang();
        String nom = s.d.nom() != null ? s.d.nom() : s.d.id();
        String suite = " Nv." + s.niveau + (s.ia.enrage ? " · rage" : "");
        Component texte = Messages.mini(r.couleur() + Messages.echapper(nom) + "<#c9c1b4> Nv." + s.niveau + (s.ia.enrage ? " <red>· rage" : ""));
        if (glyphesBoss == null) {
            try { glyphesBoss = fr.vorace.api.VoraceAPI.glyphe("vmob_boss_vie_g_00") != null; } catch (Throwable ex) { glyphesBoss = false; }
        }
        if (!glyphesBoss) return texte;
        int n = Largeur.de(nom + suite), debut = (LARGEUR_BOSS - n) / 2;
        Component barre = Messages.mini(barreBoss(s.vie, Math.max(s.vie, s.vieTraine), s.vieMax, r) + decalage(debut - LARGEUR_BOSS))
            .shadowColor(net.kyori.adventure.text.format.ShadowColor.none());
        return Component.textOfChildren(barre, texte, Messages.mini(decalage(LARGEUR_BOSS - debut - n)));
    }

    private static String decalage(int px) {
        return px == 0 ? "" : "<decalage:" + px + ">";
    }

    void cacherBarre(Mobs.Suivi s) {
        if (s.ia == null || s.ia.barre == null) return;
        for (UUID u : s.ia.spectateurs) { Player j = Bukkit.getPlayer(u); if (j != null) j.hideBossBar(s.ia.barre); }
        s.ia.spectateurs.clear();
    }

    /** Mort du mob : barre retirée, il quitte les listes des joueurs, ses invocations restent. */
    void mort(Mobs.Suivi s) {
        if (s.ia == null) return;
        cacherBarre(s);
        desinscrire(s);
        retirerMarques(s.ia);
        for (Projectile pr : projectiles) if (pr.s == s) { retirerFx(pr.fx); retirerFx(pr.marque); }
        projectiles.removeIf(p -> p.s == s);
        flaques.removeIf(f -> f.s == s);
    }

    /** Pour /vmob menace. */
    static String etat(Mobs.Suivi s) {
        if (s.ia == null) return "";
        StringBuilder b = new StringBuilder();
        b.append(s.ia.engage ? "<red>en combat" : s.ia.rentre ? "<yellow>rentre" : "<gray>calme");
        Player c = s.ia.cible == null ? null : Bukkit.getPlayer(s.ia.cible);
        if (c != null) b.append(" <gray>· cible <white>").append(c.getName());
        b.append(" <gray>· mode ").append(s.ia.mode);
        if (s.ia.enCours != null) b.append(" · attaque ").append(s.ia.enCours.nom());
        if (s.ia.munitions >= 0) b.append(" · munitions ").append(s.ia.munitions);
        if (!s.ia.conditions.isEmpty()) b.append(" · conditions ").append(s.ia.conditions);
        if (s.sansButin) b.append(" · relevé (sans butin)");
        for (Map.Entry<UUID, Double> x : s.ia.menace.entrySet()) {
            Player p = Bukkit.getPlayer(x.getKey());
            b.append("\n<gray>  ").append(p == null ? x.getKey() : p.getName()).append(" : <white>").append(Math.round(x.getValue()))
                .append(" <dark_gray>(dégâts ").append(Math.round(s.ia.degats.getOrDefault(x.getKey(), 0.0))).append(")");
        }
        return b.toString();
    }
}
