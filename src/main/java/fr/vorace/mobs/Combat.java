package fr.vorace.mobs;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bloc {@code combat:} d'un mob : niveau, Vie, rang et stats libres. vorace-mob garde la Vie du mob (elle peut dépasser
 * la santé maximale du jeu, 1 024) ; les stats sont lues par le plugin du combat du serveur (formule de dégâts,
 * esquive...) avec {@link VoraceMobAPI#stat}. Sans Bukkit, sauf la lecture.
 *
 * <pre>
 * combat:
 *   niveau: 9            # ou [8, 10] : tiré au hasard à l'apparition
 *   vie: 330
 *   rang: normal         # normal, elite, elite++, mini-boss ou boss
 *   type: Fire           # type d'attaque de son coup de base (libre, lu par le plugin du combat)
 *   magique: false       # coup de base magique (Puissance magique) ou physique
 *   barre: true          # nom, niveau et barre de vie au-dessus du mob
 *   stats: { resistance: 12, armure: 3, res_mag: 0, puissance_phys: 75, puissance_mag: 66, crit: 0.03, esquive: 0.02 }
 * </pre>
 *
 * @param niveauMin niveau le plus bas tiré à l'apparition
 * @param niveauMax niveau le plus haut
 * @param vie       Vie maximale
 * @param rang      rang du mob (règles de boss et d'élite, couleur du nom)
 * @param type      type d'attaque du coup de base
 * @param magique   coup de base magique
 * @param barre     afficher nom, niveau et barre de vie
 * @param stats     stats libres (clés en minuscules)
 */
public record Combat(int niveauMin, int niveauMax, double vie, Rang rang, String type, boolean magique, boolean barre,
                     Map<String, Double> stats) {

    /** Rangs des mobs, du plus faible au plus fort. */
    public enum Rang {
        NORMAL("normal", "<white>", ""),
        ELITE("elite", "<gold>", "★ "),
        ELITE_PP("elite++", "<light_purple>", "★★ "),
        MINI_BOSS("mini-boss", "<red>", "☠ "),
        BOSS("boss", "<dark_red>", "☠ ");

        private final String nom, couleur, marque;

        Rang(String nom, String couleur, String marque) {
            this.nom = nom;
            this.couleur = couleur;
            this.marque = marque;
        }

        public String nom() { return nom; }
        public String couleur() { return couleur; }
        public String marque() { return marque; }
        /** Règles de boss (pas de prise, pas de repousse...) : mini-boss et boss. */
        public boolean boss() { return this == MINI_BOSS || this == BOSS; }
        /** Élite et élite ++ . */
        public boolean elite() { return this == ELITE || this == ELITE_PP; }

        /** « elite++ », « elite_pp », « élite », « mini-boss », « boss_fin »... ; null si inconnu. */
        public static Rang lire(String s) {
            if (s == null) return NORMAL;
            String t = s.toLowerCase(Locale.ROOT).replace('é', 'e').replace('_', '-').replace(' ', '-').trim();
            if (t.equals("elite++") || t.equals("elite-pp") || t.equals("elitepp")) return ELITE_PP;
            if (t.equals("elite")) return ELITE;
            if (t.startsWith("mini-boss") || t.equals("miniboss")) return MINI_BOSS;
            if (t.startsWith("boss")) return BOSS;
            if (t.equals("normal") || t.equals("essaim") || t.equals("invocation") || t.equals("pnj")) return NORMAL;
            return null;
        }
    }

    public double stat(String cle) {
        Double v = stats.get(cle.toLowerCase(Locale.ROOT));
        return v == null ? 0 : v;
    }

    /** Niveau tiré entre le plus bas et le plus haut (u de 0 à 1). */
    public int tirerNiveau(double u) {
        return niveauMin + (int) Math.min(niveauMax - niveauMin, Math.floor(u * (niveauMax - niveauMin + 1)));
    }

    /** Lit le bloc {@code combat:} ; null s'il n'y en a pas ou s'il n'a pas de Vie. */
    public static Combat lire(String id, ConfigurationSection s, List<String> problemes) {
        if (s == null) return null;
        int nMin = 1, nMax = 1;
        if (s.isList("niveau")) {
            List<?> l = s.getList("niveau");
            try {
                nMin = (int) Zone.num(l.get(0));
                nMax = l.size() > 1 ? (int) Zone.num(l.get(1)) : nMin;
            } catch (RuntimeException e) {
                problemes.add("mob " + id + " : niveau mal écrit (9 ou [8, 10])");
            }
        } else {
            nMin = nMax = s.getInt("niveau", 1);
        }
        nMin = Math.max(1, nMin);
        nMax = Math.max(nMin, nMax);
        double vie = s.getDouble("vie", 0);
        if (vie <= 0) {
            problemes.add("mob " + id + " : combat sans « vie » (ignoré)");
            return null;
        }
        Rang rang = Rang.lire(s.getString("rang", "normal"));
        if (rang == null) {
            problemes.add("mob " + id + " : rang inconnu « " + s.getString("rang") + " » (normal, elite, elite++, mini-boss, boss)");
            rang = Rang.NORMAL;
        }
        Map<String, Double> stats = new LinkedHashMap<>();
        ConfigurationSection st = s.getConfigurationSection("stats");
        if (st != null) {
            for (String k : st.getKeys(false)) {
                if (st.isDouble(k) || st.isInt(k) || st.isLong(k)) stats.put(k.toLowerCase(Locale.ROOT), st.getDouble(k));
                else problemes.add("mob " + id + " : stat « " + k + " » n'est pas un nombre");
            }
        }
        return new Combat(nMin, nMax, vie, rang, s.getString("type", "Impact"), s.getBoolean("magique", false),
            s.getBoolean("barre", true), Map.copyOf(stats));
    }

    /**
     * Santé du jeu que doit avoir l'entité pour montrer cette Vie (sur une santé maximale de {@code max}). Un mob encore
     * en vie garde au moins 0,5 ; à 0 de Vie, rien.
     */
    public static double santeDuJeu(double vie, double vieMax, double max) {
        if (vie <= 0) return 0;
        return Math.max(0.5, Math.min(max, max * vie / vieMax));
    }

    /**
     * Barre de vie en texte MiniMessage : {@code cases} traits, verts au-dessus de 50 %, jaunes au-dessus de 25 %, rouges
     * en dessous ; le reste en gris foncé.
     */
    public static String barre(double vie, double vieMax, int cases) {
        double r = vieMax <= 0 ? 0 : Math.max(0, Math.min(1, vie / vieMax));
        int pleines = vie > 0 ? Math.max(1, (int) Math.round(r * cases)) : 0;
        String c = r > 0.5 ? "<#5ed35e>" : r > 0.25 ? "<#e8c547>" : "<#e04848>";
        return c + "|".repeat(pleines) + "<#3a3a3a>" + "|".repeat(cases - pleines);
    }
}
