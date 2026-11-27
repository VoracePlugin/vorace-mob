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
 *   depart: g_flame_r        # os du modèle d'où part le projectile (ou une liste : un os par tir d'une salve)
 *   vitesse: 1.1             # projectile : blocs par tic
 *   anticipation: 0.8        # 0 = vise où est la cible ; 1 = vise où elle sera si elle continue tout droit
 *   salve: 3                 # projectile : nombre de tirs (le 2e anticipe plus, le 3e vise la cible elle-même)
 *   intervalle: 4            # tics entre deux tirs d'une salve
 *   zones: 3                 # zone : plusieurs cercles (le 1er sur la cible, les autres sur sa route et autour)
 *   dispersion: 4            # zone : distance des autres cercles
 *   repousse: 1.2            # repousse les joueurs touchés
 *   bond: -5                 # après le coup, le mob bondit de 5 blocs (négatif : en arrière, loin de sa cible)
 *   flaque: 60               # l'impact laisse une flaque brûlante de 60 tics (dégâts toutes les 10 tics)
 *   flaque-multiplicateur: 0.25
 *   presse: 2                # seulement s'il a reçu 2 coups dans les 3 dernières secondes (par défaut 2 pour un bond en
 *                            # arrière, 0 sinon) : le joueur a le temps de le frapper avant qu'il se dégage
 *   lourde: true             # attaque lourde : une seule à la fois par joueur dans un groupe (par défaut : salve, plusieurs
 *                            # zones, flaque, repousse ou bond)
 *   effets:                  # modèles animés (plugins/Vorace/modeles-animes) à la place des particules
 *     marque: fx_cire_marque       # zone annoncée au sol (animations « t<tics> », rayon 1 bloc agrandi au rayon)
 *     projectile: fx_cire_goutte   # animation « vol »
 *     impact: fx_cire_impact       # animation « impact »
 *     pluie: fx_cire_pluie         # animation « pluie », pendant le temps actif d'une zone
 *     flaque: fx_cire_flaque       # animations « d<tics> »
 *   couleur: "#ff7a1a"       # couleur de la zone marquée et du projectile
 *   poids: 1                 # chance d'être choisie parmi les attaques prêtes
 *   annonce: "..."           # texte affiché aux joueurs (attaques de boss)
 *   fixation: hasard         # boss : la cible de l'attaque est choisie par le script (hasard, loin, proche), marquée
 *   vie-max: 0.6             # seulement sous 60 % de Vie
 *   interruptible: true      # un contrôle pendant le signal l'annule
 *   animation-frappe: 7      # tic de l'animation où le geste frappe : elle est retardée pour qu'il tombe à la fin du signal
 *   second-coup: { tic: 28, forme: cone, angle: 120, rayon: 2.2, multiplicateur: 0.5 }   # un 2e coup au tic 28 de l'attaque
 *   munitions: 3             # elle use une munition du mob ({@code combat.munitions}) ; à zéro, elle ne part plus
 *   apres-munitions: 3       # la recharge : seulement quand le mob n'a plus de munitions, elle les lui rend
 *   invocation: { mob: e1_ame_enragee, nombre: 3, max: 6, total: 24 }   # au coup, ces mobs naissent autour de lui (6 en
 *                            # même temps au plus, 24 sur tout le combat)
 *   phase-max: 1             # seulement jusqu'à cette phase (0 : avant la première)
 *   soin-allie: { cible: cire, seuil: 0.3, soin: 0.4 }   # soigne un allié de cette famille (combat.famille) sous 30 % de
 *                            # Vie, à sa portée, de 40 % de sa Vie ; seulement s'il y en a un
 *   interrompu-par: 2        # 2 coups reçus pendant le signal l'interrompent
 * </pre>
 */
