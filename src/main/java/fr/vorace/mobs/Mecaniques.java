package fr.vorace.mobs;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * Mécaniques propres à un monstre, dans son bloc {@code combat:} (bestiaire de Babel) :
 *
 * <pre>
 * combat:
 *   a-la-mort: { invocation: e1_crane_qui_court, nombre: 1 }       # à sa mort, ces mobs naissent à sa place
 *   garde: { reduction: 0.8, angle: 120, brise-apres: 3, ouverture: 20 }   # garde de face, brisée après 3 coups gardés
 *   point-faible: { dos: true, multiplicateur: 2, fige: 80 }        # frappé dans le dos : dégâts × 2, figé 80 tics
 *   jamais-mortel: true                                             # ses coups ne font jamais tomber un joueur (pantins)
 * </pre>
 *
 * Sans Bukkit, sauf la lecture.
 */
public record Mecaniques(Invocation aLaMort, Garde garde, PointFaible pointFaible, boolean jamaisMortel) {

    public static final Mecaniques AUCUNE = new Mecaniques(null, null, null, false);

    /** Mobs qui naissent à sa mort (le crâne qui court du Squelette-mosaïque). */
    public record Invocation(String mob, int nombre) {}

    /**
     * Garde de face : un coup reçu dans l'angle {@code angle} (degrés, devant lui) perd {@code reduction} de ses dégâts ;
     * après {@code briseApres} coups gardés, la garde est brisée pendant {@code ouverture} tics (animation {@code animation}).
     */
    public record Garde(double reduction, double angle, int briseApres, int ouverture, String animation) {}

    /** Frappé dans le dos (à plus de 120° de son regard) : dégâts × {@code multiplicateur}, figé {@code fige} tics. */
    public record PointFaible(boolean dos, double multiplicateur, int fige) {}

    /** Angle (degrés, 0 à 180) entre le regard du mob (yaw) et la direction vers l'attaquant. */
    public static double angle(double yawMob, double dx, double dz) {
        double regard = Math.toRadians(yawMob);
        double fx = -Math.sin(regard), fz = Math.cos(regard);   // convention du jeu : yaw 0 = sud (+z)
        double n = Math.hypot(dx, dz);
        if (n < 1e-6) return 0;
        double c = (fx * dx + fz * dz) / n;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    /** Vrai si l'attaquant est devant lui, dans l'angle de sa garde. */
    public boolean devant(double angle) { return garde != null && angle <= garde.angle() / 2; }

    /** Vrai si l'attaquant est dans son dos (plus de 120° du regard). */
    public boolean dos(double angle) { return pointFaible != null && pointFaible.dos() && angle >= 120; }

    static Mecaniques lire(String id, ConfigurationSection s, List<String> problemes) {
        Invocation inv = null;
        ConfigurationSection m = s.getConfigurationSection("a-la-mort");
        if (m != null) {
            String mob = m.getString("invocation");
            if (mob == null) problemes.add("mob " + id + " : a-la-mort sans « invocation »");
            else inv = new Invocation(mob.toLowerCase(java.util.Locale.ROOT), Math.max(1, m.getInt("nombre", 1)));
        }
        Garde g = null;
        ConfigurationSection gs = s.getConfigurationSection("garde");
        if (gs != null) g = new Garde(Math.max(0, Math.min(1, gs.getDouble("reduction", 0.8))), Math.max(1, Math.min(360, gs.getDouble("angle", 120))),
            Math.max(0, gs.getInt("brise-apres", 3)), Math.max(0, gs.getInt("ouverture", 20)), gs.getString("animation"));
        PointFaible pf = null;
        ConfigurationSection ps = s.getConfigurationSection("point-faible");
        if (ps != null) pf = new PointFaible(ps.getBoolean("dos", true), Math.max(1, ps.getDouble("multiplicateur", 2)), Math.max(0, ps.getInt("fige", 0)));
        boolean jamais = s.getBoolean("jamais-mortel", false);
        if (inv == null && g == null && pf == null && !jamais) return AUCUNE;
        return new Mecaniques(inv, g, pf, jamais);
    }
}
