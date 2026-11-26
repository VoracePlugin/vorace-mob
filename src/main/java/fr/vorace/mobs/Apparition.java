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
 * @param chance       chance (0 à 1) qu'un mob apparaisse quand c'est son tour (monstre rare) ; raté, on attend le tour suivant
 * @param groupe       points du même groupe : leur vague revient ensemble (un groupe mêlé écrit en plusieurs points)
 * @param xpMult       multiplicateur de l'XP des mobs de ce point
 * @param bonus        objet en plus dans le butin des mobs de ce point (ou null)
 * @param pauseSalle   salle de boss de Matsuri Donjon (« passeur ») : pas de vague tant qu'elle est occupée
 * @param pauseJoueur  pas de vague tant qu'un joueur est à moins de ces blocs (0 : jamais)
 * @param pauseMax     secondes au plus sans vague, même en pause (0 : sans limite)
 * @param entraide     distance d'entraide des mobs de ce point (−1 : celle du mob)
 */
public record Apparition(String id, List<String> mobs, String monde, double x, double y, double z, double rayon, int nombre,
                         boolean troupeau, double laisse, double reapparition, String zone, int niveau, double cycle,
                         double chance, String groupe, double xpMult, Combat.Lot bonus, String pauseSalle, double pauseJoueur,
                         double pauseMax, double entraide) {

    /** En pause ? {@code salleOccupee}, {@code joueurProche} ; {@code depuis} : ms depuis la dernière vague (ou la première mort). */
    public boolean enPause(boolean salleOccupee, boolean joueurProche, long depuis) {
        boolean pause = (pauseSalle != null && salleOccupee) || (pauseJoueur > 0 && joueurProche);
        return pause && (pauseMax <= 0 || depuis < pauseMax * 1000);
    }

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
     *   chance: 0.35               # facultatif : monstre rare, 35 % de chance d'apparaître à chaque tour
     *   groupe: gl02               # facultatif : les points du même groupe font leur vague ensemble
     *   xp-multiplicateur: 1.5     # facultatif : XP des mobs de ce point
     *   butin-bonus: { objet: "vorace:cristal_azur", nombre: 1 }   # facultatif : un objet en plus dans leur butin
     *   pause-si-salle: passeur    # facultatif : pas de vague tant que cette salle de boss (Matsuri Donjon) est occupée
     *   pause-si-joueur: 24        # facultatif : pas de vague tant qu'un joueur est à 24 blocs (rien n'apparaît sous ses yeux)
     *   pause-max: 1200            # facultatif : jamais plus de 1 200 s sans vague, même en pause
     *   entraide: 0                # facultatif : distance d'entraide des mobs de ce point (0 : on les prend un par un)
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
                Math.max(0, s.getInt("niveau", 0)), Math.max(0, s.getDouble("cycle", 0)),
                Math.max(0, Math.min(1, s.getDouble("chance", 1))), s.getString("groupe"), Math.max(0, s.getDouble("xp-multiplicateur", 1)),
                bonus(id, s, problemes), s.getString("pause-si-salle"), Math.max(0, s.getDouble("pause-si-joueur", 0)),
                Math.max(0, s.getDouble("pause-max", 0)), s.contains("entraide") ? Math.max(0, s.getDouble("entraide", 0)) : -1);
        } catch (RuntimeException e) {
            problemes.add("apparition " + id + " : centre mal écrit");
            return null;
        }
    }

    private static Combat.Lot bonus(String id, ConfigurationSection s, List<String> problemes) {
        ConfigurationSection b = s.getConfigurationSection("butin-bonus");
        if (b == null) return null;
        String objet = b.getString("objet");
        if (objet == null) { problemes.add("apparition " + id + " : butin-bonus sans « objet »"); return null; }
        int n = Math.max(1, b.getInt("nombre", 1));
        return new Combat.Lot(objet, Math.max(0, Math.min(1, b.getDouble("chance", 1))), n, n);
    }
}
