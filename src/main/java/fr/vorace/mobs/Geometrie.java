package fr.vorace.mobs;

import java.util.Random;
import java.util.function.BiPredicate;

/** Choix des destinations sur le plan (x, z) : errance, fuite, retour, en restant dans la laisse (sans Bukkit). */
public final class Geometrie {

    private Geometrie() {}

    public static double distance(double x1, double z1, double x2, double z2) {
        return Math.hypot(x1 - x2, z1 - z2);
    }

    /**
     * Point d'errance à 4 à 10 blocs du mob, dans la laisse autour de la maison et accepté par le filtre (zone, sol...).
     * Si rien ne convient, un point au hasard près de la maison.
     */
    public static double[] errance(double x, double z, double mx, double mz, double laisse, Random r, BiPredicate<Double, Double> accepte) {
        for (int essai = 0; essai < 12; essai++) {
            double a = r.nextDouble() * Math.PI * 2, d = 4 + r.nextDouble() * 6;
            double nx = x + Math.cos(a) * d, nz = z + Math.sin(a) * d;
            if (distance(nx, nz, mx, mz) <= laisse * 0.9 && accepte.test(nx, nz)) return new double[]{nx, nz};
        }
        return versMaison(mx, mz, laisse, r);
    }

    /** Point au hasard à moins de la moitié de la laisse autour de la maison. */
    public static double[] versMaison(double mx, double mz, double laisse, Random r) {
        double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * laisse * 0.5;
        return new double[]{mx + Math.cos(a) * d, mz + Math.sin(a) * d};
    }

    /**
     * Point de fuite : à l'opposé de la menace, à « longueur » blocs. Si ce point sort de la laisse, essaie des
     * directions de biais (45°, 90°, 135°) ; sinon reste sur le bord de la laisse, le plus loin possible de la menace.
     */
    public static double[] fuite(double x, double z, double menX, double menZ, double mx, double mz, double laisse, double longueur) {
        double ax = x - menX, az = z - menZ;
        double n = Math.hypot(ax, az);
        if (n < 1e-6) { ax = 1; az = 0; n = 1; }
        ax /= n; az /= n;
        double[] meilleur = null;
        double loin = -1;
        for (double angle : new double[]{0, 45, -45, 90, -90, 135, -135}) {
            double rad = Math.toRadians(angle);
            double dx = ax * Math.cos(rad) - az * Math.sin(rad), dz = ax * Math.sin(rad) + az * Math.cos(rad);
            double nx = x + dx * longueur, nz = z + dz * longueur;
            if (distance(nx, nz, mx, mz) > laisse) {
                // ramené sur le bord de la laisse
                double bx = nx - mx, bz = nz - mz, bn = Math.hypot(bx, bz);
                nx = mx + bx / bn * laisse * 0.95;
                nz = mz + bz / bn * laisse * 0.95;
            }
            double d = distance(nx, nz, menX, menZ);
            if (angle == 0 && distance(x + dx * longueur, z + dz * longueur, mx, mz) <= laisse) return new double[]{nx, nz};
            if (d > loin) { loin = d; meilleur = new double[]{nx, nz}; }
        }
        return meilleur;
    }

    /** Centre (moyenne) de points (x, z) ; null si aucun. */
    public static double[] centre(double[] xs, double[] zs, int n) {
        if (n <= 0) return null;
        double sx = 0, sz = 0;
        for (int i = 0; i < n; i++) { sx += xs[i]; sz += zs[i]; }
        return new double[]{sx / n, sz / n};
    }
}
