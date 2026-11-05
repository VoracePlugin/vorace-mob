package fr.vorace.mobs;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Cerveau d'un mob (sans Bukkit) : des besoins qui montent avec le temps (faim, soif, fatigue), des émotions
 * (peur, colère) et un choix d'action par « utilité » : chaque action reçoit une note selon la situation et la
 * meilleure l'emporte. L'action en cours garde un bonus tant qu'elle n'est pas finie, pour ne pas changer d'avis
 * à chaque instant.
 */
public final class Cerveau {

    public enum Caractere {
        /** Ne fuit que s'il est frappé ou bousculé (moutons, vaches). */
        PAISIBLE,
        /** Fuit les joueurs qui approchent, surtout en courant (lapins, cerfs, oiseaux). */
        CRAINTIF,
        /** Prévient, puis attaque si on reste trop près ou si on le frappe (sangliers, chèvres). */
        TERRITORIAL,
        /** Attaque les joueurs qu'il remarque (loups, monstres). */
        AGRESSIF
    }

    public enum Activite { DIURNE, NOCTURNE, TOUJOURS }

    public enum Action { REPOS, ERRER, MANGER, BOIRE, DORMIR, REJOINDRE, RENTRER, FUIR, ATTAQUER }

    /**
     * Ce que le mob sait de sa situation.
     * @param distMaison    distance à sa maison (blocs)
     * @param horsZone      hors de sa zone
     * @param distTroupeau  distance au centre de son troupeau (NaN : seul)
     * @param distMenace    distance du joueur le plus proche (NaN : aucun)
     * @param menaceRapide  ce joueur court ou fonce vers lui
     * @param menaceDiscrete ce joueur avance accroupi (remarqué deux fois moins loin)
     * @param nuit          il fait nuit
     * @param peutManger    de la nourriture est connue à portée
     * @param peutBoire     de l'eau est connue à portée
     * @param surPlace      le mob est arrivé là où son action se fait (mangeoire, point d'eau...)
     */
    public record Perception(double distMaison, double laisse, boolean horsZone, double distTroupeau, double cohesion,
                             double distMenace, boolean menaceRapide, boolean menaceDiscrete, double detection,
                             boolean nuit, boolean peutManger, boolean peutBoire, boolean surPlace) {}

    /** Secondes pour passer de rassasié à affamé, de désaltéré à assoiffé, de reposé à épuisé. */
    static final double FAIM = 600, SOIF = 900, FATIGUE = 1500;

    private final Random hasard;
    double faim, soif, fatigue, peur, colere;
    private Action action = Action.REPOS;
    private double temps, duree = 5;
    private boolean nouvelle;
    private final Map<Action, Double> derniers = new EnumMap<>(Action.class);

    public Cerveau(long graine) {
        hasard = new Random(graine);
        faim = hasard.nextDouble() * 0.4;
        soif = hasard.nextDouble() * 0.4;
        fatigue = hasard.nextDouble() * 0.3;
    }

    public Action action() { return action; }
    public double faim() { return faim; }
    public double soif() { return soif; }
    public double fatigue() { return fatigue; }
    public double peur() { return peur; }
    public double colere() { return colere; }
    /** Secondes depuis le début de l'action. */
    public double temps() { return temps; }
    /** Vrai juste après un changement d'action (lu par penser()). */
    public boolean nouvelle() { return nouvelle; }
    /** Notes du dernier choix (pour /vorace mob info). */
    public Map<Action, Double> notes() { return derniers; }

    /** Frappé : un mob paisible ou craintif prend peur, un territorial ou agressif se fâche. */
    public void blesser(Caractere c) {
        if (c == Caractere.TERRITORIAL || c == Caractere.AGRESSIF) colere = 1;
        else peur = 1;
    }

    /** Un compagnon du troupeau a peur : la peur se propage (débandade). */
    public void effrayer(double v) {
        peur = Math.max(peur, Math.min(1, v));
    }

    static boolean horsActivite(Activite a, boolean nuit) {
        return a == Activite.DIURNE ? nuit : a == Activite.NOCTURNE && !nuit;
    }

