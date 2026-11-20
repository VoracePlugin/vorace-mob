package fr.vorace.mobs;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Point d'apparition (fichier apparitions/*.yml) : garde une population de mobs autour d'un endroit, en fait
 * réapparaître après une mort, et leur donne cet endroit pour maison.
 *
 * @param mobs         mobs possibles (un tiré au hasard pour chaque apparition)
 * @param rayon        rayon où ils apparaissent
 * @param nombre       population gardée
 * @param troupeau     tous du même troupeau
 * @param laisse       laisse autour du centre (0 = celle du mob)
 * @param reapparition secondes avant de remplacer un mob mort
 * @param niveau       niveau des mobs de combat qui apparaissent ici (0 = celui du mob, tiré selon sa fiche)
 * @param cycle        0 : un mob mort est remplacé seul, après {@code reapparition} ; sinon <b>cycle</b> en secondes : tous les
 *                     mobs manquants reviennent ensemble, {@code cycle} secondes après la première mort du cycle
 */
public record Apparition(String id, List<String> mobs, String monde, double x, double y, double z, double rayon, int nombre,
                         boolean troupeau, double laisse, double reapparition, String zone, int niveau, double cycle) {

    /**
     * Le cycle est-il écoulé ? {@code debutManque} : instant (ms) où le premier mob a manqué, 0 si rien ne manque.
     * Toujours faux sans cycle.
     */
    public boolean vagueDue(long maintenant, long debutManque) {
        return cycle > 0 && debutManque > 0 && maintenant - debutManque >= cycle * 1000;
    }

    /**
     * <pre>
     * paturage_nord:
     *   mob: mouton                # ou [mouton, mouton_noir]
     *   monde: world
     *   centre: [120, 64, -40]
     *   rayon: 6
     *   nombre: 5
     *   troupeau: true
     *   laisse: 20
     *   reapparition: 600
     *   niveau: 12                 # facultatif : niveau imposé aux mobs de combat
     *   cycle: 120                 # facultatif : les mobs morts reviennent tous ensemble, 120 s après la première mort
     * </pre>
     */
    public static Apparition lire(String id, ConfigurationSection s, List<String> problemes) {
        List<String> mobs = new ArrayList<>();
        if (s.isList("mob")) for (Object o : s.getList("mob")) mobs.add(o.toString().toLowerCase(Locale.ROOT));
        else if (s.isString("mob")) mobs.add(s.getString("mob").toLowerCase(Locale.ROOT));
        if (mobs.isEmpty()) { problemes.add("apparition " + id + " : « mob » manquant"); return null; }
        List<?> c = s.getList("centre");
        if (c == null || c.size() < 3) { problemes.add("apparition " + id + " : « centre: [x, y, z] » manquant"); return null; }
        try {
            return new Apparition(id, List.copyOf(mobs), s.getString("monde", "world"), Zone.num(c.get(0)), Zone.num(c.get(1)), Zone.num(c.get(2)),
                Math.max(0, s.getDouble("rayon", 4)), Math.max(0, s.getInt("nombre", 1)), s.getBoolean("troupeau", false),
                Math.max(0, s.getDouble("laisse", 0)), Math.max(0, s.getDouble("reapparition", 300)), s.getString("zone"),
                Math.max(0, s.getInt("niveau", 0)), Math.max(0, s.getDouble("cycle", 0)));
        } catch (RuntimeException e) {
            problemes.add("apparition " + id + " : centre mal écrit");
            return null;
        }
    }
}
