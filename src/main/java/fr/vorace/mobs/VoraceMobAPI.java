package fr.vorace.mobs;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Locale;

/**
 * API de vorace-mob pour les autres plugins (dépendance : com.github.VoracePlugin:vorace-mob).
 * Événement : {@link VoraceMobActionEvent} à chaque changement d'action d'un mob.
 */
public final class VoraceMobAPI {

    private static VoraceMob plugin;

    private VoraceMobAPI() { }

    static void initialiser(VoraceMob p) { plugin = p; }

    private static Mobs mobs() { return plugin == null ? null : plugin.mobs(); }

    /** Fait apparaître un mob (mobs/&lt;fichier&gt;.yml) ; sa maison est cet endroit. Null si le mob est inconnu. */
    public static Entity invoquerMob(String id, Location ou) {
        Mobs m = mobs();
        return m == null ? null : m.invoquer(id, ou, null, null);
    }

    /** Identifiant du mob de cette entité, ou null si ce n'est pas un mob de vorace-mob. */
    public static String mobVorace(Entity e) {
        Mobs m = mobs();
        Mobs.Suivi s = m == null ? null : m.suivi(e);
        return s == null ? null : s.definition().id();
    }

    /** Action en cours du mob (« errer », « manger », « fuir »...), ou null. */
    public static String actionMob(Entity e) {
        Mobs m = mobs();
        Mobs.Suivi s = m == null ? null : m.suivi(e);
        return s == null ? null : s.cerveau().action().name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ combat

    private static Mobs.Suivi combattant(Entity e) {
        Mobs m = mobs();
        Mobs.Suivi s = m == null ? null : m.suivi(e);
        return s != null && s.definition().combattant() ? s : null;
    }

    /** Vrai si l'entité est un mob de combat de vorace-mob (bloc {@code combat:}). */
    public static boolean estCombattant(Entity e) {
        return combattant(e) != null;
    }

    /** Niveau du mob de combat, ou 0. */
    public static int niveau(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? 0 : s.niveau;
    }

    /** Rang (« normal », « elite », « elite++ », « mini-boss », « boss »), ou null si ce n'est pas un mob de combat. */
    public static String rang(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? null : s.definition().combat().rang().nom();
    }

    /** Stat du mob de combat (« resistance », « puissance_phys »...), 0 si elle n'est pas écrite. */
    public static double stat(Entity e, String cle) {
        Mobs.Suivi s = combattant(e);
        return s == null ? 0 : s.definition().combat().stat(cle);
    }

    /** Toutes les stats écrites du mob de combat (vide si ce n'en est pas un). */
    public static java.util.Map<String, Double> stats(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? java.util.Map.of() : s.definition().combat().stats();
    }

    /** Vie actuelle du mob de combat, ou 0. */
    public static double vie(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? 0 : s.vie;
    }

    /** Vie maximale du mob de combat, ou 0. */
    public static double vieMax(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? 0 : s.vieMax;
    }

    /**
     * Retire {@code montant} de Vie au mob de combat, pendant l'événement de dégâts du coup ; renvoie les dégâts du jeu à
     * mettre dans l'événement (base, sans armure) pour que sa santé suive, ou -1 si ce n'est pas un mob de combat.
     * Hors d'un événement : {@code entite.damage(infliger(entite, montant), source)} dans le même tic.
     */
    public static double infliger(Entity e, double montant) {
        Mobs.Suivi s = combattant(e);
        return s == null ? -1 : plugin.mobs().combats().infliger(s, montant);
    }

    /** Rend de la Vie au mob de combat. Faux si ce n'en est pas un. */
    public static boolean soigner(Entity e, double montant) {
        Mobs.Suivi s = combattant(e);
        if (s == null) return false;
        plugin.mobs().combats().soigner(s, montant);
        return true;
    }

    /** Le coup que ce mob porte en ce moment (pendant l'événement de dégâts de son coup), ou null. */
    public static CoupMob coupEnCours(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? null : s.coupEnCours;
    }

    /** Effraie un mob (0 à 1) : il fuit, et son troupeau avec lui s'il a assez peur. Faux si ce n'en est pas un. */
    public static boolean effrayerMob(Entity e, double peur) {
        Mobs m = mobs();
        Mobs.Suivi s = m == null ? null : m.suivi(e);
        if (s == null) return false;
        s.cerveau().effrayer(peur);
        return true;
    }
}
