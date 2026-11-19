package fr.vorace.mobs;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;

/**
 * Une attaque de mob de combat (bloc {@code combat.competences.<id>} ou {@code combat.coup-de-base}). Elle se joue en
 * trois temps, calés sur son animation : <b>signal</b> (l'animation démarre, la zone est marquée au sol et se remplit :
 * on a le temps de sortir), <b>actif</b> (le coup tombe), <b>récupération</b> (le mob reste exposé). Temps en tics.
 *
 * <pre>
 * goutte_ardente:
 *   nom: "Goutte ardente"
 *   animation: atk_goutte_ardente
 *   forme: projectile        # contact, projectile, zone, cercle (autour de lui), cone, ligne
 *   portee: [6, 14]          # distance à la cible pour la lancer
 *   rayon: 1.5               # zone touchée (projectile, zone, cercle) ; cone : longueur ; ligne : longueur
 *   largeur: 1               # ligne : largeur ; cone : angle en degrés (angle: 90)
 *   type: Fire
 *   magique: true
 *   multiplicateur: 1.0
 *   signal: 14
 *   actif: 1
 *   recuperation: 8
 *   recharge: 40             # depuis le début de l'attaque
 *   alteration: Brulure
 *   duree: 3
 *   depart: g_flame_r        # os du modèle d'où part le projectile
 *   couleur: "#ff7a1a"       # couleur de la zone marquée et du projectile
 *   poids: 1                 # chance d'être choisie parmi les attaques prêtes
 *   annonce: "..."           # texte affiché aux joueurs (attaques de boss)
 *   fixation: hasard         # boss : la cible de l'attaque est choisie par le script (hasard, loin, proche), marquée
 *   vie-max: 0.6             # seulement sous 60 % de Vie
 *   interruptible: true      # un contrôle pendant le signal l'annule
 * </pre>
 */
public record Competence(String id, String nom, String animation, Forme forme, double porteeMin, double porteeMax,
                         double rayon, double largeur, String type, boolean magique, double multiplicateur, int signal,
                         int actif, int recuperation, int recharge, String alteration, double duree, String depart,
                         int couleur, double poids, String annonce, String fixation, double vieMax, boolean interruptible,
                         int phase) {

    /** Formes d'attaque. */
    public enum Forme {
        /** Coup au corps à corps sur la cible (portée et angle de 120° devant lui). */ CONTACT,
        /** Projectile en cloche vers la cible, zone d'impact marquée au sol pendant le vol. */ PROJECTILE,
        /** Cercle posé sur la cible au début du signal. */ ZONE,
        /** Cercle autour du mob. */ CERCLE,
        /** Cône devant le mob. */ CONE,
        /** Ligne devant le mob. */ LIGNE;

        static Forme lire(String s) {
            if (s == null) return null;
            String t = s.toLowerCase(Locale.ROOT).replace('ô', 'o').replace('è', 'e').trim();
            return switch (t) {
                case "contact", "melee", "mêlée", "corps-a-corps" -> CONTACT;
                case "projectile", "tir" -> PROJECTILE;
                case "zone", "sol" -> ZONE;
                case "cercle", "autour" -> CERCLE;
                case "cone" -> CONE;
                case "ligne" -> LIGNE;
                default -> null;
            };
        }
    }

    /** Durée totale (signal + actif + récupération), en tics. */
    public int total() { return signal + actif + recuperation; }

    /** Couleur RGB de la zone marquée par défaut selon le type. */
    static int couleurDuType(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.contains("fire") || t.contains("feu")) return 0xFF7A1A;
        if (t.contains("ice") || t.contains("glace")) return 0x8FD8FF;
        if (t.contains("dark") || t.contains("ombre")) return 0x8A4FD8;
        if (t.contains("holy") || t.contains("light")) return 0xFFE27A;
        if (t.contains("shock") || t.contains("foudre")) return 0xF5F06A;
        if (t.contains("poison") || t.contains("nature")) return 0x7BD34F;
        return 0xE04040;
    }

    static int couleur(String s, int defaut) {
        if (s == null || s.isBlank()) return defaut;
        try {
            return Integer.parseInt(s.trim().replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return defaut;
        }
    }

    /**
     * Lit une attaque. {@code coupDeBase} : valeurs par défaut du coup au contact (animation « attack », portée 2,5,
     * signal 9, récupération 6, recharge 24). Null si la section est absente ou fausse.
     */
    static Competence lire(String mob, String id, ConfigurationSection s, Combat.Defauts d, boolean coupDeBase, List<String> problemes) {
        if (s == null) return null;
        Forme f = Forme.lire(s.getString("forme", coupDeBase ? "contact" : null));
        if (f == null) {
            problemes.add("mob " + mob + " : attaque « " + id + " » sans forme connue (contact, projectile, zone, cercle, cone, ligne)");
            return null;
        }
        double pMin = 0, pMax = coupDeBase ? 2.5 : 3;
        if (s.isList("portee")) {
            List<?> l = s.getList("portee");
            try {
                pMin = Zone.num(l.get(0));
                pMax = l.size() > 1 ? Zone.num(l.get(1)) : pMin;
                if (l.size() == 1) pMin = 0;
            } catch (RuntimeException e) {
                problemes.add("mob " + mob + " : attaque « " + id + " » : portee mal écrite ([6, 14] ou 3)");
            }
        } else if (s.contains("portee")) {
            pMax = s.getDouble("portee", pMax);
        }
        String type = s.getString("type", d.type());
        boolean magique = s.getBoolean("magique", d.magique());
        double largeur = s.contains("angle") ? s.getDouble("angle") : s.getDouble("largeur", f == Forme.CONE ? 90 : 1);
        double rayon = s.getDouble("rayon", switch (f) {
            case CONTACT -> 0;
            case PROJECTILE -> 1.5;
            case ZONE, CERCLE -> 3;
            case CONE, LIGNE -> Math.max(3, pMax);
        });
        return new Competence(id, s.getString("nom", coupDeBase ? "coup de base" : id),
            s.getString("animation", coupDeBase ? null : null), f, Math.max(0, pMin), Math.max(pMin, pMax), Math.max(0, rayon),
            Math.max(0, largeur), type, magique, s.getDouble("multiplicateur", s.getDouble("mult", 1)),
            Math.max(0, s.getInt("signal", coupDeBase ? 9 : 12)), Math.max(0, s.getInt("actif", 1)),
            Math.max(0, s.getInt("recuperation", s.getInt("recup", coupDeBase ? 6 : 10))),
            Math.max(1, s.getInt("recharge", coupDeBase ? 24 : 60)), s.getString("alteration"), s.getDouble("duree", 0),
            s.getString("depart"), couleur(s.getString("couleur"), couleurDuType(type)), Math.max(0, s.getDouble("poids", 1)),
            s.getString("annonce"), s.getString("fixation"), s.getDouble("vie-max", 1.0), s.getBoolean("interruptible", true),
            Math.max(0, s.getInt("phase", 0)));
    }
}
