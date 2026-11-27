package fr.vorace.mobs;

import fr.vorace.modeles.Modeles;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Comportements du bestiaire de Babel hors du cerveau de combat : ronde au calme, Gardien assis sur son tertre, crâne qui
 * court vers un tas d'os pour relever son squelette, durée de vie, sortie d'une paroi, invocation à la mort depuis un os.
 */
final class Comportements {

    private final VoraceMob plugin;
    private final Mobs mobs;

    Comportements(VoraceMob plugin, Mobs mobs) {
        this.plugin = plugin;
        this.mobs = mobs;
    }

    private Modeles.Instance modele(Mobs.Suivi s) {
        return plugin.modeles() == null ? null : plugin.modeles().instance(s.e);
    }

    private static boolean a(Modeles.Instance m, String anim) {
        return m != null && anim != null && m.modele().animations().containsKey(anim);
    }

    /** Ronde du mob : celle de son point d'apparition, sinon la sienne ({@code combat.ronde}), ou null. */
    Mecaniques.Ronde ronde(Mobs.Suivi s) {
        Apparition a = mobs.apparition(s.apparition);
        if (a != null && a.ronde() != null) return a.ronde();
        return s.d.combattant() ? s.d.combat().meca().ronde() : null;
    }

    /** Vrai si ce mob court vers un tas d'os ({@code fuite}) : le cerveau de combat ne le mène pas. */
    boolean gere(Mobs.Suivi s) {
        return s.d.combattant() && s.d.combat().meca().fuite() != null;
    }

    // ================================================================== au calme (toutes les 0,5 s, près d'un joueur)

    /** Mob de combat au calme : ronde, ou assis sur son tertre. Vrai s'il est mené ici (l'ancien cerveau ne le mène pas). */
    boolean calme(Mobs.Suivi s, Mob mob) {
        if (!s.d.combattant()) return false;
        Mecaniques meca = s.d.combat().meca();
        if (meca.fuite() != null) return true;
        if (meca.tertre() != null) return tertre(s, mob, meca.tertre());
        Mecaniques.Ronde r = ronde(s);
        if (r != null) return ronde(s, mob, r);
        return false;
    }

    /** Assis sur son tertre : il ne bouge pas, joue « assis », et regagne sa Vie peu à peu après une attente au calme. */
    private boolean tertre(Mobs.Suivi s, Mob mob, Mecaniques.Tertre t) {
        Location maison = new Location(mob.getWorld(), s.mx, s.my, s.mz);
        if (mob.getLocation().distanceSquared(maison) > 4) {
            mob.getPathfinder().moveTo(maison, 1.0);
            s.assis = false;
            return true;
        }
        mob.getPathfinder().stopPathfinding();
        Modeles.Instance m = modele(s);
        String assis = s.d.animation("assis");
        if (t.assis() && a(m, assis) && !assis.equals(m.animation())) { m.jouerParDessus(assis, true); s.assis = true; }
        long calme = s.ia == null ? 0 : s.ia.calmeDepuis;
        if (t.regen() > 0 && s.vie < s.vieMax && mobs.tics() - calme >= t.attente()) {
            mobs.combats().soigner(s, s.vieMax * t.regen() * 0.5);
        }
        return true;
    }

    /** Ronde : de point en point ; aller-retour avec un arrêt à chaque bout. Sa maison suit (laisse et retour au calme). */
    private boolean ronde(Mobs.Suivi s, Mob mob, Mecaniques.Ronde r) {
        long t = mobs.tics();
        Location l = mob.getLocation();
        if (s.rondeIndex < 0) { s.rondeIndex = r.plusProche(l.getX(), l.getZ()); s.rondeSens = 1; }
        Modeles.Instance m = modele(s);
        String marche = s.d.animation("marche");
        if (t < s.rondeArret) {
            mob.getPathfinder().stopPathfinding();
            if (a(m, marche) && marche.equals(m.animation())) m.arreterAnimation();
            return true;
        }
        double[] p = r.points().get(s.rondeIndex);
        Location but = point(mob.getWorld(), p, l.getY());
        if (Geometrie.distance(l.getX(), l.getZ(), but.getX(), but.getZ()) < 1.6) {
            s.mx = but.getX(); s.my = but.getY(); s.mz = but.getZ();
            if (r.bout(s.rondeIndex) && r.arret() > 0) s.rondeArret = t + r.arret();
            int[] n = r.suivant(s.rondeIndex, s.rondeSens);
            s.rondeIndex = n[0];
            s.rondeSens = n[1];
            return true;
        }
        if (!mob.getPathfinder().hasPath() || t >= s.prochainPlan) {
            mob.getPathfinder().moveTo(but, r.vitesse() * s.d.vitesse());
            s.prochainPlan = t + 40;
        }
        if (a(m, marche) && !marche.equals(m.animation())) m.jouerParDessus(marche, true);
        return true;
    }

