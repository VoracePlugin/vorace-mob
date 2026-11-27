package fr.vorace.mobs;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mécaniques propres à un monstre, dans son bloc {@code combat:} (bestiaire de Babel) :
 *
 * <pre>
 * combat:
 *   a-la-mort: { invocation: e1_crane_qui_court, nombre: 1, depart: h_head, delai: 7 }   # à sa mort, ces mobs naissent
 *                                        # à sa place (depart : os du modèle d'où ils partent ; delai : tics après la mort)
 *   garde: { reduction: 0.8, angle: 120, brise-apres: 3, ouverture: 20 }   # garde de face, brisée après 3 coups gardés
 *   point-faible: { dos: true, multiplicateur: 2, fige: 80 }        # frappé dans le dos : dégâts × 2, figé 80 tics
 *   jamais-mortel: true                                             # ses coups ne font jamais tomber un joueur (pantins)
 *   duree-de-vie: 160                                               # il disparaît (sans butin) au bout de 160 tics
 *   fuite: { vers: tas_os, rayon: 8, blocs: [bone_block] }          # il court vers le plus proche (point de zone ou bloc)
 *   si-arrive: { releve: e1_squelette_mosaique, vie: 0.5, delai: 60 }   # arrivé : ce mob se relève là (sans butin)
 *   munitions: { nombre: 3, recharge: depot_de_cire, rayon: 12, blocs: [honeycomb_block] }   # tirs avant de recharger
 *   tertre: { assis: true, regen: 0.01, attente: 200, lever: 30 }   # assis au calme ; 1 % de Vie par seconde au calme
 *   ronde: { points: [[230, 41], [229, 60]], vitesse: 1, boucle: false, arret: 300 }   # au calme, il fait sa ronde
 * </pre>
 *
 * Sans Bukkit, sauf la lecture.
 */
