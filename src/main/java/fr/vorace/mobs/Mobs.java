package fr.vorace.mobs;

import fr.vorace.mobs.Cerveau.Action;
import fr.vorace.modeles.Modeles;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Mobs de Vorace (rôle de MythicMobs) : définitions (mobs/*.yml), zones (zones/*.yml) et points d'apparition
 * (apparitions/*.yml). Chaque mob porte un modèle animé et un cerveau : besoins, troupeau, laisse autour de sa
 * maison, horaires, peur et colère. Les mobs loin de tout joueur ne réfléchissent pas.
 */
public final class Mobs {

    private final VoraceMob plugin;
    private final NamespacedKey cleMob, cleMaison, cleTroupeau, cleApparition, cleBesoins;
    private Map<String, DefinitionMob> definitions = Map.of();
    private Map<String, Zone> zones = Map.of();
    private Map<String, Apparition> apparitions = Map.of();
    private final Map<UUID, Suivi> suivis = new LinkedHashMap<>();
    private final Map<String, EtatApparition> registre = new HashMap<>();
    private Map<String, List<Suivi>> troupeaux = new HashMap<>();
    private final Random hasard = new Random();
    private BukkitTask tache;
    private long tic;
    private boolean registreModifie;
    private double distanceReflexion = 64;
    private volatile String derniereErreur;
    private boolean erreurJournalisee;

    /** Mobs suivis d'un point d'apparition (gardés dans donnees/apparitions.yml). */
    static final class EtatApparition {
        final Set<UUID> membres = new HashSet<>();
        final Map<UUID, double[]> positions = new HashMap<>();
        long derniereMort;
        boolean rempli;
    }

    public Mobs(VoraceMob plugin) {
        this.plugin = plugin;
        cleMob = NamespacedKey.fromString("vorace:mob");
        cleMaison = NamespacedKey.fromString("vorace:mob_maison");
        cleTroupeau = NamespacedKey.fromString("vorace:mob_troupeau");
        cleApparition = NamespacedKey.fromString("vorace:mob_apparition");
        cleBesoins = NamespacedKey.fromString("vorace:mob_besoins");
    }

    public Path dossier() { return plugin.getDataFolder().toPath(); }
    public Collection<DefinitionMob> definitions() { return definitions.values(); }
    public DefinitionMob definition(String id) { return id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<Apparition> apparitions() { return apparitions.values(); }
    public Collection<Zone> zones() { return zones.values(); }
    public Collection<Suivi> suivis() { return suivis.values(); }
    public String derniereErreur() { return derniereErreur; }
    public int membres(String apparition) { EtatApparition e = registre.get(apparition); return e == null ? 0 : e.membres.size(); }

    // ------------------------------------------------------------------ chargement

    public void charger() {
        if (registreModifie) sauverRegistre();
        installerExemple();
        try {
            distanceReflexion = Math.max(8, plugin.getConfig().getDouble("mobs.distance-reflexion", 64));
        } catch (Throwable ignore) { }
        List<String> problemes = new ArrayList<>();
        Map<String, DefinitionMob> defs = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigurationSection> e : sections("mobs").entrySet()) {
            DefinitionMob d = DefinitionMob.lire(e.getKey(), e.getValue(), problemes);
            if (d != null) defs.put(d.id(), d);
        }
        Map<String, Zone> zs = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigurationSection> e : sections("zones").entrySet()) {
            Zone z = Zone.lire(e.getKey(), e.getValue(), problemes);
            if (z != null) zs.put(z.id(), z);
        }
        Map<String, Apparition> apps = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigurationSection> e : sections("apparitions").entrySet()) {
            Apparition a = Apparition.lire(e.getKey(), e.getValue(), problemes);
            if (a == null) continue;
            for (String m : a.mobs()) if (!defs.containsKey(m)) problemes.add("apparition " + a.id() + " : mob inconnu « " + m + " »");
            if (a.zone() != null && !zs.containsKey(a.zone())) problemes.add("apparition " + a.id() + " : zone inconnue « " + a.zone() + " »");
            apps.put(a.id(), a);
        }
        for (DefinitionMob d : defs.values()) {
            if (d.zone() != null && !zs.containsKey(d.zone())) problemes.add("mob " + d.id() + " : zone inconnue « " + d.zone() + " »");
            if (plugin.modeles() != null) for (String m : d.modeles()) if (plugin.modeles().modele(m) == null) problemes.add("mob " + d.id() + " : modèle animé inconnu « " + m + " »");
            if (!d.decor() && type(d.type()) == null) problemes.add("mob " + d.id() + " : entité inconnue « " + d.type() + " »");
        }
        definitions = defs;
        zones = zs;
        apparitions = apps;
        problemes.forEach(p -> plugin.getLogger().warning("Mobs : " + p));
        plugin.getLogger().info(defs.size() + " mob(s), " + zs.size() + " zone(s), " + apps.size() + " point(s) d'apparition");
        lireRegistre();
        if (tache != null && Bukkit.isPrimaryThread()) {
            // rechargement : les mobs en jeu reprennent leur nouvelle définition
            for (Suivi s : suivis.values()) sauverBesoins(s);
            suivis.clear();
            for (World w : Bukkit.getWorlds()) charger(w.getEntities());
        }
    }

    /** Toutes les sections de premier niveau des fichiers .yml d'un dossier (id en minuscules). */
    private Map<String, ConfigurationSection> sections(String nom) {
        Map<String, ConfigurationSection> res = new LinkedHashMap<>();
        Path d = dossier().resolve(nom);
        if (!Files.isDirectory(d)) return res;
        List<Path> fichiers;
        try (Stream<Path> f = Files.walk(d)) {
            fichiers = f.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            return res;
        }
        for (Path p : fichiers) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(p.toFile());
            for (String k : y.getKeys(false)) if (y.isConfigurationSection(k)) res.put(k.toLowerCase(Locale.ROOT), y.getConfigurationSection(k));
        }
        return res;
    }

    public void demarrer() {
        tache = Bukkit.getScheduler().runTaskTimer(plugin, this::tic, 20L, 1L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) charger(w.getEntities());
        });
    }

    public void arreter() {
        if (tache != null) tache.cancel();
        for (Suivi s : suivis.values()) sauverBesoins(s);
        sauverRegistre();
    }

    // ------------------------------------------------------------------ suivi des entités

    /** Un mob de Vorace dans le monde. */
    public final class Suivi {
        final Entity e;
        final DefinitionMob d;
        final Cerveau c;
        double mx, my, mz, laisse;
        String troupeau, apparition;
        Zone zone;
        Location cible, nourriture, eau;
        long rechercheNourriture, rechercheEau, prochainEnnui, dernierCoup, envolFin = -1, prochainPlan;
        String boucle;
        double distMenacePrec = Double.NaN;
        Player menace;
        /** Volants : pilote de vol, et vrai si un joueur est assez près pour qu'il bouge. */
        Vol vol;
        boolean actif;
        /** Distance parcourue (au sol ou en vol) depuis son apparition, et dernière position vue. */
        double parcouru, dernierPas;
        /** Pensées d'affilée sans un pas alors qu'il veut se déplacer. */
        int bloque, coince;
        double avantX = Double.NaN, avantZ;

        Suivi(Entity e, DefinitionMob d) {
            this.e = e;
            this.d = d;
            this.c = new Cerveau(e.getUniqueId().getLeastSignificantBits());
        }

        public Entity entite() { return e; }
        public DefinitionMob definition() { return d; }
        public Cerveau cerveau() { return c; }

        public String etat() {
            Location l = e.getLocation();
            String act = vol != null ? (vol.etat() == Vol.Etat.POSE ? (vol.marche() ? "marche" : vol.marcheur() ? "picore" : "pose")
                : vol.etat() == Vol.Etat.VOL ? "vole" : "atterrit")
                : d.decor() ? (envolFin >= 0 ? "partis" : "presents")
                : c.action() == Action.REPOS && dernierPas > 0.3 ? (e.isInWater() ? "nage" : "flane")
                : c.action().name().toLowerCase(Locale.ROOT);
            return String.format(Locale.ROOT, "id=%s mob=%s action=%s faim=%.2f soif=%.2f fatigue=%.2f peur=%.2f colere=%.2f maison=%.1f/%.0f%s%s",
                e.getUniqueId().toString().substring(0, 6), d.id(), act, c.faim(), c.soif(), c.fatigue(), c.peur(), c.colere(),
                Geometrie.distance(l.getX(), l.getZ(), mx, mz), laisse, troupeau == null ? "" : " troupeau=" + troupeau,
                apparition == null ? "" : " apparition=" + apparition) + String.format(Locale.ROOT, " parcouru=%.1f", parcouru)
                + (vol != null ? String.format(Locale.ROOT, " a-pied=%.1f", vol.parcouru()) : "")
                + String.format(Locale.ROOT, " pos=%.1f,%.1f,%.1f", l.getX(), l.getY(), l.getZ())
                + effetsJoues();
        }

        /** Décors vivants : sons et particules joués par leurs animations (preuve que l'animation tourne en jeu). */
        private String effetsJoues() {
            if (!d.decor() || plugin.modeles() == null) return "";
            Modeles.Instance i = plugin.modeles().instance(e);
            if (i == null) return " modele=absent";
            String an = i.animation();
            return String.format(Locale.ROOT, " animation=%s sons=%d particules=%d", an == null ? "-" : an, i.sonsJoues(), i.particulesJouees());
        }
    }

    public Suivi suivi(Entity e) {
        return e == null ? null : suivis.get(e.getUniqueId());
    }

    /** Mob de Vorace le plus proche, ou null. */
    public Suivi plusProche(Location l, double rayon) {
        Suivi meilleur = null;
        double d = rayon * rayon;
        for (Suivi s : suivis.values()) {
            if (!s.e.getWorld().equals(l.getWorld())) continue;
            double x = s.e.getLocation().distanceSquared(l);
            if (x <= d) { d = x; meilleur = s; }
        }
        return meilleur;
    }

    /** Les n mobs de Vorace les plus proches (dans le rayon), du plus proche au plus loin. */
    public List<Suivi> proches(Location l, double rayon, int n) {
        List<Suivi> res = new ArrayList<>();
        for (Suivi s : suivis.values()) {
            if (s.e.getWorld().equals(l.getWorld()) && s.e.getLocation().distanceSquared(l) <= rayon * rayon) res.add(s);
        }
        res.sort(java.util.Comparator.comparingDouble(s -> s.e.getLocation().distanceSquared(l)));
        return res.size() > n ? res.subList(0, n) : res;
    }

    /** Reprend les mobs de Vorace des entités chargées (démarrage, chargement d'un chunk). */
    public void charger(Collection<? extends Entity> entites) {
        for (Entity e : entites) {
            if (suivis.containsKey(e.getUniqueId())) continue;
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            String id = pdc.get(cleMob, PersistentDataType.STRING);
            if (id == null) continue;
            DefinitionMob d = definition(id);
            if (d == null) continue;
            if (e instanceof LivingEntity le && le.isDead()) continue;
            Suivi s = new Suivi(e, d);
            String[] m = pdc.getOrDefault(cleMaison, PersistentDataType.STRING, "").split(";");
            Location l = e.getLocation();
            s.mx = l.getX(); s.my = l.getY(); s.mz = l.getZ();
            try {
                if (m.length >= 3) { s.mx = Double.parseDouble(m[0]); s.my = Double.parseDouble(m[1]); s.mz = Double.parseDouble(m[2]); }
            } catch (NumberFormatException ignore) { }
            s.troupeau = pdc.get(cleTroupeau, PersistentDataType.STRING);
            s.apparition = pdc.get(cleApparition, PersistentDataType.STRING);
            Apparition a = s.apparition == null ? null : apparitions.get(s.apparition);
            s.laisse = a != null && a.laisse() > 0 ? a.laisse() : d.laisse();
            String z = a != null && a.zone() != null ? a.zone() : d.zone();
            s.zone = z == null ? null : zones.get(z);
            s.c.relire(pdc.get(cleBesoins, PersistentDataType.STRING));
            if (d.cerveau() && e instanceof Mob mob) libererIA(mob);
            if (d.volant()) {
                e.setGravity(false);
                if (e instanceof Mob mob) mob.setAware(false);
                double vit = (d.vol().equals("papillon") ? 1.6 : 6.5) * d.vitesse();
                s.vol = new Vol(e.getUniqueId().getMostSignificantBits(), d.vol().equals("papillon"), vit, d.altMin(), d.altMax(),
                    l.getX(), l.getY(), l.getZ());
                if (d.marche() > 0) s.vol.marcheur(d.marche());
            }
            suivis.put(e.getUniqueId(), s);
            if (s.apparition != null) {
                EtatApparition et = registre.computeIfAbsent(s.apparition, k -> new EtatApparition());
                if (et.membres.add(e.getUniqueId())) registreModifie = true;
            }
        }
    }

    /** Le cerveau de Vorace remplace l'IA de déplacement, de regard et de cible du jeu (flotter dans l'eau reste). */
    private void libererIA(Mob mob) {
        try {
            var goals = Bukkit.getMobGoals();
            goals.removeAllGoals(mob, com.destroystokyo.paper.entity.ai.GoalType.MOVE);
            goals.removeAllGoals(mob, com.destroystokyo.paper.entity.ai.GoalType.LOOK);
            goals.removeAllGoals(mob, com.destroystokyo.paper.entity.ai.GoalType.TARGET);
        } catch (Throwable ignore) {
            // mob sans « goals » (IA à cerveau du jeu) : il garde son IA, Vorace le guide quand même
        }
    }

    /** Entité déchargée : on garde ses besoins et sa dernière position. */
    public void decharger(Entity e) {
        Suivi s = suivis.remove(e.getUniqueId());
        if (s == null) return;
        sauverBesoins(s);
        if (s.apparition != null) {
            EtatApparition et = registre.get(s.apparition);
            if (et != null) {
                Location l = e.getLocation();
                et.positions.put(e.getUniqueId(), new double[]{l.getX(), l.getY(), l.getZ()});
                registreModifie = true;
            }
        }
    }

    /** Mort (ou retrait) : le point d'apparition le remplacera après son délai. */
    public void mort(Entity e) {
        Suivi s = suivis.remove(e.getUniqueId());
        String app = s != null ? s.apparition : e.getPersistentDataContainer().get(cleApparition, PersistentDataType.STRING);
        if (app == null) return;
        EtatApparition et = registre.get(app);
        if (et == null) return;
        et.membres.remove(e.getUniqueId());
        et.positions.remove(e.getUniqueId());
        et.derniereMort = System.currentTimeMillis();
        registreModifie = true;
    }

    /** Frappé par un joueur (ou un de ses projectiles) : peur ou colère, et le troupeau s'affole. */
    public void blesse(Entity e, Player par) {
        Suivi s = suivis.get(e.getUniqueId());
        if (s == null) return;
        s.c.blesser(s.d.caractere());
        if (par != null) s.menace = par;
        if (s.vol != null) {
            Location m = par != null ? par.getLocation() : e.getLocation();
            s.vol.effrayer(m.getX(), m.getZ(), sol(e.getWorld(), e.getLocation().getY()), s.mx, s.mz, s.laisse);
        }
        if (s.troupeau != null && s.c.peur() > 0.5) {
            for (Suivi o : troupeaux.getOrDefault(s.troupeau, List.of())) {
                if (o != s && o.e.getWorld().equals(e.getWorld()) && o.e.getLocation().distanceSquared(e.getLocation()) < 256) o.c.effrayer(0.8);
            }
        }
    }

    private void sauverBesoins(Suivi s) {
        try { s.e.getPersistentDataContainer().set(cleBesoins, PersistentDataType.STRING, s.c.sauver()); } catch (Throwable ignore) { }
    }

    // ------------------------------------------------------------------ apparition

    /**
     * Fait apparaître un mob ; null si le mob est inconnu.
     * @param apparition point d'apparition (ou null), troupeau : identifiant de troupeau (ou null)
     */
    public Entity invoquer(String id, Location ou, String apparition, String troupeau) {
        DefinitionMob d = definition(id);
        if (d == null || ou.getWorld() == null) return null;
        Modeles modeles = plugin.modeles();
        float taille = (float) (d.tailleMin() + hasard.nextDouble() * (d.tailleMax() - d.tailleMin()));
        String modele = d.modeles().isEmpty() ? null : d.modeles().get(hasard.nextInt(d.modeles().size()));
        Apparition a = apparition == null ? null : apparitions.get(apparition);
        double mx = a != null ? a.x() : ou.getX(), my = a != null ? a.y() : ou.getY(), mz = a != null ? a.z() : ou.getZ();
        Entity e;
        if (d.decor()) {
            if (modeles == null || modele == null) return null;
            Modeles.Instance i = modeles.poser(modele, ou, hasard.nextFloat() * 360f - 180f, null);
            if (i == null) return null;
            if (Math.abs(taille - 1) > 1e-3) i.taille(taille);
            e = i.base();
        } else {
            EntityType type = type(d.type());
            if (type == null) return null;
            Location l = ou.clone();
            l.setYaw(hasard.nextFloat() * 360f - 180f);
            e = ou.getWorld().spawnEntity(l, type);
            e.setPersistent(true);
            if (e instanceof LivingEntity le) {
                le.setRemoveWhenFarAway(false);
                if (d.vie() > 0) {
                    AttributeInstance vie = attribut(le, "max_health");
                    if (vie != null) {
                        vie.setBaseValue(d.vie());
                        le.setHealth(Math.min(d.vie(), vie.getValue()));
                    }
                }
            }
            if (e instanceof Ageable ag) ag.setAdult();
            if (d.silencieux()) e.setSilent(true);
            if (modeles != null && modele != null && e instanceof LivingEntity le) {
                Modeles.Instance i = modeles.attacher(le, modele);
                if (i != null && Math.abs(taille - 1) > 1e-3) i.taille(taille);
            }
        }
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(cleMob, PersistentDataType.STRING, d.id());
        pdc.set(cleMaison, PersistentDataType.STRING, String.format(Locale.ROOT, "%.2f;%.2f;%.2f", mx, my, mz));
        if (troupeau != null) pdc.set(cleTroupeau, PersistentDataType.STRING, troupeau);
        if (apparition != null) pdc.set(cleApparition, PersistentDataType.STRING, apparition);
        charger(List.of(e));
        return e;
    }

    static EntityType type(String nom) {
        try {
            return Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(nom.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Attribut par son nom (« max_health » : avant 1.21.3, « generic.max_health »). */
    static AttributeInstance attribut(LivingEntity e, String nom) {
        for (String k : List.of(nom, "generic." + nom)) {
            try {
                Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(k));
                if (a != null) return e.getAttribute(a);
            } catch (Throwable ignore) { }
        }
        return null;
    }

    private void verifierApparitions() {
        long maintenant = System.currentTimeMillis();
        for (Apparition a : apparitions.values()) {
            World w = Bukkit.getWorld(a.monde());
            if (w == null || !w.isChunkLoaded((int) Math.floor(a.x()) >> 4, (int) Math.floor(a.z()) >> 4)) continue;
            boolean joueur = false;
            for (Player p : w.getPlayers()) {
                if (Geometrie.distance(p.getLocation().getX(), p.getLocation().getZ(), a.x(), a.z()) < 96) { joueur = true; break; }
            }
            if (!joueur) continue;
            EtatApparition et = registre.computeIfAbsent(a.id(), k -> new EtatApparition());
            // membres disparus sans mourir (commande /kill, chunk effacé) : leur chunk est chargé mais ils n'y sont plus
            for (UUID u : new ArrayList<>(et.membres)) {
                Entity e = Bukkit.getEntity(u);
                if (e != null && e.isValid()) {
                    Location l = e.getLocation();
                    et.positions.put(u, new double[]{l.getX(), l.getY(), l.getZ()});
                    continue;
                }
                double[] p = et.positions.get(u);
                if (p != null && w.isChunkLoaded((int) Math.floor(p[0]) >> 4, (int) Math.floor(p[2]) >> 4)) {
                    et.membres.remove(u);
                    et.positions.remove(u);
                    et.derniereMort = maintenant;
                    registreModifie = true;
                }
            }
            int manque = a.nombre() - et.membres.size();
            if (manque <= 0) { et.rempli = true; continue; }
            if (et.rempli && maintenant - et.derniereMort < a.reapparition() * 1000) continue;
            int n = et.rempli ? 1 : manque;
            for (int k = 0; k < n; k++) {
                Location l = solAutour(w, a.x(), a.y(), a.z(), a.rayon());
                String mob = a.mobs().get(hasard.nextInt(a.mobs().size()));
                Entity e = invoquer(mob, l, a.id(), a.troupeau() ? a.id() : null);
                if (e == null) continue;
                et.membres.add(e.getUniqueId());
                et.positions.put(e.getUniqueId(), new double[]{l.getX(), l.getY(), l.getZ()});
            }
            if (et.rempli) et.derniereMort = maintenant;
            et.rempli = true;
            registreModifie = true;
        }
    }

    /** Un endroit au sol au hasard dans le rayon (le centre si rien ne convient). */
    private Location solAutour(World w, double x, double y, double z, double rayon) {
        for (int essai = 0; essai < 10; essai++) {
            double a = hasard.nextDouble() * Math.PI * 2, r = Math.sqrt(hasard.nextDouble()) * rayon;
            Location l = sol(w, x + Math.cos(a) * r, y, z + Math.sin(a) * r, false);
            if (l != null) return l;
        }
        return new Location(w, x, y, z);
    }

    /**
     * Sol praticable près de la hauteur y (bloc solide, deux blocs libres au-dessus), ou null.
     * @param eau accepter la surface de l'eau (animaux aquatiques)
     */
    static Location sol(World w, double x, double y, double z, boolean eau) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        for (int dy = 4; dy >= -8; dy--) {
            Block b = w.getBlockAt(bx, by + dy, bz);
            Block h1 = b.getRelative(0, 1, 0), h2 = b.getRelative(0, 2, 0);
            if (!h1.isPassable() || !h2.isPassable() || h1.isLiquid()) continue;
            if (b.isLiquid()) {
                if (eau) return new Location(w, bx + 0.5, by + dy, bz + 0.5);
                return null;
            }
            if (b.getType().isSolid() && b.getType() != Material.LAVA) return new Location(w, bx + 0.5, by + dy + 1, bz + 0.5);
        }
        return null;
    }

    // ------------------------------------------------------------------ réflexion

    private void tic() {
        tic++;
        if (tic % 20 == 0) {
            Map<String, List<Suivi>> t = new HashMap<>();
            for (Suivi s : suivis.values()) if (s.troupeau != null) t.computeIfAbsent(s.troupeau, k -> new ArrayList<>()).add(s);
            troupeaux = t;
        }
        if (tic % 100 == 0) {
            try { verifierApparitions(); } catch (Throwable ex) { erreur("apparitions", ex); }
        }
        if (tic % 600 == 0 && registreModifie) sauverRegistre();
        if (tic % 1200 == 0) for (Suivi s : suivis.values()) sauverBesoins(s);
        for (Suivi s : suivis.values()) {
            if (s.vol == null || !s.actif || !s.e.isValid()) continue;
            try { voler(s); } catch (Throwable ex) { erreur(s.d.id(), ex); }
        }
        for (Suivi s : new ArrayList<>(suivis.values())) {
            if ((tic + (s.e.getUniqueId().hashCode() & 0xFFFF)) % 10 != 0) continue;
            try {
                if (!s.e.isValid()) { suivis.remove(s.e.getUniqueId()); continue; }
                penser(s, 0.5);
            } catch (Throwable ex) {
                erreur(s.d.id(), ex);
            }
        }
    }

    private void erreur(String quoi, Throwable ex) {
        derniereErreur = quoi + " : " + ex;
        if (!erreurJournalisee) {
            erreurJournalisee = true;
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Mobs : erreur (" + quoi + ")", ex);
        }
    }

    /**
     * Joueur le plus proche (ni spectateur, ni invisible, ni mort), et sa distance au carré dans d[0]. Un joueur en
     * créatif compte : les animaux vivent et le fuient autour de lui, mais ne l'attaquent pas (voir penser()).
     */
    private static Player joueurProche(Entity e, double rayon, double[] d) {
        Player meilleur = null;
        double best = rayon * rayon;
        Location l = e.getLocation();
        for (Player p : e.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.isInvisible() || p.isDead()) continue;
            double x = p.getLocation().distanceSquared(l);
            if (x < best) { best = x; meilleur = p; }
        }
        d[0] = best;
        return meilleur;
    }

    private void penser(Suivi s, double dt) {
        Entity e = s.e;
        Location l = e.getLocation();
        if (!Double.isNaN(s.avantX)) {
            double pas = Math.hypot(l.getX() - s.avantX, l.getZ() - s.avantZ);
            if (pas < 8) s.parcouru += pas;            // au-delà : téléportation, pas un déplacement
            s.dernierPas = pas < 8 ? pas : 0;
        }
        s.avantX = l.getX(); s.avantZ = l.getZ();
        double[] dd = new double[1];
        Player proche = joueurProche(e, distanceReflexion, dd);
        Modeles.Instance modele = plugin.modeles() == null ? null : plugin.modeles().instance(e);

        if (s.vol != null) {
            s.actif = proche != null;
            if (proche == null) return;
            double env = s.d.envol() > 0 ? s.d.envol() : s.d.detection();
            Location pl = proche.getLocation();
            double dj = Math.sqrt(dd[0]);
            // un joueur qui reste près d'eux : ils ne marchent ni ne se posent à moins de deux fois la distance d'envol
            s.vol.eviter(pl.getX(), pl.getZ(), dj < env * 4 ? env * 2 : 0);
            if (dj < env * (proche.isSneaking() ? 0.5 : 1) && s.vol.etat() != Vol.Etat.VOL) {
                s.vol.effrayer(pl.getX(), pl.getZ(), sol(e.getWorld(), l.getY()), s.mx, s.mz, s.laisse);
                // la volée part ensemble : les oiseaux de la même espèce posés à moins de 12 blocs, et toute la bande du même
                // point d'apparition (une place à pigeons), s'envolent aussi
                for (Suivi o : suivis.values()) {
                    if (o != s && o.vol != null && o.d == s.d && o.vol.etat() != Vol.Etat.VOL && o.e.getWorld().equals(e.getWorld())
                        && (o.e.getLocation().distanceSquared(l) < 144
                            || (s.apparition != null && s.apparition.equals(o.apparition) && o.e.getLocation().distanceSquared(l) < 900))) {
                        o.vol.effrayer(pl.getX(), pl.getZ(), sol(e.getWorld(), o.e.getLocation().getY()), o.mx, o.mz, o.laisse);
                        o.actif = true;
                    }
                }
            }
            if (modele != null) {
                String an = s.d.animation("vol");
                if (s.vol.enVol() && an != null && !an.equals(s.boucle)) { modele.jouer(an, true); s.boucle = an; }
                else if (!s.vol.enVol() && s.boucle != null) {
                    // posé : animation d'atterrissage puis repos / marche automatiques
                    String pose = s.d.animation("pose");
                    if (pose != null) modele.jouer(pose, false); else modele.arreterAnimation();
                    s.boucle = null;
                    s.prochainEnnui = tic + 40 + hasard.nextInt(60);
                }
                else if (!s.vol.enVol() && !s.vol.marche() && !s.d.ennui().isEmpty() && tic >= s.prochainEnnui) {
                    s.prochainEnnui = tic + 40 + hasard.nextInt(100);
                    modele.jouer(s.d.ennui().get(hasard.nextInt(s.d.ennui().size())), false);
                }
            }
            return;
        }
        if (s.d.decor()) {
            penserDecor(s, modele, proche, dd[0]);
            return;
        }
        if (s.d.ia().equals("aucune")) return;
        if (proche == null) return; // personne autour : il ne réfléchit pas
        if (!(e instanceof Mob mob)) return;
        // monté par un joueur ou tenu en laisse : c'est le joueur qui décide
        if (!e.getPassengers().isEmpty() || (e instanceof LivingEntity le && le.isLeashed())) return;
        double distMaison = Geometrie.distance(l.getX(), l.getZ(), s.mx, s.mz);

        if (s.d.ia().equals("jeu")) {
            // IA du jeu, simplement tenue en laisse
            if (distMaison > s.laisse * 2 && dd[0] > 24 * 24) e.teleport(new Location(e.getWorld(), s.mx, s.my, s.mz, l.getYaw(), 0));
            else if (distMaison > s.laisse) mob.getPathfinder().moveTo(new Location(e.getWorld(), s.mx, s.my, s.mz), 1.1);
            return;
        }

        // perception
        boolean horsZone = s.zone != null && s.zone.monde().equals(e.getWorld().getName()) && !s.zone.contient(l.getX(), l.getZ());
        double distTroupeau = Double.NaN;
        double[] centre = null;
        List<Suivi> groupe = s.troupeau == null ? List.of() : troupeaux.getOrDefault(s.troupeau, List.of());
        if (groupe.size() >= 2) {
            double[] xs = new double[groupe.size()], zs = new double[groupe.size()];
            int n = 0;
            for (Suivi o : groupe) {
                if (!o.e.isValid() || !o.e.getWorld().equals(e.getWorld())) continue;
                xs[n] = o.e.getLocation().getX();
                zs[n] = o.e.getLocation().getZ();
                n++;
            }
            centre = n >= 2 ? Geometrie.centre(xs, zs, n) : null;
            if (centre != null) distTroupeau = Geometrie.distance(l.getX(), l.getZ(), centre[0], centre[1]);
        }
        Player menace = s.menace != null && s.menace.isValid() && s.menace.getWorld().equals(e.getWorld())
            && s.menace.getLocation().distanceSquared(l) < 32 * 32 ? s.menace : proche;
        double distMenace = Math.sqrt(menace.getLocation().distanceSquared(l));
        if (distMenace > Math.max(16, s.d.detection() * 2)) { menace = null; distMenace = Double.NaN; }
        // un joueur en créatif n'est jamais pris pour cible (comme dans le jeu) ; les craintifs le fuient quand même
        if (menace != null && menace.getGameMode() == GameMode.CREATIVE
            && (s.d.caractere() == Cerveau.Caractere.TERRITORIAL || s.d.caractere() == Cerveau.Caractere.AGRESSIF)) {
            menace = null; distMenace = Double.NaN;
        }
        boolean rapide = menace != null && (menace.isSprinting()
            || (!Double.isNaN(s.distMenacePrec) && (s.distMenacePrec - distMenace) / dt > 3.5));
        boolean discrete = menace != null && menace.isSneaking();
        s.distMenacePrec = distMenace;
        if (menace == null) s.menace = null;
        long temps = e.getWorld().getTime();
        boolean nuit = temps >= 13000 && temps <= 23000;

        boolean faimProche = s.c.faim() > 0.3 || s.c.action() == Action.MANGER;
        if (faimProche && !s.d.nourriture().isEmpty() && (s.nourriture == null || tic >= s.rechercheNourriture)) {
            s.nourriture = chercherNourriture(s, centre);
            s.rechercheNourriture = tic + (s.nourriture == null ? 200 : 600);
        }
        boolean soifProche = s.d.boit() && (s.c.soif() > 0.3 || s.c.action() == Action.BOIRE);
        if (soifProche && (s.eau == null || tic >= s.rechercheEau)) {
            s.eau = chercherEau(s);
            s.rechercheEau = tic + (s.eau == null ? 200 : 600);
        }
        boolean surPlace = switch (s.c.action()) {
            case MANGER -> s.nourriture != null && s.nourriture.getWorld().equals(e.getWorld()) && s.nourriture.distanceSquared(l) < 2.6;
            case BOIRE -> s.eau != null && s.eau.getWorld().equals(e.getWorld()) && s.eau.distanceSquared(l) < 2.6;
            default -> true;
        };
        Cerveau.Perception p = new Cerveau.Perception(distMaison, s.laisse, horsZone, distTroupeau, s.d.cohesion(), distMenace, rapide, discrete,
            s.d.detection(), nuit, s.nourriture != null, s.eau != null, surPlace);
        Action avant = s.c.action();
        Action action = s.c.penser(p, dt, s.d.caractere(), s.d.activite());
        if (s.c.nouvelle()) {
            arreterBoucle(s, modele);
            Bukkit.getPluginManager().callEvent(new VoraceMobActionEvent(e, s.d.id(), avant.name().toLowerCase(Locale.ROOT),
                action.name().toLowerCase(Locale.ROOT)));
            s.cible = null;
            s.prochainPlan = 0;
            if (action == Action.FUIR || action == Action.ATTAQUER) {
                String alerte = s.d.animation("alerte");
                if (modele != null && alerte != null) modele.jouerParDessus(alerte, false);
            }
            if (action == Action.FUIR && s.troupeau != null) {
                for (Suivi o : groupe) if (o != s && o.e.getLocation().distanceSquared(l) < 256) o.c.effrayer(s.c.peur() * 0.8);
            }
        }
        // coincé (aucun pas depuis 3 s alors qu'il veut aller quelque part : clôture, pente, recoin) : il part au hasard
        // quelques blocs plus loin, puis reprend son action
        boolean enRoute = switch (action) {
            case ERRER, REJOINDRE, RENTRER, FUIR -> true;
            case MANGER -> s.nourriture != null && s.nourriture.distanceSquared(l) >= 2.6;
            case BOIRE -> s.eau != null && s.eau.distanceSquared(l) >= 2.6;
            default -> false;
        };
        s.bloque = enRoute && s.dernierPas < 0.05 ? s.bloque + 1 : 0;
        if (s.bloque >= 6) {
            s.bloque = 0;
            World w = l.getWorld();
            boolean parti = false;
            Location repli = null;
            for (int k = 0; k < 12 && !parti; k++) {
                double a = hasard.nextDouble() * Math.PI * 2, r = 3 + hasard.nextDouble() * 5;
                Location c = sol(w, l.getX() + Math.cos(a) * r, l.getY(), l.getZ() + Math.sin(a) * r, false);
                if (c == null || Math.abs(c.getY() - l.getY()) > 3) continue;
                if (repli == null) repli = c;
                if (mob.getPathfinder().moveTo(c, s.d.vitesse())) {
                    s.prochainPlan = tic + 50;
                    s.cible = c;
                    parti = true;
                }
            }
            // aucun chemin nulle part (né dans un trou, entre des troncs, derrière une clôture) : au bout de deux essais, on le
            // pose sur un sol libre tout près plutôt que de le laisser figé
            s.coince = parti ? 0 : s.coince + 1;
            if (!parti && s.coince >= 2 && repli != null) {
                mob.teleport(repli.add(0.5, 0, 0.5));
                s.coince = 0;
                s.avantX = Double.NaN;
            }
            return;
        }
        agir(s, mob, modele, action, menace, centre, l);
    }

    /**
     * Décor vivant (modèle posé) : animations d'ennui de temps en temps ; s'il a une animation « envol », il la joue
     * quand un joueur approche. Avec une animation « retour », le décor reste (les oiseaux sont partis) et la joue
     * quand plus personne n'est près depuis un moment ; sans elle, il disparaît et son point le remplacera.
     */
    private void penserDecor(Suivi s, Modeles.Instance modele, Player proche, double d2) {
        double env = s.d.envol();
        String retour = s.d.animation("retour");
        // accroupi : deux fois moins loin ; en courant : deux fois plus loin
        boolean pres = proche != null && env > 0 && d2 < env * env * (proche.isSneaking() ? 0.25 : proche.isSprinting() ? 4 : 1);
        if (s.envolFin < 0) {
            if (pres) {
                String an = s.d.animation("envol");
                if (modele != null && an != null) modele.jouer(an, false);
                s.envolFin = tic + (retour != null ? 300 : 30);
                return;
            }
            if (modele != null && !s.d.ennui().isEmpty() && proche != null && tic >= s.prochainEnnui) {
                if (s.prochainEnnui > 0) modele.jouer(s.d.ennui().get(hasard.nextInt(s.d.ennui().size())), false);
                s.prochainEnnui = tic + 160 + hasard.nextInt(240);
            }
            return;
        }
        if (retour == null) {
            if (tic >= s.envolFin) {
                mort(s.e);
                if (modele != null && plugin.modeles() != null) plugin.modeles().retirer(modele);
                else s.e.remove();
            }
            return;
        }
        // partis : ils reviennent 15 s après le départ du dernier joueur proche
        if (proche != null && d2 < env * env * 4) s.envolFin = Math.max(s.envolFin, tic + 300);
        if (tic >= s.envolFin) {
            if (modele != null) modele.jouer(retour, false);
            s.envolFin = -1;
            s.prochainEnnui = tic + 200 + hasard.nextInt(200);
        }
    }

    /** Hauteur du sol pour les volants : dessus du bloc le plus haut (toit, branche), pas sur l'eau. */
    static Vol.Sol sol(World w, double yRef) {
        // les cartes fermées par un plafond de barrières (Orario : y 703) : le sol se cherche en descendant depuis un peu
        // au-dessus de l'oiseau, en sautant les barrières et ce qui ne porte pas (herbes, fleurs)
        int haut = Math.min(w.getMaxHeight() - 1, (int) Math.floor(yRef) + 40);
        int bas = Math.max(w.getMinHeight(), (int) Math.floor(yRef) - 64);
        return (x, z) -> {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return Double.NaN;
            int y = w.getHighestBlockYAt(bx, bz);
            if (y <= haut) {
                Block b = w.getBlockAt(bx, y, bz);
                if (b.getType() != Material.BARRIER) return b.isLiquid() ? Double.NaN : y + 1;
            }
            for (int yy = Math.min(y, haut); yy >= bas; yy--) {
                Block b = w.getBlockAt(bx, yy, bz);
                if (b.isLiquid()) return Double.NaN;
                Material m = b.getType();
                if (m.isAir() || m == Material.BARRIER || !m.isSolid()) continue;
                return yy + 1;
            }
            return Double.NaN;
        };
    }

    /** Un pas de vol (chaque tic) : l'entité de base suit le pilote, le modèle suit l'entité. */
    private void voler(Suivi s) {
        Entity e = s.e;
        Vol v = s.vol;
        double x0 = v.x(), y0 = v.y(), z0 = v.z();
        v.pas(0.05, sol(e.getWorld(), v.y()), s.mx, s.mz, s.laisse);
        if (Math.abs(v.x() - x0) + Math.abs(v.y() - y0) + Math.abs(v.z() - z0) < 1e-4) return;
        Location l = new Location(e.getWorld(), v.x(), v.y(), v.z(), v.yaw(), 0);
        e.teleport(l);
        e.setVelocity(new org.bukkit.util.Vector());
    }

    private void agir(Suivi s, Mob mob, Modeles.Instance modele, Action action, Player menace, double[] centre, Location l) {
        World w = l.getWorld();
        double v = s.d.vitesse();
        switch (action) {
            case REPOS -> {
                mob.getPathfinder().stopPathfinding();
                if (tic >= s.prochainEnnui) {
                    s.prochainEnnui = tic + 120 + hasard.nextInt(200);
                    if (!s.d.ennui().isEmpty() && modele != null && hasard.nextDouble() < 0.6) {
                        modele.jouer(s.d.ennui().get(hasard.nextInt(s.d.ennui().size())), false);
                    } else {
                        double a = hasard.nextDouble() * Math.PI * 2;
                        mob.lookAt(l.getX() + Math.cos(a) * 4, l.getY() + 1, l.getZ() + Math.sin(a) * 4);
                    }
                }
                if (menace != null && s.d.caractere() == Cerveau.Caractere.PAISIBLE && menace.getLocation().distanceSquared(l) < 36) mob.lookAt(menace);
            }
            case ERRER -> {
                // il flâne : un but, une courte pause en arrivant (ou s'il est bloqué), puis un autre but
                boolean libre = s.cible == null || s.cible.distanceSquared(l) < 2.25 || !mob.getPathfinder().hasPath();
                if (libre && tic >= s.prochainPlan) {
                    double[] p = Geometrie.errance(l.getX(), l.getZ(), s.mx, s.mz, s.laisse, hasard,
                        (x, z) -> (s.zone == null || s.zone.contient(x, z)) && sol(w, x, l.getY(), z, false) != null);
                    s.cible = sol(w, p[0], l.getY(), p[1], false);
                    if (s.cible != null) mob.getPathfinder().moveTo(s.cible, v);
                    s.prochainPlan = tic + 30 + hasard.nextInt(60);
                }
            }
            case MANGER -> allerPuisBoucle(s, mob, modele, s.nourriture, s.d.animation("manger"), v);
            case BOIRE -> allerPuisBoucle(s, mob, modele, s.eau, s.d.animation("boire") != null ? s.d.animation("boire") : s.d.animation("manger"), v);
            case DORMIR -> {
                Zone.Point abri = s.zone == null ? null : s.zone.plusProche("abri", l.getX(), l.getZ());
                Location ou = abri == null ? null : new Location(w, abri.x(), abri.y(), abri.z());
                if (ou != null && Geometrie.distance(ou.getX(), ou.getZ(), s.mx, s.mz) > s.laisse) ou = null;
                if (ou != null && ou.distanceSquared(l) > 9) {
                    if (tic >= s.prochainPlan) { mob.getPathfinder().moveTo(ou, v); s.prochainPlan = tic + 60; }
                } else {
                    mob.getPathfinder().stopPathfinding();
                    String an = s.d.animation("dormir");
                    if (modele != null && an != null && !an.equals(s.boucle)) { modele.jouer(an, true); s.boucle = an; }
                }
            }
            case REJOINDRE -> {
                if (centre != null && tic >= s.prochainPlan) {
                    Location c = sol(w, centre[0], l.getY(), centre[1], false);
                    if (c != null) mob.getPathfinder().moveTo(c, v * 1.1);
                    s.prochainPlan = tic + 40;
                }
            }
            case RENTRER -> {
                if (s.cible == null || tic >= s.prochainPlan) {
                    double[] p = Geometrie.versMaison(s.mx, s.mz, s.laisse, hasard);
                    Location c = sol(w, p[0], s.my, p[1], false);
                    s.cible = c != null ? c : new Location(w, s.mx, s.my, s.mz);
                    mob.getPathfinder().moveTo(s.cible, v * 1.1);
                    s.prochainPlan = tic + 100;
                }
            }
            case FUIR -> {
                if (tic >= s.prochainPlan) {
                    Location m = menace != null ? menace.getLocation() : l.clone().add(hasard.nextDouble() - 0.5, 0, hasard.nextDouble() - 0.5);
                    double[] p = Geometrie.fuite(l.getX(), l.getZ(), m.getX(), m.getZ(), s.mx, s.mz, s.laisse, 10);
                    Location c = sol(w, p[0], l.getY(), p[1], false);
                    boolean ok = c != null && mob.getPathfinder().moveTo(c, v * 1.6);
                    // pas de chemin à l'opposé (pente, falaise, mur) : il file ailleurs dans sa laisse plutôt que de rester figé
                    for (int k = 0; !ok && k < 6; k++) {
                        double[] q = Geometrie.errance(l.getX(), l.getZ(), s.mx, s.mz, s.laisse, hasard,
                            (x, z) -> sol(w, x, l.getY(), z, false) != null);
                        Location c2 = sol(w, q[0], l.getY(), q[1], false);
                        ok = c2 != null && mob.getPathfinder().moveTo(c2, v * 1.6);
                    }
                    if (!ok) s.c.peur *= 0.5;                 // vraiment coincé : il se calme au lieu de trembler sur place
                    s.prochainPlan = tic + 30;
                }
            }
            case ATTAQUER -> {
                if (menace == null) return;
                double d2 = menace.getLocation().distanceSquared(l);
                mob.lookAt(menace);
                if (d2 < 2.4 * 2.4) {
                    mob.getPathfinder().stopPathfinding();
                    if (tic - s.dernierCoup >= 24) {
                        s.dernierCoup = tic;
                        menace.damage(s.d.degats(), mob);
                    }
                } else if (tic >= s.prochainPlan) {
                    mob.getPathfinder().moveTo(menace, v * 1.3);
                    s.prochainPlan = tic + 10;
                }
            }
        }
    }

    /** Va jusqu'à l'endroit, puis joue l'animation en boucle (manger, boire). */
    private void allerPuisBoucle(Suivi s, Mob mob, Modeles.Instance modele, Location ou, String anim, double v) {
        if (ou == null) return;
        Location l = mob.getLocation();
        if (ou.getWorld().equals(l.getWorld()) && ou.distanceSquared(l) < 2.6) {
            mob.getPathfinder().stopPathfinding();
            mob.lookAt(ou.getX(), ou.getY() - 0.5, ou.getZ());
            if (modele != null && anim != null && !anim.equals(s.boucle)) { modele.jouer(anim, true); s.boucle = anim; }
        } else if (tic >= s.prochainPlan) {
            if (!mob.getPathfinder().moveTo(ou, v)) {
                // inaccessible : on oublie cet endroit, il en cherchera un autre
                if (ou == s.nourriture) s.nourriture = null;
                if (ou == s.eau) s.eau = null;
            }
            s.prochainPlan = tic + 40;
        }
    }

    private void arreterBoucle(Suivi s, Modeles.Instance modele) {
        if (s.boucle != null && modele != null) modele.arreterAnimation();
        s.boucle = null;
    }

    /**
     * Bloc ou plante comestible à portée (dans la laisse), ou point « nourriture » de la zone. Un mob en troupeau broute
     * près du troupeau : sinon il partait manger loin, revenait le rejoindre, repartait... (va-et-vient en courant).
     */
    private Location chercherNourriture(Suivi s, double[] centre) {
        Location l = s.e.getLocation();
        double cx = l.getX(), cz = l.getZ(), rayon = 6, maxCentre = Double.MAX_VALUE;
        if (centre != null && s.d.cohesion() > 0) {
            cx = centre[0]; cz = centre[1];
            rayon = Math.max(2, s.d.cohesion() * 0.5);
            maxCentre = s.d.cohesion() * 0.8;
        }
        World w = l.getWorld();
        if (s.d.nourriture().contains("zone") && s.zone != null) {
            Zone.Point p = s.zone.plusProche("nourriture", l.getX(), l.getZ());
            if (p != null && Geometrie.distance(p.x(), p.z(), s.mx, s.mz) <= s.laisse + 4) return new Location(w, p.x(), p.y(), p.z());
        }
        for (int essai = 0; essai < 28; essai++) {
            double x = cx + hasard.nextGaussian() * rayon, z = cz + hasard.nextGaussian() * rayon;
            if (Geometrie.distance(x, z, s.mx, s.mz) > s.laisse) continue;
            if (Geometrie.distance(x, z, cx, cz) > maxCentre) continue;
            if (s.zone != null && !s.zone.contient(x, z)) continue;
            Location g = sol(w, x, l.getY(), z, false);
            if (g == null) continue;
            String dessous = g.getBlock().getRelative(0, -1, 0).getType().name().toLowerCase(Locale.ROOT);
            String dessus = g.getBlock().getType().name().toLowerCase(Locale.ROOT);
            if (s.d.nourriture().contains(dessous) || s.d.nourriture().contains(dessus)) return g;
        }
        return null;
    }

    /** Rive praticable au bord de l'eau (dans la laisse), ou point « eau » de la zone. */
    private Location chercherEau(Suivi s) {
        Location l = s.e.getLocation();
        World w = l.getWorld();
        if (s.zone != null) {
            Zone.Point p = s.zone.plusProche("eau", l.getX(), l.getZ());
            if (p != null && Geometrie.distance(p.x(), p.z(), s.mx, s.mz) <= s.laisse + 4) return new Location(w, p.x(), p.y(), p.z());
        }
        for (int essai = 0; essai < 32; essai++) {
            double x = l.getX() + hasard.nextGaussian() * 8, z = l.getZ() + hasard.nextGaussian() * 8;
            if (Geometrie.distance(x, z, s.mx, s.mz) > s.laisse) continue;
            Location g = sol(w, x, l.getY(), z, true);
            if (g == null || !g.getBlock().isLiquid()) continue;
            // un bloc de terre ferme à côté de l'eau
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Location r = sol(w, g.getX() + o[0], g.getY() + 1, g.getZ() + o[1], false);
                if (r != null && Math.abs(r.getY() - g.getY()) <= 2) return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ registre des apparitions

    private Path fichierRegistre() { return dossier().resolve("donnees").resolve("apparitions.yml"); }

    private void lireRegistre() {
        registre.clear();
        Path f = fichierRegistre();
        if (!Files.isRegularFile(f)) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
        for (String id : y.getKeys(false)) {
            EtatApparition et = new EtatApparition();
            et.rempli = y.getBoolean(id + ".rempli");
            et.derniereMort = y.getLong(id + ".derniere-mort");
            ConfigurationSection m = y.getConfigurationSection(id + ".membres");
            if (m != null) for (String u : m.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(u);
                    et.membres.add(uuid);
                    List<Double> p = m.getDoubleList(u);
                    if (p.size() >= 3) et.positions.put(uuid, new double[]{p.get(0), p.get(1), p.get(2)});
                } catch (IllegalArgumentException ignore) { }
            }
            registre.put(id, et);
        }
    }

    void sauverRegistre() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, EtatApparition> e : registre.entrySet()) {
            EtatApparition et = e.getValue();
            y.set(e.getKey() + ".rempli", et.rempli);
            y.set(e.getKey() + ".derniere-mort", et.derniereMort);
            for (UUID u : et.membres) {
                double[] p = et.positions.get(u);
                y.set(e.getKey() + ".membres." + u, p == null ? List.of() : List.of(round(p[0]), round(p[1]), round(p[2])));
            }
        }
        try {
            Files.createDirectories(fichierRegistre().getParent());
            y.save(fichierRegistre().toFile());
            registreModifie = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Mobs : impossible d'écrire " + fichierRegistre() + " : " + ex.getMessage());
        }
    }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }

    // ------------------------------------------------------------------ exemple

    private void installerExemple() {
        try {
            Path liste = dossier().resolve("donnees").resolve("exemples.txt");
            List<String> faits = new ArrayList<>(Files.isRegularFile(liste) ? Files.readAllLines(liste) : List.of());
            if (faits.contains("mobs")) return;
            for (String f : List.of("mobs/exemple.yml", "zones/exemple.yml", "apparitions/exemple.yml")) {
                Path cible = dossier().resolve(f);
                if (Files.exists(cible)) continue;
                try (InputStream in = plugin.getClass().getClassLoader().getResourceAsStream("exemples/" + f)) {
                    if (in == null) continue;
                    Files.createDirectories(cible.getParent());
                    Files.copy(in, cible);
                }
            }
            faits.add("mobs");
            Files.createDirectories(liste.getParent());
            Files.write(liste, faits);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible de créer l'exemple de mobs : " + e.getMessage());
        }
    }
}