    private static Location point(World w, double[] p, double yRef) {
        double x = p[0], z = p[p.length - 1], y = p.length >= 3 ? p[1] : yRef;
        Location sol = Mobs.sol(w, x, y, z, false);
        return sol != null ? sol : new Location(w, x, y, z);
    }

    // ================================================================== chaque 5 tics : durée de vie, crâne qui court

    void tic(Iterable<Mobs.Suivi> suivis) {
        long t = mobs.tics();
        List<Mobs.Suivi> retirer = new ArrayList<>();
        for (Mobs.Suivi s : suivis) {
            if (!s.d.combattant() || !s.e.isValid()) continue;
            Mecaniques meca = s.d.combat().meca();
            if (meca.dureeDeVie() > 0 && t - s.ne >= meca.dureeDeVie() && s.arrive < 0) { retirer.add(s); continue; }
            if (meca.fuite() != null && s.e instanceof Mob mob) {
                if (courir(s, mob, meca, t)) retirer.add(s);
            }
        }
        for (Mobs.Suivi s : retirer) {
            Mecaniques.Releve r = s.d.combat().meca().siArrive();
            Location ou = s.e.getLocation();
            boolean releve = s.arrive >= 0 && r != null;
            mobs.retirer(s.e);
            if (releve) relever(r, ou, s.niveau);
        }
    }

    /** Le crâne court vers le tas d'os le plus proche ; arrivé, il s'y pose. Vrai quand il est temps de relever son squelette. */
    private boolean courir(Mobs.Suivi s, Mob mob, Mecaniques meca, long t) {
        if (s.arrive >= 0) {
            mob.getPathfinder().stopPathfinding();
            Mecaniques.Releve r = meca.siArrive();
            return r == null || t - s.arrive >= r.delai();
        }
        Mecaniques.Fuite f = meca.fuite();
        if (!s.fuiteCherchee) {
            s.fuiteCherchee = true;
            s.fuiteVers = mobs.endroitProche(mob.getLocation(), f.vers(), f.rayon(), f.blocs());
        }
        Location l = mob.getLocation();
        if (s.fuiteVers == null) {
            // aucun tas d'os à sa portée : il fuit le joueur le plus proche
            Player p = null;
            double best = 16 * 16;
            for (Player j : mob.getWorld().getPlayers()) {
                if (j.getGameMode() == GameMode.SPECTATOR || j.isDead()) continue;
                double d = j.getLocation().distanceSquared(l);
                if (d < best) { best = d; p = j; }
            }
            if (p != null && t % 10 == 0) {
                Vector loin = l.toVector().subtract(p.getLocation().toVector()).setY(0);
                if (loin.lengthSquared() > 1e-4) mob.getPathfinder().moveTo(l.clone().add(loin.normalize().multiply(6)), s.d.combat().ia().vitesse());
            }
            return false;
        }
        if (Geometrie.distance(l.getX(), l.getZ(), s.fuiteVers.getX(), s.fuiteVers.getZ()) <= 1.6) {
            s.arrive = t;
            mob.getPathfinder().stopPathfinding();
            l.getWorld().playSound(l, Sound.BLOCK_BONE_BLOCK_PLACE, 1f, 0.6f);
            l.getWorld().spawnParticle(Particle.BLOCK, l.clone().add(0, 0.3, 0), 18, 0.3, 0.2, 0.3, 0,
                org.bukkit.Material.BONE_BLOCK.createBlockData());
            return false;
        }
        if (!mob.getPathfinder().hasPath() || t >= s.prochainPlan) {
            mob.getPathfinder().moveTo(s.fuiteVers, s.d.combat().ia().vitesse());
            s.prochainPlan = t + 20;
        }
        return false;
    }

    /** Le squelette se relève au tas d'os, avec une part de sa Vie, sans butin. */
    private void relever(Mecaniques.Releve r, Location ou, int niveau) {
        Entity e = mobs.invoquer(r.mob(), ou, null, null, niveau);
        if (e == null) return;
        Mobs.Suivi n = mobs.suivi(e);
        if (n == null) return;
        mobs.marquerSansButin(n);
        if (n.d.combattant()) mobs.combats().fixerVie(n, n.vieMax * r.vie());
        ou.getWorld().playSound(ou, Sound.ENTITY_SKELETON_AMBIENT, 1f, 0.6f);
    }

    // ================================================================== mort, apparition

