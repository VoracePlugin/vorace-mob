package fr.vorace.mobs;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
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
 *   barre: true          # barre de vie au-dessus du mob, en combat
 *   nom-visible: false   # vrai : nom et niveau au-dessus du mob (par défaut, seulement sa barre de vie)
 *   stats: { resistance: 12, armure: 3, res_mag: 0, puissance_phys: 75, puissance_mag: 66, crit: 0.03, esquive: 0.02 }
 *   vie-groupe: 0.7      # Vie × (1 + 0,7 × (joueurs − 1)) selon les joueurs verrouillés à l'engagement (0 : jamais)
 *   degats-groupe: {1: 0.4, 2: 0.7}   # Puissance de ses coups × ce facteur selon les joueurs (absents : × 1)
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
                     Map<String, Double> stats, int niveauBase, Map<String, Double> progression, double xp, IA ia,
                     Competence coupDeBase, List<Competence> competences, List<Lot> butin, List<Phase> phases, int enrage,
                     boolean nomVisible, double vieGroupe, Map<Integer, Double> degatsGroupe, Mecaniques meca,
                     List<LotSpecial> butinSpecial) {

    /** Distance (blocs) à laquelle un joueur est verrouillé dans le combat d'un mob qui suit la taille du groupe. */
    public static final double RAYON_GROUPE = 30;

    /** Vrai si sa Vie ou ses dégâts suivent la taille du groupe ({@code vie-groupe} ou {@code degats-groupe}). */
    public boolean suitLeGroupe() { return vieGroupe > 0 || !degatsGroupe.isEmpty(); }

    /** Multiplicateur de Vie pour {@code joueurs} joueurs verrouillés : 1 + vie-groupe × (joueurs − 1). */
    public double facteurVie(int joueurs) { return 1 + vieGroupe * (Math.max(1, joueurs) - 1); }

    /** Multiplicateur de la Puissance de ses coups pour {@code joueurs} joueurs (1 si ce nombre n'est pas écrit). */
    public double facteurDegats(int joueurs) {
        Double f = degatsGroupe.get(Math.max(1, joueurs));
        return f == null ? 1 : f;
    }

    /** Valeurs reprises par les attaques qui ne les écrivent pas. */
    record Defauts(String type, boolean magique) {}

    /**
     * Comportement en combat ({@code combat.ia}).
     *
     * @param role      contact (va au corps à corps) ou distance (garde ses distances, tourne autour de sa cible)
     * @param aggro     distance de vue : le premier joueur vu à cette distance devient sa cible
     * @param laisse    distance à son point de départ au-delà de laquelle il abandonne et rentre (Vie rendue)
     * @param distMin   distance la plus courte qu'il accepte (distance : il recule en dessous)
     * @param distMax   distance la plus longue (au-delà, il se rapproche)
     * @param entraide  les mobs du même type à cette distance le rejoignent quand il entre en combat
     * @param vitesse   multiplicateur de ses déplacements en combat
     * @param feinte    chance de se décaler (pas chassés) entre deux attaques, de 0 à 1
     * @param cadence   à plusieurs sur le même joueur (rangs normal et élite) : tics au moins entre deux débuts d'attaque
     *                  du groupe sur ce joueur (le coup de base n'est pas compté)
     * @param repit     à plusieurs : tics au moins entre la fin d'une attaque lourde et la suivante sur ce joueur
     * @param recul     multiplicateur de sa vitesse quand il recule (distance)
     * @param souffle   tics de recul d'affilée avant d'être essoufflé : il tient alors sa place une fois et demie ce temps
     */
    public record IA(String role, double aggro, double laisse, double distMin, double distMax, double entraide, double vitesse,
                     double feinte, int cadence, int repit, double recul, int souffle) {
        public boolean distance() { return role.equals("distance"); }
    }

    /** Une ligne de butin : objet (« minecraft:diamond », « vorace:rubis »), chance de 0 à 1, nombre entre min et max. */
    public record Lot(String objet, double chance, int min, int max) {}

    /**
     * Une ligne de {@code butin-special} : {@code chasseur} (pièce prélevable : elle ne tombe pas, elle revient au métier
     * du Chasseur par {@link VoraceMobMortEvent#getPiecesPrelevables}), {@code si} (condition notée pendant le combat :
     * {@code garde_brisee}, {@code meche_eteinte}... ; une condition {@code par_...} compte : chance × le nombre de fois,
     * 1 au plus) ou {@code premierKill} (une seule fois par joueur et par mob).
     */
    public record LotSpecial(Lot lot, boolean chasseur, String si, boolean premierKill) {
        /** Chance de la ligne pour {@code fois} occurrences de sa condition (0 si la condition n'est pas remplie). */
        public double chance(int fois) {
            if (si == null) return lot.chance();
            if (fois <= 0) return 0;
            return si.startsWith("par_") ? Math.min(1, lot.chance() * fois) : lot.chance();
        }
    }

    /**
     * Phase d'un élite ++ ou d'un boss : sous {@code seuil} de Vie (0,6 = 60 %), elle commence une fois.
     *
     * @param invocation   mob invoqué autour de lui (ou null), {@code nombre} exemplaires
     * @param vitesse      multiplicateur de vitesse à partir de cette phase
     * @param annonce      texte montré aux joueurs du combat
     * @param animation    animation jouée au changement de phase
     * @param animations   rôles d'animation remplacés à partir de cette phase ({@code animations: {garde: idle_a_pied,
     *                     marche-combat: marche_a_pied}}) : son repos et sa marche changent avec la phase
     */
    public record Phase(double seuil, String nom, String invocation, int nombre, double vitesse, String annonce, String animation,
                        Map<String, String> animations) {}
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

    /** Stat écrite (au niveau de base). */
    public double stat(String cle) {
        Double v = stats.get(cle.toLowerCase(Locale.ROOT));
        return v == null ? 0 : v;
    }

    /** Facteur de progression d'une stat pour ce niveau : 1 + progression × (niveau − niveau de base), 0,1 au moins. */
    public double facteur(String famille, int niveau) {
        Double p = progression.get(famille);
        if (p == null || p == 0) return 1;
        return Math.max(0.1, 1 + p * (niveau - niveauBase));
    }

    /** Famille de progression d'une stat : vie, puissance, defense ou rien (crit, esquive, vitesse : inchangées). */
    static String famille(String cle) {
        String k = cle.toLowerCase(Locale.ROOT);
        if (k.startsWith("puissance") || k.endsWith("-damage") || k.startsWith("degats")) return "puissance";
        if (k.startsWith("resistance") || k.startsWith("res_") || k.startsWith("armure")) return "defense";
        return null;
    }

    /** Stat au niveau du mob (puissances et défenses suivent la progression). */
    public double stat(String cle, int niveau) {
        double v = stat(cle);
        String f = famille(cle);
        return f == null ? v : v * facteur(f, niveau);
    }

    /** Vie maximale au niveau du mob. */
    public double vieAuNiveau(int niveau) { return vie * facteur("vie", niveau); }

    /** XP au niveau du mob (avant l'écart de niveau avec le joueur, appliqué par le plugin du combat). */
    public double xpAuNiveau(int niveau) { return xp * facteur("xp", niveau); }

    /** Toutes les stats au niveau du mob. */
    public Map<String, Double> statsAuNiveau(int niveau) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String k : stats.keySet()) m.put(k, stat(k, niveau));
        return Map.copyOf(m);
    }

    public Competence competence(String id) {
        if (coupDeBase != null && coupDeBase.id().equals(id)) return coupDeBase;
        for (Competence c : competences) if (c.id().equals(id)) return c;
        return null;
    }

    /** Progression par défaut (par niveau d'écart avec le niveau de base). */
    static final Map<String, Double> PROGRESSION = Map.of("vie", 0.10, "puissance", 0.06, "defense", 0.05, "xp", 0.10);

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
        String type = s.getString("type", "Impact");
        boolean magique = s.getBoolean("magique", false);
        Map<String, Double> prog = new LinkedHashMap<>(PROGRESSION);
        ConfigurationSection pr = s.getConfigurationSection("progression");
        if (pr != null) for (String k : pr.getKeys(false)) prog.put(k.toLowerCase(Locale.ROOT), pr.getDouble(k));
        ConfigurationSection i = s.getConfigurationSection("ia");
        String role = i == null ? "contact" : i.getString("role", "contact").toLowerCase(Locale.ROOT);
        if (!role.equals("contact") && !role.equals("distance")) {
            problemes.add("mob " + id + " : ia.role inconnu « " + role + " » (contact ou distance)");
            role = "contact";
        }
        double dMin = role.equals("distance") ? 6 : 0, dMax = role.equals("distance") ? 11 : 2;
        if (i != null && i.isList("distance")) {
            List<?> l = i.getList("distance");
            try { dMin = Zone.num(l.get(0)); dMax = l.size() > 1 ? Zone.num(l.get(1)) : dMin; } catch (RuntimeException e) {
                problemes.add("mob " + id + " : ia.distance mal écrite ([6, 11])");
            }
        }
        IA ia = new IA(role, i == null ? 12 : i.getDouble("aggro", 12), i == null ? 24 : i.getDouble("laisse", 24),
            Math.max(0, dMin), Math.max(dMin, dMax), i == null ? 8 : i.getDouble("entraide", 8),
            i == null ? 1 : i.getDouble("vitesse", 1), i == null ? 0.35 : i.getDouble("feinte", 0.35),
            Math.max(0, i == null ? 24 : i.getInt("cadence", 24)), Math.max(0, i == null ? 80 : i.getInt("repit", 80)),
            Math.max(0.1, i == null ? 0.85 : i.getDouble("recul", 0.85)), Math.max(0, i == null ? 40 : i.getInt("souffle", 40)));
        Defauts d = new Defauts(type, magique);
        Competence base = s.contains("coup-de-base") && !s.isConfigurationSection("coup-de-base") && !s.getBoolean("coup-de-base", true) ? null
            : Competence.lire(id, "coup_de_base", s.isConfigurationSection("coup-de-base") ? s.getConfigurationSection("coup-de-base")
                : new org.bukkit.configuration.MemoryConfiguration(), d, true, problemes);
        List<Competence> comps = new ArrayList<>();
        ConfigurationSection cs = s.getConfigurationSection("competences");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                Competence c = Competence.lire(id, k, cs.getConfigurationSection(k), d, false, problemes);
                if (c != null) comps.add(c);
            }
        }
        List<Lot> butin = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("butin")) {
            Lot l = lot(id, m, problemes);
            if (l != null) butin.add(l);
        }
        List<LotSpecial> special = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("butin-special")) {
            Lot l = lot(id, m, problemes);
            if (l == null) continue;
            Object si = m.get("si");
            special.add(new LotSpecial(l, Boolean.parseBoolean(String.valueOf(m.get("chasseur"))),
                si == null ? null : si.toString().toLowerCase(Locale.ROOT).trim(), Boolean.parseBoolean(String.valueOf(m.get("premier-kill")))));
        }
        List<Phase> phases = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("phases")) {
            try {
                double seuil = Zone.num(m.get("seuil"));
                if (seuil >= 1) continue;   // la phase 1 (100 %) est celle de départ
                Object inv = m.get("invocation");
                String mobInv = null;
                int nombre = 0;
                if (inv instanceof Map<?, ?> im) { mobInv = String.valueOf(im.get("mob")); nombre = im.get("nombre") == null ? 1 : (int) Zone.num(im.get("nombre")); }
                else if (inv != null) { mobInv = inv.toString(); nombre = 1; }
                phases.add(new Phase(seuil, m.get("nom") == null ? null : m.get("nom").toString(), mobInv, nombre,
                    m.get("vitesse") == null ? 1 : Zone.num(m.get("vitesse")), m.get("annonce") == null ? null : m.get("annonce").toString(),
                    m.get("animation") == null ? null : m.get("animation").toString(), rolesDePhase(m.get("animations"))));
            } catch (RuntimeException e) {
                problemes.add("mob " + id + " : phase mal écrite (seuil: 0.6 attendu)");
            }
        }
        phases.sort((x, y) -> Double.compare(y.seuil(), x.seuil()));
        return new Combat(nMin, nMax, vie, rang, type, magique, s.getBoolean("barre", true), Map.copyOf(stats),
            s.getInt("niveau-base", nMin), Map.copyOf(prog), Math.max(0, s.getDouble("xp", 0)), ia, base, List.copyOf(comps),
            List.copyOf(butin), List.copyOf(phases), Math.max(0, s.getInt("enrage", 0)), s.getBoolean("nom-visible", false),
            Math.max(0, s.getDouble("vie-groupe", 0)), degatsGroupe(id, s, problemes), Mecaniques.lire(id, s, problemes),
            List.copyOf(special));
    }

    /** Une ligne de butin ({@code objet}, {@code chance}, {@code nombre}) ; null si elle n'a pas d'objet. */
    static Lot lot(String id, Map<?, ?> m, List<String> problemes) {
        Object o = m.get("objet");
        if (o == null) { problemes.add("mob " + id + " : ligne de butin sans « objet »"); return null; }
        int a = 1, b = 1;
        Object n = m.get("nombre");
        try {
            if (n instanceof List<?> l && !l.isEmpty()) { a = (int) Zone.num(l.get(0)); b = l.size() > 1 ? (int) Zone.num(l.get(1)) : a; }
            else if (n != null) a = b = (int) Zone.num(n);
        } catch (RuntimeException e) { problemes.add("mob " + id + " : butin « " + o + " » : nombre mal écrit"); }
        double ch = 1;
        try { if (m.get("chance") != null) ch = Zone.num(m.get("chance")); } catch (RuntimeException e) { problemes.add("mob " + id + " : butin « " + o + " » : chance mal écrite"); }
        return new Lot(o.toString(), Math.max(0, Math.min(1, ch)), Math.max(0, a), Math.max(Math.max(0, a), b));
    }

    /** {@code animations: {garde: idle_a_pied}} d'une phase : rôle → animation (vide si absent). */
    static Map<String, String> rolesDePhase(Object o) {
        if (o == null) return Map.of();
        Map<String, String> r = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> x : m.entrySet()) if (x.getValue() != null) r.put(x.getKey().toString().toLowerCase(Locale.ROOT), x.getValue().toString().toLowerCase(Locale.ROOT));
        } else if (o instanceof ConfigurationSection c) {
            for (String k : c.getKeys(false)) if (c.getString(k) != null) r.put(k.toLowerCase(Locale.ROOT), c.getString(k).toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(r);
    }

    /** {@code degats-groupe: {1: 0.4, 2: 0.7}} : nombre de joueurs → facteur de Puissance. */
    static Map<Integer, Double> degatsGroupe(String id, ConfigurationSection s, List<String> problemes) {
        ConfigurationSection g = s.getConfigurationSection("degats-groupe");
        if (g == null) {
            if (s.contains("degats-groupe")) problemes.add("mob " + id + " : degats-groupe mal écrit ({1: 0.4, 2: 0.7})");
            return Map.of();
        }
        Map<Integer, Double> m = new java.util.TreeMap<>();
        for (String k : g.getKeys(false)) {
            try {
                int n = Integer.parseInt(k.trim());
                double f = g.getDouble(k, -1);
                if (n < 1 || f <= 0) throw new NumberFormatException();
                m.put(n, f);
            } catch (NumberFormatException e) {
                problemes.add("mob " + id + " : degats-groupe « " + k + " » mal écrit (joueurs: facteur, ex. 1: 0.4)");
            }
        }
        return Map.copyOf(m);
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
