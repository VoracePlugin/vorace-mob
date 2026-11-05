package fr.vorace.mobs;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Zone où des mobs vivent (pâture, forêt, lac...) : rectangle, cercle ou polygone sur le plan (x, z), avec des
 * points d'intérêt étiquetés (« nourriture », « eau », « abri »). Sans Bukkit, sauf la lecture.
 */
public record Zone(String id, String monde, String forme, double[] xs, double[] zs, double rayon, List<Point> points) {

    /** Point d'intérêt : un endroit où trouver à manger, à boire, ou s'abriter pour la nuit. */
    public record Point(String type, double x, double y, double z) {}

    /** Vrai si (x, z) est dans la zone. */
    public boolean contient(double x, double z) {
        switch (forme) {
            case "cercle" -> {
                double dx = x - xs[0], dz = z - zs[0];
                return dx * dx + dz * dz <= rayon * rayon;
            }
            case "rectangle" -> {
                return x >= Math.min(xs[0], xs[1]) && x <= Math.max(xs[0], xs[1]) && z >= Math.min(zs[0], zs[1]) && z <= Math.max(zs[0], zs[1]);
            }
            default -> {
                // polygone : règle du nombre de croisements
                boolean dedans = false;
                for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                    if ((zs[i] > z) != (zs[j] > z) && x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i]) dedans = !dedans;
                }
                return dedans;
            }
        }
    }

    /** Centre approximatif (moyenne des sommets, ou centre du cercle). */
    public double[] centre() {
        if (forme.equals("cercle")) return new double[]{xs[0], zs[0]};
        double sx = 0, sz = 0;
        for (int i = 0; i < xs.length; i++) { sx += xs[i]; sz += zs[i]; }
        return new double[]{sx / xs.length, sz / zs.length};
    }

    /** Point d'intérêt de ce type le plus proche, ou null. */
    public Point plusProche(String type, double x, double z) {
        Point meilleur = null;
        double d = Double.MAX_VALUE;
        for (Point p : points) {
            if (!p.type().equals(type)) continue;
            double e = (p.x() - x) * (p.x() - x) + (p.z() - z) * (p.z() - z);
            if (e < d) { d = e; meilleur = p; }
        }
        return meilleur;
    }

    /**
     * <pre>
     * paturage:
     *   monde: world
     *   rectangle: [x1, z1, x2, z2]      # ou cercle: [x, z, rayon], ou polygone: [[x, z], [x, z], ...]
     *   points:
     *     - { type: eau, position: [x, y, z] }
     * </pre>
     */
    public static Zone lire(String id, ConfigurationSection s, List<String> problemes) {
        String monde = s.getString("monde", "world");
        List<Point> points = new ArrayList<>();
        for (java.util.Map<?, ?> m : s.getMapList("points")) {
            Object type = m.get("type"), pos = m.get("position");
            if (type == null || !(pos instanceof List<?> l) || l.size() < 3) { problemes.add("zone " + id + " : point mal écrit " + m); continue; }
            try {
                points.add(new Point(type.toString().toLowerCase(Locale.ROOT), num(l.get(0)), num(l.get(1)), num(l.get(2))));
            } catch (RuntimeException e) {
                problemes.add("zone " + id + " : point mal écrit " + m);
            }
        }
        try {
            if (s.isList("cercle")) {
                List<?> c = s.getList("cercle");
                return new Zone(id, monde, "cercle", new double[]{num(c.get(0))}, new double[]{num(c.get(1))}, num(c.get(2)), points);
            }
            if (s.isList("rectangle")) {
                List<?> r = s.getList("rectangle");
                return new Zone(id, monde, "rectangle", new double[]{num(r.get(0)), num(r.get(2))}, new double[]{num(r.get(1)), num(r.get(3))}, 0, points);
            }
            if (s.isList("polygone")) {
                List<?> p = s.getList("polygone");
                double[] xs = new double[p.size()], zs = new double[p.size()];
                for (int i = 0; i < p.size(); i++) {
                    List<?> c = (List<?>) p.get(i);
                    xs[i] = num(c.get(0));
                    zs[i] = num(c.get(1));
                }
                if (xs.length < 3) throw new IllegalArgumentException("au moins 3 sommets");
                return new Zone(id, monde, "polygone", xs, zs, 0, points);
            }
        } catch (RuntimeException e) {
            problemes.add("zone " + id + " : forme mal écrite (" + e.getMessage() + ")");
            return null;
        }
        problemes.add("zone " + id + " : il faut rectangle, cercle ou polygone");
        return null;
    }

    static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString().trim().replace(',', '.'));
    }
}
