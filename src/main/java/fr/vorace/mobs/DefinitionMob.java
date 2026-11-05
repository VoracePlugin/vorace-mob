package fr.vorace.mobs;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Un mob de Vorace (fichier mobs/*.yml) : entité de base, modèle animé, caractère, besoins, troupeau, laisse...
 * Sans Bukkit, sauf la lecture.
 *
 * @param type       entité du jeu qui porte le mob (sheep, cow...) ou « decor » pour un modèle posé sans entité
 * @param modeles    modèles animés possibles (un tiré au hasard) ; vide = apparence du jeu
 * @param ia         « vorace » (cerveau de Vorace), « jeu » (IA du jeu, gardée en laisse) ou « aucune »
 * @param laisse     distance maximale à la maison (blocs)
 * @param cohesion   distance maximale au centre du troupeau (0 = solitaire)
 * @param detection  distance à laquelle un joueur est remarqué
 * @param nourriture blocs ou plantes mangés (noms du jeu, « zone » = points « nourriture » de la zone)
 * @param animations rôles (manger, boire, dormir, alerte, envol, ennui...) vers noms d'animations du modèle
 * @param ennui      animations jouées de temps en temps au repos
 * @param envol      distance à laquelle il s'envole quand un joueur approche (décor ou volant ; 0 = jamais)
 * @param vol        « oiseau » ou « papillon » : le mob vole (sans gravité, piloté par Vorace) ; null = au sol
 * @param altMin     hauteur de vol minimale au-dessus du sol (volants)
 * @param altMax     hauteur de vol maximale
 * @param marche     volants : vitesse de marche au sol (blocs/s) quand il est posé ; 0 = il reste sur place
 */
public record DefinitionMob(String id, String nom, String type, List<String> modeles, double tailleMin, double tailleMax,
                            double vie, double vitesse, double degats, Cerveau.Caractere caractere, String ia, double laisse,
                            String zone, Cerveau.Activite activite, double cohesion, double detection, List<String> nourriture,
                            boolean boit, boolean silencieux, Map<String, String> animations, List<String> ennui, double envol,
                            String vol, double altMin, double altMax, double marche) {

    public boolean decor() { return type.equals("decor"); }
    public boolean cerveau() { return ia.equals("vorace") && !decor() && vol == null; }
    public boolean volant() { return vol != null && !decor(); }

    /** Animation du modèle pour un rôle (« manger »...), ou null. */
    public String animation(String role) { return animations.get(role); }

    public static DefinitionMob lire(String id, ConfigurationSection s, List<String> problemes) {
        String type = s.getString("type", "pig").toLowerCase(Locale.ROOT).replace("minecraft:", "");
        List<String> modeles = new ArrayList<>();
        if (s.isList("modele")) for (Object o : s.getList("modele")) modeles.add(o.toString().toLowerCase(Locale.ROOT));
        else if (s.isString("modele")) modeles.add(s.getString("modele").toLowerCase(Locale.ROOT));
        if (type.equals("decor") && modeles.isEmpty()) { problemes.add("mob " + id + " : un décor a besoin d'un modèle"); return null; }
        double tMin = 1, tMax = 1;
        if (s.isList("taille")) {
            List<?> t = s.getList("taille");
            tMin = Zone.num(t.get(0));
            tMax = t.size() > 1 ? Zone.num(t.get(1)) : tMin;
        } else if (s.contains("taille")) {
            tMin = tMax = s.getDouble("taille", 1);
        }
        Cerveau.Caractere car;
        try {
            car = Cerveau.Caractere.valueOf(s.getString("caractere", "paisible").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problemes.add("mob " + id + " : caractère inconnu « " + s.getString("caractere") + " » (paisible, craintif, territorial, agressif)");
            car = Cerveau.Caractere.PAISIBLE;
        }
        Cerveau.Activite act;
        try {
            act = Cerveau.Activite.valueOf(s.getString("activite", "diurne").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problemes.add("mob " + id + " : activité inconnue « " + s.getString("activite") + " » (diurne, nocturne, toujours)");
            act = Cerveau.Activite.DIURNE;
        }
        String ia = s.getString("ia", "vorace").toLowerCase(Locale.ROOT);
        if (!List.of("vorace", "jeu", "aucune").contains(ia)) { problemes.add("mob " + id + " : ia inconnue « " + ia + " » (vorace, jeu, aucune)"); ia = "vorace"; }
        double cohesion = 0;
        if (s.isConfigurationSection("troupeau")) cohesion = s.getDouble("troupeau.cohesion", 6);
        else if (s.isBoolean("troupeau")) cohesion = s.getBoolean("troupeau") ? 6 : 0;
        else if (s.contains("troupeau")) cohesion = s.getDouble("troupeau", 0);
        List<String> nourriture = new ArrayList<>();
        for (String n : s.getStringList("nourriture")) nourriture.add(n.toLowerCase(Locale.ROOT).replace("minecraft:", ""));
        Map<String, String> anims = new LinkedHashMap<>();
        List<String> ennui = new ArrayList<>();
        ConfigurationSection a = s.getConfigurationSection("animations");
        if (a != null) {
            for (String k : a.getKeys(false)) {
                if (k.equals("ennui")) {
                    if (a.isList(k)) for (Object o : a.getList(k)) ennui.add(o.toString().toLowerCase(Locale.ROOT));
                    else ennui.add(a.getString(k, "").toLowerCase(Locale.ROOT));
                } else {
                    anims.put(k.toLowerCase(Locale.ROOT), a.getString(k, "").toLowerCase(Locale.ROOT));
                }
            }
        }
        return new DefinitionMob(id, s.getString("nom"), type, List.copyOf(modeles), Math.max(0.05, tMin), Math.max(Math.max(0.05, tMin), tMax),
            s.getDouble("vie", 0), Math.max(0.05, s.getDouble("vitesse", 1)), s.getDouble("degats", 2), car, ia,
            Math.max(2, s.getDouble("laisse", 24)), s.getString("zone"), act, Math.max(0, cohesion), Math.max(1, s.getDouble("detection", 10)),
            List.copyOf(nourriture), s.getBoolean("boit", true), s.getBoolean("silencieux", false), Map.copyOf(anims), List.copyOf(ennui),
            Math.max(0, s.getDouble("envol", 0)), volLu(id, s, problemes), alt(s, 0), alt(s, 1), Math.max(0, s.getDouble("marche", 0)));
    }

    private static String volLu(String id, ConfigurationSection s, List<String> problemes) {
        String v = s.getString("vol");
        if (v == null) return null;
        v = v.toLowerCase(Locale.ROOT);
        if (!List.of("oiseau", "papillon").contains(v)) { problemes.add("mob " + id + " : vol inconnu « " + v + " » (oiseau, papillon)"); return "oiseau"; }
        return v;
    }

    private static double alt(ConfigurationSection s, int k) {
        boolean pap = "papillon".equalsIgnoreCase(s.getString("vol"));
        double[] def = pap ? new double[]{0.5, 2.5} : new double[]{4, 12};
        if (s.isList("altitude")) {
            List<?> l = s.getList("altitude");
            try { return Zone.num(l.get(Math.min(k, l.size() - 1))); } catch (RuntimeException e) { return def[k]; }
        }
        return def[k];
    }
}
