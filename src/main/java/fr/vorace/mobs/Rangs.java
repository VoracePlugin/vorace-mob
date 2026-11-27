package fr.vorace.mobs;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rang porté par un objet de butin (fichiers {@code rangs/*.yml}) : chaque objet de Vorace a un rang de base et un
 * <b>spectre</b>, la chance de tomber à chaque rang. Le rang tiré est écrit sur l'objet (clés {@code vorace:rang} et
 * {@code vorace:rang_base}), son nom prend la couleur du rang et sa description le nom du rang.
 *
 * <pre>
 * objets:
 *   e1_crane_fele: { base: peu_commun, spectre: { commun: 20, peu_commun: 70, rare: 10 } }
 * </pre>
 *
 * Sans Bukkit, sauf la lecture.
 */
final class Rangs {

    /** Un rang de Babel : identifiant, nom affiché (féminin, comme sur les objets) et couleur (vorace-item, raretes.yml). */
    record Rang(String id, String nom, String couleur) {}

    static final List<Rang> TOUS = List.of(new Rang("commun", "Commune", "#c8c8c8"), new Rang("peu_commun", "Peu commune", "#3a9e5c"),
        new Rang("rare", "Rare", "#4d8dff"), new Rang("epique", "Épique", "#b05cff"), new Rang("legendaire", "Légendaire", "#ffa42e"),
        new Rang("mythique", "Mythique", "#ff4d5e"), new Rang("divin", "Divine", "#ffe680"));

    /** Rang de base et poids de chaque rang. */
    record Spectre(String base, Map<String, Double> poids) {
        /** Le rang tiré pour {@code tirage} (0 à 1). */
        String tirer(double tirage) {
            double total = 0;
            for (double p : poids.values()) total += p;
            if (total <= 0) return base;
            double r = tirage * total;
            for (Map.Entry<String, Double> x : poids.entrySet()) { r -= x.getValue(); if (r < 0) return x.getKey(); }
            return base;
        }
    }

    private final Map<String, Spectre> parObjet = new LinkedHashMap<>();

    static Rang rang(String id) {
        if (id == null) return null;
        for (Rang r : TOUS) if (r.id().equals(id)) return r;
        return null;
    }

    /** « peu commune », « Peu_commun », « épique »... → identifiant ; null si inconnu. */
    static String lire(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT).replace('é', 'e').replace(' ', '_');
        if (t.endsWith("e") && !t.equals("rare") && !t.equals("mythique") && !t.equals("epique") && !t.equals("legendaire")) t = t.substring(0, t.length() - 1);
        if (t.equals("divin")) return "divin";
        for (Rang r : TOUS) if (r.id().equals(t)) return r.id();
        return null;
    }

    /** Spectre d'un objet de Vorace (« e1_crane_fele »), ou null. */
    Spectre spectre(String objet) {
        return objet == null ? null : parObjet.get(objet.toLowerCase(Locale.ROOT));
    }

    int taille() { return parObjet.size(); }

    void vider() { parObjet.clear(); }

    /** Lit la section {@code objets:} d'un fichier de rangs. */
    void lire(String fichier, ConfigurationSection s, List<String> problemes) {
        ConfigurationSection o = s == null ? null : s.getConfigurationSection("objets");
        if (o == null) return;
        for (String id : o.getKeys(false)) {
            ConfigurationSection x = o.getConfigurationSection(id);
            String base = x == null ? null : lire(x.getString("base"));
            if (base == null) { problemes.add(fichier + " : " + id + " sans « base » connue (commun, peu_commun, rare...)"); continue; }
            Map<String, Double> poids = new LinkedHashMap<>();
            ConfigurationSection sp = x.getConfigurationSection("spectre");
            if (sp != null) {
                for (String k : sp.getKeys(false)) {
                    String r = lire(k);
                    if (r == null) { problemes.add(fichier + " : " + id + " : rang inconnu « " + k + " »"); continue; }
                    poids.put(r, Math.max(0, sp.getDouble(k)));
                }
            }
            if (poids.isEmpty()) poids.put(base, 1.0);
            // dans l'ordre des rangs (le tirage va du plus courant au plus rare)
            Map<String, Double> range = new LinkedHashMap<>();
            for (Rang r : TOUS) if (poids.containsKey(r.id())) range.put(r.id(), poids.get(r.id()));
            parObjet.put(id.toLowerCase(Locale.ROOT), new Spectre(base, Map.copyOf(range).isEmpty() ? Map.of(base, 1.0) : java.util.Collections.unmodifiableMap(range)));
        }
    }
}
