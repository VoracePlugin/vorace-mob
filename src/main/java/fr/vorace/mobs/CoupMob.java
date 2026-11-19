package fr.vorace.mobs;

/**
 * Le coup qu'un mob de combat est en train de porter, lu par le plugin du combat pendant l'événement de dégâts
 * ({@link VoraceMobAPI#coupEnCours}).
 *
 * @param puissance      Puissance du mob (sa stat {@code puissance_phys} ou {@code puissance_mag})
 * @param magique        coup magique (Résistance magique de la cible) ou physique
 * @param type           type d'attaque (« Fire », « Impact »...)
 * @param multiplicateur multiplicateur du coup (1 pour le coup de base)
 * @param alteration     altération donnée par le coup (nom libre), ou null
 * @param duree          durée de l'altération (secondes)
 * @param nom            nom du coup (« coup de base », « Goutte ardente »...)
 */
public record CoupMob(double puissance, boolean magique, String type, double multiplicateur, String alteration,
                      double duree, String nom) {

    /** Le coup de base d'un mob de combat. */
    public static CoupMob deBase(Combat c) {
        return new CoupMob(c.stat(c.magique() ? "puissance_mag" : "puissance_phys"), c.magique(), c.type(), 1, null, 0, "coup de base");
    }
}