    /**
     * {@code a-la-mort} : ses invocations naissent à l'os {@code depart} du modèle (le crâne du Squelette-mosaïque),
     * {@code delai} tics après sa mort.
     */
    void aLaMort(Mobs.Suivi s, Mecaniques.Invocation inv) {
        Location ou = s.e.getLocation();
        Modeles.Instance m = modele(s);
        if (inv.depart() != null && m != null) {
            try {
                Location os = m.positionOs(inv.depart());
                if (os != null) ou = Mobs.sol(os.getWorld(), os.getX(), os.getY(), os.getZ(), false) != null
                    ? Mobs.sol(os.getWorld(), os.getX(), os.getY(), os.getZ(), false) : os;
            } catch (Throwable ignore) { }
        }
        final Location lieu = ou;
        final int niveau = s.niveau;
        Runnable naitre = () -> {
            for (int k = 0; k < inv.nombre(); k++) {
                double dx = inv.nombre() > 1 ? (Math.random() - 0.5) : 0, dz = inv.nombre() > 1 ? (Math.random() - 0.5) : 0;
                try { mobs.invoquer(inv.mob(), lieu.clone().add(dx, 0.2, dz), null, null, niveau); }
                catch (Throwable ex) { plugin.getLogger().warning("a-la-mort " + s.d.id() + " : " + ex); }
            }
        };
        if (inv.delai() > 0) Bukkit.getScheduler().runTaskLater(plugin, naitre, inv.delai());
        else naitre.run();
    }

    /** Son animation d'apparition (rôle « apparition »), s'il en a une. */
    void apparition(Mobs.Suivi s) {
        Modeles.Instance m = modele(s);
        String an = s.d.animation("apparition");
        if (a(m, an)) m.jouer(an, false);
    }

    /**
     * Sortie d'une paroi : le mob est collé à la paroi la plus proche (4 blocs au plus), figé {@link Apparition#FISSURE}
     * tics le temps que la fissure s'ouvre (lueur et éclats de la pierre), puis il entre.
     */
    void paroi(Mobs.Suivi s) {
        Entity e = s.e;
        Location l = e.getLocation();
        World w = l.getWorld();
        Block mur = null;
        BlockFace face = null;
        double best = Double.MAX_VALUE;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
            BlockFace.NORTH_EAST, BlockFace.NORTH_WEST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST}) {
            for (int k = 1; k <= 4; k++) {
                Block b = w.getBlockAt(l.getBlockX() + f.getModX() * k, l.getBlockY() + 1, l.getBlockZ() + f.getModZ() * k);
                if (b.isPassable()) continue;
                double d = k * Math.hypot(f.getModX(), f.getModZ());
                if (d < best) { best = d; mur = b; face = f; }
                break;
            }
        }
        Location fissure;
        if (mur != null) {
            // collé à la paroi, du côté du couloir, et tourné vers lui
            Block devant = mur.getRelative(face.getOppositeFace());
            Location pied = Mobs.sol(w, devant.getX() + 0.5, l.getY(), devant.getZ() + 0.5, false);
            if (pied != null && Math.abs(pied.getY() - l.getY()) <= 2) {
                pied.setYaw((float) Math.toDegrees(Math.atan2(face.getModX(), -face.getModZ())));
                e.teleport(pied);
            }
            fissure = mur.getLocation().add(0.5, 0.5, 0.5).subtract(face.getModX() * 0.52, 0, face.getModZ() * 0.52);
        } else {
            fissure = l.clone().add(0, 1, 0);
        }
        if (s.d.combattant()) mobs.ia().entraver(s, Apparition.FISSURE * 50L, false, false);
        else if (e instanceof Mob mob) mob.getPathfinder().stopPathfinding();
        apparition(s);
        BlockData pierre = mur != null ? mur.getBlockData() : org.bukkit.Material.STONE.createBlockData();
        final Location f = fissure;
        w.playSound(f, Sound.BLOCK_DEEPSLATE_BREAK, 0.9f, 0.6f);
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= Apparition.FISSURE || !e.isValid()) { cancel(); return; }
                double h = 0.2 + 1.6 * Math.min(1, t / (double) Apparition.FISSURE);
                w.spawnParticle(Particle.DUST, f.clone().add(0, h - 1, 0), 4, 0.05, h * 0.5, 0.05, 0,
                    new Particle.DustOptions(Color.fromRGB(0x9FF2E6), 1.1f));
                if (t % 3 == 0) w.spawnParticle(Particle.BLOCK, f, 6, 0.15, 0.6, 0.15, 0, pierre);
                if (t % 10 == 0) w.playSound(f, Sound.BLOCK_STONE_HIT, 0.6f, 0.5f);
                t += 2;
            }
        }.runTaskTimer(plugin, 0, 2);
    }
}