public record Competence(String id, String nom, String animation, Forme forme, double porteeMin, double porteeMax,
                         double rayon, double largeur, String type, boolean magique, double multiplicateur, int signal,
                         int actif, int recuperation, int recharge, String alteration, double duree, List<String> departs,
                         int couleur, double poids, String annonce, String fixation, double vieMax, boolean interruptible,
                         int phase, double vitesse, double anticipation, int salve, int intervalle, int zones,
                         double dispersion, double repousse, double bond, int flaque, double flaqueMult, Effets effets,
                         int presse, boolean lourde, int animationFrappe, SecondCoup secondCoup, boolean munitions,
                         boolean rechargeMunitions, Invocation invocation, int phaseMax, SoinAllie soinAllie, int interrompuPar) {

    /** Mobs qui naissent autour de lui au coup ({@code max} en même temps, {@code total} sur le combat ; 0 : sans limite). */
    public record Invocation(String mob, int nombre, int max, int total) {
        static Invocation lire(ConfigurationSection s) {
            if (s == null || s.getString("mob") == null) return null;
            return new Invocation(s.getString("mob").toLowerCase(Locale.ROOT), Math.max(1, s.getInt("nombre", 1)), Math.max(0, s.getInt("max", 0)),
                Math.max(0, s.getInt("total", 0)));
        }
    }

    /** Soin d'un allié de la famille {@code cible} sous {@code seuil} de sa Vie : {@code soin} de sa Vie maximale. */
    public record SoinAllie(String cible, double seuil, double soin) {
        static SoinAllie lire(ConfigurationSection s) {
            if (s == null) return null;
            return new SoinAllie(s.getString("cible", "").toLowerCase(Locale.ROOT), Math.max(0, Math.min(1, s.getDouble("seuil", 0.3))),
                Math.max(0, s.getDouble("soin", 0.4)));
        }
    }

    /**
     * Deuxième coup d'une attaque (Double coup de massue) : au tic {@code tic} depuis son début, une autre forme (cône,
     * cercle ou ligne) frappe avec {@code multiplicateur}.
     */
    public record SecondCoup(int tic, Forme forme, double rayon, double largeur, double multiplicateur) {
        static SecondCoup lire(String mob, String id, ConfigurationSection s, Competence.Forme defaut, double rayon, List<String> problemes) {
            if (s == null) return null;
            Forme f = Forme.lire(s.getString("forme"));
            if (f == null) f = defaut;
            if (f == Forme.PROJECTILE || f == Forme.ZONE) {
                problemes.add("mob " + mob + " : attaque « " + id + " » : second-coup en cone, cercle, ligne ou contact seulement");
                f = Forme.CONE;
            }
            double larg = s.contains("angle") ? s.getDouble("angle") : s.getDouble("largeur", f == Forme.CONE ? 90 : 1);
            return new SecondCoup(Math.max(1, s.getInt("tic", 20)), f, Math.max(0.5, s.getDouble("rayon", rayon > 0 ? rayon : 2.5)),
                Math.max(0, larg), Math.max(0, s.getDouble("multiplicateur", 0.5)));
        }
    }

    /** Modèles animés des effets d'une attaque (null : particules). */
    public record Effets(String marque, String projectile, String impact, String pluie, String flaque) {
        static final Effets AUCUN = new Effets(null, null, null, null, null);

        static Effets lire(ConfigurationSection s) {
            if (s == null) return AUCUN;
            return new Effets(s.getString("marque"), s.getString("projectile"), s.getString("impact"), s.getString("pluie"), s.getString("flaque"));
        }
    }

    /** Os de départ du tir n° k d'une salve (ils tournent), ou null. */
    public String depart(int k) {
        return departs.isEmpty() ? null : departs.get(Math.floorMod(k, departs.size()));
    }

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

    /**
     * Tics d'attente avant de jouer l'animation : avec « animation-frappe » (le tic de l'animation où le geste frappe),
     * elle part plus tard pour que ce geste tombe à la fin du signal, quand le coup touche. 0 : elle part tout de suite.
     */
    public int departAnimation() { return animationFrappe > 0 && signal > animationFrappe ? signal - animationFrappe : 0; }

    /** Durée totale (signal + actif + récupération), en tics. */
    public int total() {
        int t = signal + actif + recuperation + (salve - 1) * intervalle;
        return secondCoup == null ? t : Math.max(t, secondCoup.tic());
    }

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
        // un cercle autour de lui se déclenche quand la cible est bien dedans (ou à la portée écrite)
        if (f == Forme.CERCLE && !s.contains("portee")) pMax = Math.max(0.5, s.getDouble("rayon", 3) - 0.4);
        String type = s.getString("type", d.type());
        boolean magique = s.getBoolean("magique", d.magique());
        double largeur = s.contains("angle") ? s.getDouble("angle") : s.getDouble("largeur", f == Forme.CONE ? 90 : 1);
        double rayon = s.getDouble("rayon", switch (f) {
            case CONTACT -> 0;
            case PROJECTILE -> 1.5;
            case ZONE, CERCLE -> 3;
            case CONE, LIGNE -> Math.max(3, pMax);
        });
        int salve = Math.max(1, s.getInt("salve", 1)), zones = Math.max(1, s.getInt("zones", 1)), flaque = Math.max(0, s.getInt("flaque", 0));
        double repousse = Math.max(0, s.getDouble("repousse", 0)), bond = s.getDouble("bond", 0);
        boolean lourde = s.getBoolean("lourde", salve > 1 || zones > 1 || flaque > 0 || repousse > 0 || bond != 0);
        return new Competence(id, s.getString("nom", coupDeBase ? "coup de base" : id),
            s.getString("animation", coupDeBase ? null : null), f, Math.max(0, pMin), Math.max(pMin, pMax), Math.max(0, rayon),
            Math.max(0, largeur), type, magique, s.getDouble("multiplicateur", s.getDouble("mult", 1)),
            Math.max(0, s.getInt("signal", coupDeBase ? 9 : 12)), Math.max(0, s.getInt("actif", 1)),
            Math.max(0, s.getInt("recuperation", s.getInt("recup", coupDeBase ? 6 : 10))),
            Math.max(1, s.getInt("recharge", coupDeBase ? 24 : 60)), s.getString("alteration"), s.getDouble("duree", 0),
            s.isList("depart") ? List.copyOf(s.getStringList("depart")) : s.getString("depart") == null ? List.of() : List.of(s.getString("depart")),
            couleur(s.getString("couleur"), couleurDuType(type)), Math.max(0, s.getDouble("poids", 1)),
            s.getString("annonce"), s.getString("fixation"), s.getDouble("vie-max", 1.0), s.getBoolean("interruptible", true),
            Math.max(0, s.getInt("phase", 0)), Math.max(0.2, s.getDouble("vitesse", 1.0)),
            Math.max(0, Math.min(2, s.getDouble("anticipation", f == Forme.PROJECTILE ? 0.7 : 0.8))), Math.max(1, s.getInt("salve", 1)),
            Math.max(1, s.getInt("intervalle", 4)), Math.max(1, s.getInt("zones", 1)), Math.max(0, s.getDouble("dispersion", rayon * 1.4)),
            Math.max(0, s.getDouble("repousse", 0)), s.getDouble("bond", 0), Math.max(0, s.getInt("flaque", 0)),
            Math.max(0, s.getDouble("flaque-multiplicateur", 0.25)), Effets.lire(s.getConfigurationSection("effets")),
            Math.max(0, s.getInt("presse", bond < 0 ? 2 : 0)), lourde, Math.max(0, s.getInt("animation-frappe", 0)),
            SecondCoup.lire(mob, id, s.getConfigurationSection("second-coup"), f, rayon, problemes),
            s.isBoolean("munitions") ? s.getBoolean("munitions") : s.getInt("munitions", 0) > 0,
            s.isBoolean("apres-munitions") ? s.getBoolean("apres-munitions") : s.getInt("apres-munitions", 0) > 0,
            Invocation.lire(s.getConfigurationSection("invocation")), s.contains("phase-max") ? Math.max(0, s.getInt("phase-max")) : -1,
            SoinAllie.lire(s.getConfigurationSection("soin-allie")), Math.max(0, s.getInt("interrompu-par", 0)));
    }
}