public record Mecaniques(Invocation aLaMort, Garde garde, PointFaible pointFaible, boolean jamaisMortel, int dureeDeVie,
                         Fuite fuite, Releve siArrive, Munitions munitions, Tertre tertre, Ronde ronde) {

    public static final Mecaniques AUCUNE = new Mecaniques(null, null, null, false, 0, null, null, null, null, null);

    /** Mobs qui naissent à sa mort (le crâne qui court du Squelette-mosaïque), depuis l'os {@code depart}, {@code delai} tics après. */
    public record Invocation(String mob, int nombre, String depart, int delai) {
        public Invocation(String mob, int nombre) { this(mob, nombre, null, 0); }
    }

    /**
     * Garde de face : un coup reçu dans l'angle {@code angle} (degrés, devant lui) perd {@code reduction} de ses dégâts ;
     * après {@code briseApres} coups gardés, la garde est brisée pendant {@code ouverture} tics (animation {@code animation}).
     */
    public record Garde(double reduction, double angle, int briseApres, int ouverture, String animation) {}

    /** Frappé dans le dos (à plus de 120° de son regard) : dégâts × {@code multiplicateur}, figé {@code fige} tics. */
    public record PointFaible(boolean dos, double multiplicateur, int fige) {}

    /**
     * Il court vers l'endroit {@code vers} le plus proche à {@code rayon} blocs : un point d'intérêt de ce type dans les
     * zones, sinon un des {@code blocs} (noms du jeu). Sans endroit à sa portée, il fuit le joueur le plus proche.
     */
    public record Fuite(String vers, double rayon, List<String> blocs) {}

    /** Arrivé au bout de sa fuite : {@code mob} se relève là, {@code delai} tics après, avec {@code vie} de sa Vie, sans butin. */
    public record Releve(String mob, double vie, int delai) {}

    /**
     * Munitions : ses attaques marquées {@code munitions:} en usent une ; à zéro, il marche au {@code recharge} le plus
     * proche à {@code rayon} blocs (point d'intérêt de ce type, sinon un des {@code blocs}) — sur place s'il n'y en a pas —
     * et joue son attaque marquée {@code apres-munitions:} (la recharge), qui les rend toutes.
     */
    public record Munitions(int nombre, String recharge, double rayon, List<String> blocs) {}

    /**
     * Tertre (Gardien de l'Ossuaire) : assis au calme (animation « assis »), il se lève à l'engagement (animation
     * « se-lever », {@code lever} tics sans attaquer). Il ne récupère pas toute sa Vie en rentrant : seulement {@code regen}
     * de sa Vie maximale par seconde, après {@code attente} tics de calme.
     */
    public record Tertre(boolean assis, double regen, int attente, int lever) {}

    /**
     * Ronde au calme : il marche de point en point ({x, z}, ou {x, y, z}) à {@code vitesse} (multiplicateur de sa marche) ;
     * sans {@code boucle}, il fait l'aller et le retour, et s'arrête {@code arret} tics à chaque bout.
     */
    public record Ronde(List<double[]> points, double vitesse, boolean boucle, int arret) {
        /** Point suivant (indice) en partant de {@code i} dans le sens {@code sens} (1 ou −1) ; {@code [indice, nouveau sens]}. */
        public int[] suivant(int i, int sens) {
            int n = points.size();
            if (n <= 1) return new int[]{0, 1};
            if (boucle) return new int[]{Math.floorMod(i + 1, n), 1};
            int j = i + sens;
            if (j < 0 || j >= n) { sens = -sens; j = i + sens; }
            return new int[]{j, sens};
        }

        /** Vrai si {@code i} est un bout de la ronde (aller-retour) : il s'y arrête. */
        public boolean bout(int i) { return !boucle && (i == 0 || i == points.size() - 1); }

        /** Indice du point le plus proche de (x, z). */
        public int plusProche(double x, double z) {
            int best = 0;
            double d = Double.MAX_VALUE;
            for (int k = 0; k < points.size(); k++) {
                double[] p = points.get(k);
                double e = (p[0] - x) * (p[0] - x) + (p[p.length - 1] - z) * (p[p.length - 1] - z);
                if (e < d) { d = e; best = k; }
            }
            return best;
        }

        /** Lit {@code ronde: { points: [[x, z], ...], vitesse, boucle, arret }} ; null si absente ou fausse. */
        static Ronde lire(String quoi, ConfigurationSection s, List<String> problemes) {
            if (s == null) return null;
            List<double[]> pts = new ArrayList<>();
            List<?> l = s.getList("points");
            if (l != null) {
                for (Object o : l) {
                    if (!(o instanceof List<?> p) || p.size() < 2) { problemes.add(quoi + " : ronde, point mal écrit ([x, z] attendu)"); continue; }
                    try {
                        double[] v = new double[p.size() >= 3 ? 3 : 2];
                        for (int k = 0; k < v.length; k++) v[k] = Zone.num(p.get(k));
                        pts.add(v);
                    } catch (RuntimeException e) { problemes.add(quoi + " : ronde, point mal écrit ([x, z] attendu)"); }
                }
            }
            if (pts.size() < 2) { problemes.add(quoi + " : ronde avec moins de 2 points (ignorée)"); return null; }
            return new Ronde(List.copyOf(pts), Math.max(0.2, s.getDouble("vitesse", 1)), s.getBoolean("boucle", false),
                Math.max(0, s.getInt("arret", 0)));
        }
    }

    /** Angle (degrés, 0 à 180) entre le regard du mob (yaw) et la direction vers l'attaquant. */
    public static double angle(double yawMob, double dx, double dz) {
        double regard = Math.toRadians(yawMob);
        double fx = -Math.sin(regard), fz = Math.cos(regard);   // convention du jeu : yaw 0 = sud (+z)
        double n = Math.hypot(dx, dz);
        if (n < 1e-6) return 0;
        double c = (fx * dx + fz * dz) / n;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    /** Vrai si l'attaquant est devant lui, dans l'angle de sa garde. */
    public boolean devant(double angle) { return garde != null && angle <= garde.angle() / 2; }

    /** Vrai si l'attaquant est dans son dos (plus de 120° du regard). */
    public boolean dos(double angle) { return pointFaible != null && pointFaible.dos() && angle >= 120; }

    private static List<String> blocs(ConfigurationSection s, String defaut) {
        List<String> l = new ArrayList<>();
        if (s.isList("blocs")) for (String b : s.getStringList("blocs")) l.add(b.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim());
        else if (s.isString("blocs")) l.add(s.getString("blocs").toLowerCase(Locale.ROOT).replace("minecraft:", "").trim());
        else if (defaut != null) l.add(defaut);
        return List.copyOf(l);
    }

    /** Blocs par défaut d'un type d'endroit : tas d'os → bloc d'os, dépôt de cire → bloc de rayon de miel. */
    static String blocParDefaut(String vers) {
        if (vers == null) return null;
        String v = vers.toLowerCase(Locale.ROOT);
        if (v.contains("os")) return "bone_block";
        if (v.contains("cire")) return "honeycomb_block";
        return null;
    }

    static Mecaniques lire(String id, ConfigurationSection s, List<String> problemes) {
        Invocation inv = null;
        ConfigurationSection m = s.getConfigurationSection("a-la-mort");
        if (m != null) {
            String mob = m.getString("invocation");
            if (mob == null) problemes.add("mob " + id + " : a-la-mort sans « invocation »");
            else inv = new Invocation(mob.toLowerCase(Locale.ROOT), Math.max(1, m.getInt("nombre", 1)), m.getString("depart"),
                Math.max(0, m.getInt("delai", 0)));
        }
        Garde g = null;
        ConfigurationSection gs = s.getConfigurationSection("garde");
        if (gs != null) g = new Garde(Math.max(0, Math.min(1, gs.getDouble("reduction", 0.8))), Math.max(1, Math.min(360, gs.getDouble("angle", 120))),
            Math.max(0, gs.getInt("brise-apres", 3)), Math.max(0, gs.getInt("ouverture", 20)), gs.getString("animation"));
        PointFaible pf = null;
        ConfigurationSection ps = s.getConfigurationSection("point-faible");
        if (ps != null) pf = new PointFaible(ps.getBoolean("dos", true), Math.max(1, ps.getDouble("multiplicateur", 2)), Math.max(0, ps.getInt("fige", 0)));
        boolean jamais = s.getBoolean("jamais-mortel", false);
        int duree = Math.max(0, s.getInt("duree-de-vie", 0));
        Fuite fu = null;
        ConfigurationSection fs = s.getConfigurationSection("fuite");
        if (fs != null) {
            String vers = fs.getString("vers");
            fu = new Fuite(vers, Math.max(1, fs.getDouble("rayon", 8)), blocs(fs, blocParDefaut(vers)));
        }
        Releve rel = null;
        ConfigurationSection rs = s.getConfigurationSection("si-arrive");
        if (rs != null) {
            String mob = rs.getString("releve");
            if (mob == null) problemes.add("mob " + id + " : si-arrive sans « releve »");
            else rel = new Releve(mob.toLowerCase(Locale.ROOT), Math.max(0.05, Math.min(1, rs.getDouble("vie", 0.5))), Math.max(0, rs.getInt("delai", 60)));
        }
        Munitions mu = null;
        ConfigurationSection ms = s.getConfigurationSection("munitions");
        if (ms != null) {
            String rec = ms.getString("recharge");
            mu = new Munitions(Math.max(1, ms.getInt("nombre", 3)), rec, Math.max(0, ms.getDouble("rayon", 12)), blocs(ms, blocParDefaut(rec)));
        }
        Tertre te = null;
        ConfigurationSection ts = s.getConfigurationSection("tertre");
        if (ts != null) te = new Tertre(ts.getBoolean("assis", true), Math.max(0, Math.min(1, ts.getDouble("regen", 0.01))),
            Math.max(0, ts.getInt("attente", 200)), Math.max(0, ts.getInt("lever", 30)));
        Ronde ro = Ronde.lire("mob " + id, s.getConfigurationSection("ronde"), problemes);
        if (inv == null && g == null && pf == null && !jamais && duree == 0 && fu == null && rel == null && mu == null && te == null && ro == null)
            return AUCUNE;
        return new Mecaniques(inv, g, pf, jamais, duree, fu, rel, mu, te, ro);
    }
}
