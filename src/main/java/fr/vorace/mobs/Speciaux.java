package fr.vorace.mobs;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Comportements spéciaux du bestiaire de Babel (étage 1), lus dans la configuration : réglages d'une attaque
 * ({@link Attaque}), d'un mob de combat ({@link Mob}, bloc {@code combat:}) et d'une entité de quête sans combat
 * ({@link Quete}, à la racine du mob). Le jeu est dans {@link Bestiaire}. Sans Bukkit, sauf la lecture.
 */
final class Speciaux {

    private Speciaux() { }

    private static String min(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT).trim(); }

    private static List<String> liste(ConfigurationSection s, String cle) {
        List<String> l = new ArrayList<>();
        if (s.isList(cle)) for (Object o : s.getList(cle)) l.add(min(o.toString()));
        else if (s.isString(cle)) for (String x : s.getString(cle).split("[,;]| ou ")) if (!x.isBlank()) l.add(min(x));
        return List.copyOf(l);
    }

    // ================================================================== attaques

    /** Fragment d'âme : la Vie maximale du joueur touché perd {@code vieMax} (−0,1 = −10 %) {@code duree} s, {@code cumul} fois au plus. */
    record Fragment(double vieMax, int duree, int cumul) {}

    /**
     * Réglages d'attaque du bestiaire :
     * <pre>
     * colle: 40                  # seulement si un joueur reste à sa portée 40 tics d'affilée
     * trajectoire: droite        # projectile tendu (cloche par défaut)
     * ligne-de-vue: true         # ne part jamais sans ligne de vue (projectile et zone : déjà le cas)
     * sautable: true             # un joueur en l'air au moment du coup ne le prend pas
     * cibles-min: 2              # seulement si 2 joueurs ou plus sont dans la zone
     * appel: 20                  # au coup, les mobs de combat à 20 blocs accourent sur sa cible
     * apres-jeu: 200             # seulement après 200 tics de combat (musique de l'Os-violon)
     * branches: [a, b, c]        # une animation par tir (une branche par bougie) ; une branche coupée ne tire plus
     * si: [branche_coupee, dernier_du_groupe]   # seulement si une de ces conditions est vraie
     * seulement-hors-regard: true   # seulement si personne ne le regarde
     * fragment-d-ame: { vie-max: -0.1, duree: 300, cumul: 3 }
     * immunite-projectiles: 30   # les tirs ne le touchent pas pendant 30 tics depuis le début de l'attaque
     * contre-paroi: 60           # charge : il fonce sur la ligne ; arrêté par une paroi, il est étourdi 60 tics
     * centre: lanterne           # cercle centré sur sa lanterne posée (lanterne-posee)
     * sacrifice: true            # il meurt de son coup, sans butin (âme enragée)
     * evenement-interrompu: e1.mouliere_interrompue   # envoyé au joueur qui l'interrompt (interrompu-par)
     * evenement-ratee: e1.goule_punie   # attaque ratée (personne touché) puis mob tué dans les 2 s de sa récupération
     * </pre>
     */
    record Attaque(int colle, boolean droite, boolean ligneDeVue, boolean sautable, int ciblesMin, double appel, int apresJeu,
                   List<String> branches, List<String> si, boolean horsRegard, Fragment fragment, int immuniteProjectiles,
                   int contreParoi, String centre, boolean sacrifice, String evenementInterrompu, String evenementRatee) {

        static final Attaque AUCUNE = new Attaque(0, false, true, false, 0, 0, 0, List.of(), List.of(), false, null, 0, 0, null, false, null, null);

        static Attaque lire(ConfigurationSection s) {
            Fragment fr = null;
            ConfigurationSection f = s.getConfigurationSection("fragment-d-ame");
            if (f != null) fr = new Fragment(-Math.abs(f.getDouble("vie-max", -0.1)), Math.max(1, f.getInt("duree", 300)), Math.max(1, f.getInt("cumul", 3)));
            Attaque a = new Attaque(Math.max(0, s.getInt("colle", 0)), "droite".equalsIgnoreCase(s.getString("trajectoire")),
                s.getBoolean("ligne-de-vue", true), s.getBoolean("sautable", false), Math.max(0, s.getInt("cibles-min", 0)),
                Math.max(0, s.getDouble("appel", 0)), Math.max(0, s.getInt("apres-jeu", 0)), liste(s, "branches"), liste(s, "si"),
                s.getBoolean("seulement-hors-regard", false), fr, Math.max(0, s.getInt("immunite-projectiles", 0)),
                Math.max(0, s.getInt("contre-paroi", 0)), min(s.getString("centre")), s.getBoolean("sacrifice", false),
                s.getString("evenement-interrompu"), s.getString("evenement-ratee"));
            return a.equals(AUCUNE) ? AUCUNE : a;
        }
    }

    // ================================================================== mobs de combat

    record Lueur(String os, int luminosite, int couleur) {}
    /** Faiblesses : dégâts × {@code distance} (tirs, sorts de loin), × {@code zone}, × par type ; un coup de Feu ramollit (Défense ÷ 2) {@code ramollit} tics. */
    record Faiblesse(double distance, double zone, Map<String, Double> types, int ramollit) {}
    record EteintLumiere(double rayon, int niveaux) {}
    record Manger(double rayon, double soin, int corps) {}
    record Sceau(int dos, int fige, double degatsDos, String evenement) {}
    /** Partie (sous-hitbox, os du modèle) : {@code coups} pour la casser et son {@code effet} ; {@code multiplicateur} des coups qu'elle reçoit. */
    record Partie(String nom, List<String> os, int coups, String effet, int nombre, double multiplicateur, String evenement, String condition) {}
    record PointFaiblePartie(String partie, String evenement) {}
    record Ouverture(String partie, double multiplicateur, int duree) {}
    record Leurre(String objet, int immobilisation, String evenement) {}
    record FigeePar(List<String> types, int duree, String evenement) {}
    record RegardSubi(boolean solide, int aveugleApres, int aveuglement) {}
    record Sac(int ramasseApres, int sacsMax, boolean troc, String evenement) {}
    record Vision(double devant, double derriere, double angle, double accroupi) {}
    record Invincible(double seRendA, String evenement) {}
    record Offrande(int interaction, int refusMax, String objet, String evenement) {}
    record TeteLanterne(double vie, int aveugle, int reforme, double degatsRecus, List<String> os) {}
    record LanternePosee(int phase, double vie, double reductionCorps, String objet) {}
    record Recomposition(int duree, double pieceCasseeA, double perteVie, double perteDegats) {}

    /** Réglages de mob du bestiaire (bloc {@code combat:}). */
    record Mob(Lueur lueur, Faiblesse faiblesse, EteintLumiere eteintLumiere, Manger manger, boolean perceptionMouvement,
               Sceau sceau, Map<String, Partie> parties, PointFaiblePartie pointFaiblePartie, Ouverture ouverture, Leurre leurre,
               boolean cibleLumiere, FigeePar figeePar, String finDeVie, boolean exploseEnMourant, RegardSubi regard, Sac sac,
               Vision vision, Invincible invincible, Offrande offrande, TeteLanterne teteLanterne, LanternePosee lanternePosee,
               List<Double> formes, Recomposition recomposition, double rotation, double hitboxBasse, String evenementMort) {

        static final Mob AUCUN = new Mob(null, null, null, null, false, null, Map.of(), null, null, null, false, null, null, false,
            null, null, null, null, null, null, null, List.of(), null, 0, 0, null);

        boolean rien() { return this.equals(AUCUN); }

        /** La partie dont un des os s'appelle {@code os} (sans tenir compte des majuscules ; nom contenu), ou null. */
        Partie partieDeLOs(String os) {
            if (os == null) return null;
            String o = os.toLowerCase(Locale.ROOT);
            for (Partie p : parties.values()) for (String x : p.os()) if (o.equals(x) || o.contains(x)) return p;
            return null;
        }

        static Mob lire(String id, ConfigurationSection s, List<String> problemes) {
            Lueur lu = null;
            ConfigurationSection x = s.getConfigurationSection("lueur");
            if (x != null) lu = new Lueur(x.getString("os"), Math.max(0, Math.min(15, x.getInt("luminosite", 12))),
                Competence.couleur(x.getString("couleur"), 0x38C4B6));
            Faiblesse fa = null;
            x = s.getConfigurationSection("faiblesse");
            if (x != null) {
                Map<String, Double> types = new HashMap<>();
                for (String k : x.getKeys(false)) {
                    if (List.of("distance", "zone", "ramollit").contains(k)) continue;
                    types.put(min(k), x.getDouble(k, 1));
                }
                fa = new Faiblesse(x.getDouble("distance", 1), x.getDouble("zone", 1), Map.copyOf(types), Math.max(0, x.getInt("ramollit", 0)));
            }
            EteintLumiere el = null;
            x = s.getConfigurationSection("eteint-lumiere");
            if (x != null) el = new EteintLumiere(Math.max(1, x.getDouble("rayon", 6)), Math.max(1, Math.min(15, x.getInt("niveaux", 4))));
            Manger ma = null;
            x = s.getConfigurationSection("manger");
            if (x != null) ma = new Manger(Math.max(1, x.getDouble("rayon", 6)), Math.max(0, x.getDouble("soin", 0.05)), Math.max(20, x.getInt("corps", 400)));
            Sceau sc = null;
            x = s.getConfigurationSection("sceau");
            if (x != null) sc = new Sceau(Math.max(1, x.getInt("dos", 20)), Math.max(1, x.getInt("fige", 600)), Math.max(1, x.getDouble("degats-dos", 1.5)),
                x.getString("evenement", "sceau_recolle"));
            Map<String, Partie> parties = new LinkedHashMap<>();
            x = s.getConfigurationSection("parties");
            if (x != null) {
                for (String k : x.getKeys(false)) {
                    ConfigurationSection p = x.getConfigurationSection(k);
                    if (p == null) { problemes.add("mob " + id + " : partie « " + k + " » mal écrite"); continue; }
                    List<String> os = liste(p, "os");
                    if (os.isEmpty()) os = List.of(min(k).endsWith("s") ? min(k).substring(0, min(k).length() - 1) : min(k));
                    parties.put(min(k), new Partie(min(k), os, Math.max(0, p.getInt("coups", 0)), min(p.getString("effet")),
                        Math.max(1, p.getInt("nombre", 1)), Math.max(0, p.getDouble("multiplicateur", 1)), p.getString("evenement"),
                        p.getString("condition")));
                }
            }
            PointFaiblePartie pfp = null;
            x = s.getConfigurationSection("point-faible");
            if (x != null && x.getString("partie") != null) pfp = new PointFaiblePartie(min(x.getString("partie")), x.getString("evenement"));
            Ouverture ou = null;
            x = s.getConfigurationSection("ouverture");
            if (x != null) ou = new Ouverture(min(x.getString("partie", "tete")), Math.max(1, x.getDouble("multiplicateur", 2)), Math.max(1, x.getInt("duree", 16)));
            Leurre le = null;
            x = s.getConfigurationSection("leurre");
            if (x != null) le = new Leurre(x.getString("objet", "ballot"), Math.max(0, x.getInt("immobilisation", 60)), x.getString("evenement", "ballot_ramasse"));
            FigeePar fp = null;
            x = s.getConfigurationSection("figee-par");
            if (x != null) fp = new FigeePar(liste(x, "types"), Math.max(1, x.getInt("duree", 120)), x.getString("evenement"));
            RegardSubi rg = null;
            x = s.getConfigurationSection("regard");
            if (x != null) rg = new RegardSubi(x.getBoolean("solide-si-regardee", true), Math.max(0, x.getInt("aveugle-apres", 80)), Math.max(1, x.getInt("aveuglement", 40)));
            Sac sa = null;
            x = s.getConfigurationSection("sac");
            if (x != null) sa = new Sac(Math.max(0, x.getInt("ramasse-apres", 600)), Math.max(1, x.getInt("sacs-max", 2)), x.getBoolean("troc", true),
                x.getString("evenement", "troc_glaneur"));
            Vision vi = null;
            x = s.getConfigurationSection("vision");
            if (x != null) vi = new Vision(Math.max(0, x.getDouble("devant", 12)), Math.max(0, x.getDouble("derriere", 5)),
                Math.max(1, Math.min(360, x.getDouble("angle", 120))), Math.max(0, Math.min(1, x.getDouble("accroupi", 0.5))));
            Invincible in = null;
            x = s.getConfigurationSection("invincible");
            if (x != null) in = new Invincible(Math.max(1, x.getDouble("se-rend-a", 1)), x.getString("evenement"));
            Offrande of = null;
            x = s.getConfigurationSection("offrande");
            if (x != null) of = new Offrande(Math.max(1, x.getInt("interaction", 60)), Math.max(1, x.getInt("refus-max", 3)), x.getString("objet"),
                x.getString("evenement"));
            TeteLanterne tl = null;
            x = s.getConfigurationSection("tete-lanterne");
            if (x != null) {
                List<String> os = liste(x, "os");
                tl = new TeteLanterne(Math.max(0.01, x.getDouble("vie", 0.06)), Math.max(1, x.getInt("aveugle", 120)), Math.max(20, x.getInt("reforme", 400)),
                    Math.max(1, x.getDouble("degats-recus", 1.2)), os.isEmpty() ? List.of("lanterne", "tete", "head") : os);
            }
            LanternePosee lp = null;
            x = s.getConfigurationSection("lanterne-posee");
            if (x != null) lp = new LanternePosee(Math.max(0, x.getInt("phase", 2)), Math.max(0.01, x.getDouble("vie", 0.25)),
                Math.max(0, Math.min(1, x.getDouble("reduction-corps", 0.8))), x.getString("objet", "lantern"));
            List<Double> formes = new ArrayList<>();
            if (s.isList("formes")) for (Object o : s.getList("formes")) { try { formes.add(Zone.num(o)); } catch (RuntimeException e) { problemes.add("mob " + id + " : formes mal écrites"); } }
            Recomposition rc = null;
            x = s.getConfigurationSection("recomposition");
            if (x != null) rc = new Recomposition(Math.max(1, x.getInt("duree", 60)), Math.max(0, x.getDouble("piece-cassee-a", 0.08)),
                Math.max(0, Math.min(0.9, x.getDouble("perte-vie", 0.25))), Math.max(0, Math.min(0.9, x.getDouble("perte-degats", 0.1))));
            String evMort = s.getString("a-la-mort.evenement");
            Mob m = new Mob(lu, fa, el, ma, "mouvement".equalsIgnoreCase(s.getString("perception")), sc, Map.copyOf(parties), pfp, ou, le,
                "lumiere".equalsIgnoreCase(s.getString("cible")), fp, min(s.getString("fin-de-vie")), s.getBoolean("explose-en-mourant", false),
                rg, sa, vi, in, of, tl, lp, List.copyOf(formes), rc, Math.max(0, s.getDouble("rotation", 0)), Math.max(0, s.getDouble("hitbox-basse", 0)), evMort);
            return m.equals(AUCUN) ? AUCUN : m;
        }
    }

    // ================================================================== entités de quête (racine du mob)

    /** Ce que l'entité voit : cône de {@code angle}° sur {@code portee} blocs (accroupi : {@code porteeAccroupi} ; lanterne en main : {@code porteeLanterne}). */
    record Regard(double angle, double portee, double porteeAccroupi, double porteeLanterne, boolean ligneDeVue) {}

    /** Vu : signal (tics), puis repousse le joueur de {@code repousse} blocs vers {@code vers} (point de zone) ; événement envoyé. */
    record Vu(int signal, double repousse, String vers, String evenement, String animation) {}

    /**
     * Entité de quête sans combat (tête-lanterne, Faucheuse fantôme) :
     * <pre>
     * invulnerable: true
     * regard: { angle: 25, portee: 16, portee-accroupi: 6, portee-lanterne: 24, ligne-de-vue: true }
     * vu: { signal: 20, repousse: 6, vers: le_fil, evenement: e1.faucheuse_vu, animation: hurler }
     * ronde: { points: [[236, 73], [200, 90]], vitesse: 3, boucle: false, arret: 200, fin: disparait }   # vitesse en blocs/s
     * duree-de-vie: 1400
     * </pre>
     */
    record Quete(boolean invulnerable, Regard regard, Vu vu, Mecaniques.Ronde ronde, boolean disparaitEnFin, int dureeDeVie) {

        static final Quete AUCUNE = new Quete(false, null, null, null, false, 0);

        static Quete lire(String id, ConfigurationSection s, List<String> problemes) {
            Regard rg = null;
            ConfigurationSection x = s.getConfigurationSection("regard");
            if (x != null && !x.contains("solide-si-regardee")) {
                double p = Math.max(1, x.getDouble("portee", 12));
                rg = new Regard(Math.max(1, Math.min(360, x.getDouble("angle", 90))), p, Math.max(0, x.getDouble("portee-accroupi", p)),
                    Math.max(p, x.getDouble("portee-lanterne", p)), x.getBoolean("ligne-de-vue", true));
            }
            Vu vu = null;
            x = s.getConfigurationSection("vu");
            if (x != null) vu = new Vu(Math.max(0, x.getInt("signal", 0)), Math.max(0, x.getDouble("repousse", 0)), x.getString("vers"),
                x.getString("evenement", "vu"), x.getString("animation"));
            Mecaniques.Ronde ro = null;
            boolean fin = false;
            if (s.isConfigurationSection("ronde") && s.getConfigurationSection("combat") == null) {
                ro = Mecaniques.Ronde.lire("mob " + id, s.getConfigurationSection("ronde"), problemes);
                fin = "disparait".equalsIgnoreCase(s.getString("ronde.fin"));
            }
            Quete q = new Quete(s.getBoolean("invulnerable", false), rg, vu, ro, fin, s.getConfigurationSection("combat") == null ? Math.max(0, s.getInt("duree-de-vie", 0)) : 0);
            return q.equals(AUCUNE) ? AUCUNE : q;
        }
    }
}