    /** Fait passer le temps (dt secondes) et choisit l'action. */
    public Action penser(Perception p, double dt, Caractere c, Activite a) {
        // besoins
        boolean actif = action != Action.DORMIR && action != Action.REPOS;
        faim = borne(faim + dt / FAIM);
        soif = borne(soif + dt / SOIF);
        fatigue = borne(fatigue + dt / FATIGUE * (actif ? 1.5 : 0.5));
        if (p.surPlace()) {
            if (action == Action.MANGER) faim = borne(faim - dt / 15);
            if (action == Action.BOIRE) soif = borne(soif - dt / 8);
        }
        if (action == Action.DORMIR) fatigue = borne(fatigue - dt / 200);

        // émotions
        boolean menace = !Double.isNaN(p.distMenace());
        double portee = p.detection() * (menace && p.menaceDiscrete() ? 0.5 : 1);
        if (menace) {
            double d = p.distMenace();
            switch (c) {
                case CRAINTIF -> {
                    if (d < portee) peur = Math.max(peur, p.menaceRapide() ? 1 : 0.3 + 0.7 * (1 - d / portee));
                }
                case PAISIBLE -> {
                    if (d < 3 && p.menaceRapide()) peur = Math.max(peur, 0.6);
                }
                case TERRITORIAL -> {
                    if (d < 4) colere = borne(colere + dt * 0.12); // un joueur qui reste trop près finit par le fâcher
                }
                case AGRESSIF -> {
                    if (d < portee) colere = 1;
                }
            }
        }
        boolean proche = menace && p.distMenace() < portee;
        peur = borne(peur - dt / (proche && c == Caractere.CRAINTIF ? 30 : 6));
        colere = borne(colere - dt / (menace && p.distMenace() < p.detection() * 1.5 ? 25 : 5));

        // choix
        temps += dt;
        Map<Action, Double> s = scores(p, c, a);
        Action meilleure = action;
        double note = -1;
        for (Map.Entry<Action, Double> e : s.entrySet()) if (e.getValue() > note) { note = e.getValue(); meilleure = e.getKey(); }
        derniers.clear();
        derniers.putAll(s);
        nouvelle = meilleure != action;
        if (nouvelle) {
            action = meilleure;
            temps = 0;
            duree = switch (meilleure) {
                case REPOS -> 3 + hasard.nextDouble() * 5;
                case ERRER -> 6 + hasard.nextDouble() * 8;
                default -> 600;
            };
        }
        return action;
    }

    /** Vrai quand l'action en cours a atteint son but. */
    boolean finie(Perception p, Activite a) {
        return switch (action) {
            case REPOS, ERRER -> temps >= duree;
            case MANGER -> faim < 0.05 || !p.peutManger();
            case BOIRE -> soif < 0.05 || !p.peutBoire();
            case DORMIR -> fatigue < 0.05 && !horsActivite(a, p.nuit());
            case REJOINDRE -> Double.isNaN(p.distTroupeau()) || p.distTroupeau() <= p.cohesion() * 0.6;
            case RENTRER -> p.distMaison() <= p.laisse() * 0.6 && !p.horsZone();
            case FUIR -> peur < 0.1;
            case ATTAQUER -> colere < 0.2 || Double.isNaN(p.distMenace());
        };
    }

    /** Note de chaque action (0 à environ 1). */
    Map<Action, Double> scores(Perception p, Caractere c, Activite a) {
        Map<Action, Double> s = new EnumMap<>(Action.class);
        boolean menace = !Double.isNaN(p.distMenace());
        s.put(Action.REPOS, 0.3 + hasard.nextDouble() * 0.2);
        s.put(Action.ERRER, 0.3 + hasard.nextDouble() * 0.2);
        s.put(Action.FUIR, peur > 0.15 ? 0.55 + 0.45 * peur : 0);
        s.put(Action.ATTAQUER, colere > 0.3 && menace && p.distMenace() < p.detection() * 2 ? 0.6 + 0.4 * colere : 0);
        double rentrer = p.horsZone() ? 0.9 : p.distMaison() > p.laisse() ? 0.92 : p.distMaison() > 0.85 * p.laisse() ? 0.45 : 0;
        s.put(Action.RENTRER, rentrer);
        boolean calme = peur <= 0.2 && colere <= 0.3;
        double dormir = (horsActivite(a, p.nuit()) ? 0.8 : 0) + fatigue * fatigue * 0.8;
        s.put(Action.DORMIR, calme ? Math.min(1, dormir) : 0);
        s.put(Action.MANGER, calme && p.peutManger() && faim > 0.35 ? 0.3 + 0.6 * faim : 0);
        s.put(Action.BOIRE, calme && p.peutBoire() && soif > 0.35 ? 0.3 + 0.6 * soif : 0);
        double dt = p.distTroupeau();
        s.put(Action.REJOINDRE, !Double.isNaN(dt) && p.cohesion() > 0 && dt > p.cohesion() ? 0.6 + Math.min(0.3, (dt - p.cohesion()) / p.cohesion() * 0.15) : 0);
        // l'action en cours garde un bonus tant qu'elle n'est pas finie (et encore possible) ; repos et errance alternent
        boolean finie = finie(p, a);
        switch (action) {
            case REPOS, ERRER -> s.merge(action, finie ? -0.15 : 0.1, Double::sum);
            case FUIR, ATTAQUER -> { if (!finie) s.merge(action, 0.25, Double::sum); }
            default -> {
                boolean possible = switch (action) {
                    case MANGER -> calme && p.peutManger();
                    case BOIRE -> calme && p.peutBoire();
                    case DORMIR -> calme;
                    default -> true;
                };
                s.put(action, !finie && possible ? Math.max(s.get(action), 0.55) + 0.25 : 0.0);
            }
        }
        return s;
    }

    /** « faim;soif;fatigue » pour la sauvegarde dans l'entité. */
    public String sauver() {
        return String.format(Locale.ROOT, "%.3f;%.3f;%.3f", faim, soif, fatigue);
    }

    public void relire(String s) {
        if (s == null) return;
        String[] p = s.split(";");
        try {
            if (p.length > 0) faim = borne(Double.parseDouble(p[0]));
            if (p.length > 1) soif = borne(Double.parseDouble(p[1]));
            if (p.length > 2) fatigue = borne(Double.parseDouble(p[2]));
        } catch (NumberFormatException ignore) { }
    }

    private static double borne(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
