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

    /** Effraie un mob (0 à 1) : il fuit, et son troupeau avec lui s'il a assez peur. Faux si ce n'en est pas un. */
    public static boolean effrayerMob(Entity e, double peur) {
        Mobs m = mobs();
        Mobs.Suivi s = m == null ? null : m.suivi(e);
        if (s == null) return false;
        s.cerveau().effrayer(peur);
        return true;
    }
}
