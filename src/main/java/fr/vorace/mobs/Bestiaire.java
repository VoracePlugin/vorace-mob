package fr.vorace.mobs;

import fr.vorace.Messages;
import fr.vorace.modeles.ModeleBB;
import fr.vorace.modeles.Modeles;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Le jeu des comportements spéciaux du bestiaire de Babel ({@link Speciaux}) : parties à casser, faiblesses, lueur et
 * lumières éteintes, goule qui mange, Sauteur aveugle et son sceau, Attentive qu'on doit regarder, Glaneur et ses sacs,
 * offrande de la Lame-orpheline, tête-lanterne et lanterne posée du Passeur, formes de l'Armure veuve, entités de quête
 * qui voient le joueur. Le cerveau de combat ({@link IACombat}) et la Vie ({@link Combats}) l'appellent à leurs étapes.
 */
final class Bestiaire implements Listener {

    /** État d'un mob du bestiaire. */
    static final class Etat {
        boolean prepare, dormant, appelFait;
        /** Lueur : bloc de lumière posé ; lumières baissées : bloc → ancienne donnée. */
        Location lumiere;
        /** Dernier coup porté (tic, attaque) ; saisie ratée : punissable jusqu'à ce tic. */
        long derniereTouche = -1, rateeJusqua;
        String toucheAttaque;
        final Map<Location, BlockData> eteints = new HashMap<>();
        /** Parties : coups reçus, cassées ; dernière partie touchée (tic). */
        final Map<String, Integer> coupsParties = new HashMap<>();
        final Set<String> cassees = new HashSet<>();
        int branchesCoupees;
        String dernierePartie;
        long dernierePartieTic = -100, ouvertureJusqua, ramolliJusqua, figeJusqua, sceauJusqua;
        Competence derniereAttaque;
        /** Goule : corps visé, elle mange. */
        Corps corps;
        boolean mange;
        long dernierCoup;
        /** Sauteur : progression du sceau par joueur ; Attentive : regardée, regard par joueur. */
        final Map<UUID, Integer> sceau = new HashMap<>();
        final Map<UUID, Integer> regardes = new HashMap<>();
        boolean regardee;
        final Map<UUID, Long> pasPercu = new HashMap<>();
        /** Glaneur : sacs portés. */
        final List<Sac> sacs = new ArrayList<>();
        Sac sacVise;
        /** Lame-orpheline : à qui elle tend sa poignée, progression, refus, déjà proposée à ces joueurs. */
        UUID offreA;
        int offreProgres, refus;
        final Set<UUID> proposes = new HashSet<>();
        boolean accepte;
        /** Passeur : tête-lanterne (satellite), aveugle jusqu'à ce tic, reformée à ce tic ; lanterne posée. */
        Satellite tete, lanterne;
        long aveugleJusqua, reformeA, prochainHasard;
        boolean lanternePosee;
        /** Armure veuve : forme en cours (0, 1, 2...), recomposition, dégâts reçus pendant, pièce cassée. */
        int forme;
        long recomposeJusqua;
        double accumule, formeSuivante;
        boolean pieceCassee;
        /** Feu-follet : refuge (cul-de-sac) visé, arrivé. */
        Location refuge;
        boolean refugeCherche, auRefuge;
        /** Coups des joueurs sur lui (moyenne, pour les satellites). */
        final Map<UUID, double[]> moyennes = new HashMap<>();
        /** Entité de quête : point de ronde, sens, arrêt ; joueurs vus (fin du délai) ; repoussés à ce tic. */
        int rondeIndex, rondeSens = 1;
        long rondeArret;
        boolean rondeFinie;
        final Map<UUID, Long> vus = new HashMap<>();
        final Map<UUID, Long> repousses = new HashMap<>();
        /** Porte-Fardeau : ballot posé. */
        final List<Entity> ballot = new ArrayList<>();
        Location ballotOu;
        /** Rotation lente : dernier regard. */
        float yaw = Float.NaN;
    }

    /** Un corps de mob tombé (les goules le mangent). */
    record Corps(Location ou, long tic) {}

    /** Le sac d'un joueur mort (cristaux et matériaux perdus), au sol puis dans le sac du Glaneur. */
    static final class Sac {
        final UUID proprietaire;
        final List<ItemStack> objets;
        Location ou;
        final long tic;
        ItemDisplay affichage;
        Interaction prise;

        Sac(UUID proprietaire, List<ItemStack> objets, Location ou, long tic) {
            this.proprietaire = proprietaire; this.objets = objets; this.ou = ou; this.tic = tic;
        }
    }

    /** Partie détachée d'un boss qu'on frappe à part (tête-lanterne, lanterne posée). */
    static final class Satellite {
        final String quoi;
        final ItemDisplay affichage;
        final Interaction prise;
        double vie, vieMax;

        Satellite(String quoi, ItemDisplay affichage, Interaction prise, double vie) {
            this.quoi = quoi; this.affichage = affichage; this.prise = prise; this.vie = vie; this.vieMax = vie;
        }

        boolean valide() { return affichage != null && affichage.isValid() && prise != null && prise.isValid(); }

        void retirer() {
            if (affichage != null) affichage.remove();
            if (prise != null) prise.remove();
        }
    }

    private final VoraceMob plugin;
    private final Mobs mobs;
    private final Random hasard = new Random();
    private final Map<UUID, Etat> etats = new HashMap<>();
    private final List<Corps> corps = new ArrayList<>();
    private final List<Sac> sacsAuSol = new ArrayList<>();
    /** Joueur → {x, y, z, tic du dernier mouvement} (perception au mouvement). */
    private final Map<UUID, double[]> positions = new HashMap<>();
    /** Satellite (son interaction) → boss. */
    private final Map<UUID, UUID> satellites = new HashMap<>();
    /** Fragments d'âme : joueur → fins (ms) et valeurs. */
    private final Map<UUID, List<double[]>> fragments = new HashMap<>();
    /** Sacs à rendre à des joueurs hors ligne (rendus à leur arrivée). */
    private final Map<UUID, List<ItemStack>> aRendre = new HashMap<>();
    private final File fichierSacs;

    /** Contexte du coup en cours (plugin du combat) : type de dégâts et nature (melee, distance, zone). */
    private static String typeCoup, natureCoup;
    private static int ticContexte = -1;

    /** Le bestiaire en service (pour /vmob info). */
    static Bestiaire actif;

    Bestiaire(VoraceMob plugin, Mobs mobs) {
        actif = this;
        this.plugin = plugin;
        this.mobs = mobs;
        this.fichierSacs = new File(plugin.getDataFolder(), "donnees/sacs-a-rendre.yml");
        lireSacs();
    }

    private long now() { return mobs.tics(); }

    Etat etat(Mobs.Suivi s) { return etats.computeIfAbsent(s.e.getUniqueId(), k -> new Etat()); }

    private static Speciaux.Mob sp(Mobs.Suivi s) {
        return s.d.combattant() ? s.d.combat().meca().sp() : Speciaux.Mob.AUCUN;
    }

    private Modeles.Instance modele(Mobs.Suivi s) {
        return plugin.modeles() == null ? null : plugin.modeles().instance(s.e);
    }

    private static boolean a(Modeles.Instance m, String anim) {
        return m != null && anim != null && m.modele().animations().containsKey(anim);
    }

    private void jouer(Mobs.Suivi s, String role, boolean boucle) {
        Modeles.Instance m = modele(s);
        String n = s.d.animation(role);
        if (n == null) n = role;
        if (a(m, n)) m.jouer(n, boucle);
    }

    /** Envoie un événement du bestiaire au joueur (le plugin des quêtes le compte). */
    void evenement(Player p, Mobs.Suivi s, String cle) {
        if (p == null || cle == null || cle.isBlank()) return;
        Bukkit.getPluginManager().callEvent(new VoraceMobEvenementEvent(p, s == null ? null : s.e, s == null ? null : s.d.id(), cle.trim()));
    }

    /** Le plugin du combat dit ce qu'est le coup qu'il va infliger (type de dégâts, « distance », « zone » ou « melee »). */
    static void contexte(String type, String nature) {
        typeCoup = type == null ? null : type.toLowerCase(Locale.ROOT);
        natureCoup = nature == null ? null : nature.toLowerCase(Locale.ROOT);
        ticContexte = Bukkit.getCurrentTick();
    }

    // ================================================================== apparition et chaque tic

    /** Premier passage : les rôles « touche » et « mort » du mob servent aussi aux réactions automatiques du modèle. */
    private void preparer(Mobs.Suivi s, Etat e) {
        e.prepare = true;
        Modeles.Instance m = modele(s);
        if (m != null) {
            alias(m, s.d.animation("touche"), Modeles.AUTO.get("degat"), "hurt");
            alias(m, s.d.animation("mort"), Modeles.AUTO.get("mort"), "death");
        }
        if (s.d.quete().invulnerable()) s.e.setInvulnerable(true);
        // entité de quête : ni l'IA du jeu ni celle de Vorace, le bestiaire la mène (ronde, regard)
        if (s.d.quete() != Speciaux.Quete.AUCUNE && !s.d.combattant() && s.e instanceof Mob mob) mob.setAware(false);
        // une entité de quête volante qui fait sa ronde : c'est la ronde qui la mène (pas le vol de Vorace)
        if (s.d.quete().ronde() != null && s.d.volant()) { s.vol = null; s.e.setGravity(false); }
        Speciaux.Mob sp = sp(s);
        if (sp.leurre() != null && !s.sansButin) poserBallot(s, e, sp.leurre());
    }

    /** Si le modèle n'a aucune animation du rôle automatique, celle du mob y répond (clé ajoutée au modèle chargé). */
    private static void alias(Modeles.Instance m, String anim, List<String> noms, String cle) {
        if (anim == null || noms == null || !m.modele().animations().containsKey(anim)) return;
        for (String n : noms) if (m.modele().animations().containsKey(n)) return;
        try {
            ModeleBB.Animation a = m.modele().animations().get(anim);
            m.modele().animations().putIfAbsent(cle, a);
        } catch (UnsupportedOperationException ignore) { }
    }

    /** Un mob vient d'apparaître (point d'apparition ou invocation). */
    void apparition(Mobs.Suivi s) {
        Etat e = etat(s);
        if (!e.prepare) preparer(s, e);
    }

    /** Vrai si le bestiaire mène ce mob à la place du cerveau de combat (goule qui va manger, feu-follet qui fuit). */
    boolean gere(Mobs.Suivi s) {
        Etat e = etats.get(s.e.getUniqueId());
        if (e == null || s.ia == null || s.ia.enCours != null) return false;
        if (e.corps != null) return true;
        return sp(s) != Speciaux.Mob.AUCUN && s.d.combat().meca().fuite() != null && s.d.combat().meca().siArrive() == null && s.ia.engage && !e.auRefuge;
    }

    /** Vrai s'il ne cherche personne (Porte-Fardeau sous l'eau tant qu'on n'a pas touché au ballot). */
    boolean dormant(Mobs.Suivi s) {
        Etat e = etats.get(s.e.getUniqueId());
        return e != null && e.dormant;
    }

    void tic() {
        long t = now();
        if (t % 2 == 0) suivrePositions(t);
        for (Mobs.Suivi s : new ArrayList<>(mobs.suivis())) {
            if (!s.e.isValid()) continue;
            Speciaux.Mob sp = sp(s);
            Speciaux.Quete q = s.d.quete();
            if (sp == Speciaux.Mob.AUCUN && q == Speciaux.Quete.AUCUNE && !aDesAttaques(s)) continue;
            Etat e = etat(s);
            if (!e.prepare) preparer(s, e);
            try {
                if (q != Speciaux.Quete.AUCUNE) ticQuete(s, e, q, t);
                if (s.d.combattant()) ticCombat(s, e, sp, t);
            } catch (Throwable ex) {
                plugin.getLogger().warning("Bestiaire " + s.d.id() + " : " + ex);
            }
        }
        if (t % 20 == 0) {
            corps.removeIf(c -> t - c.tic() > 1200);
            for (Iterator<Map.Entry<UUID, Etat>> it = etats.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Etat> x = it.next();
                if (mobs.suivi(Bukkit.getEntity(x.getKey())) == null) {
                    for (Sac sac : x.getValue().sacs) rendreSac(sac);
                    x.getValue().sacs.clear();
                    nettoyer(x.getValue());
                    it.remove();
                }
            }
        }
    }

    private static boolean aDesAttaques(Mobs.Suivi s) {
        if (!s.d.combattant()) return false;
        for (Competence c : s.d.combat().competences()) if (c.sp() != Speciaux.Attaque.AUCUNE) return true;
        return false;
    }

    private void suivrePositions(long t) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            double[] v = positions.get(p.getUniqueId());
            if (v == null || Math.abs(v[0] - l.getX()) + Math.abs(v[1] - l.getY()) + Math.abs(v[2] - l.getZ()) > 0.08) {
                positions.put(p.getUniqueId(), new double[]{l.getX(), l.getY(), l.getZ(), t});
            }
        }
    }

    /** Vrai si le joueur n'a pas bougé depuis {@code tics} tics. */
    private boolean immobile(Player p, long tics) {
        double[] v = positions.get(p.getUniqueId());
        return v != null && now() - v[3] >= tics;
    }

    private void ticCombat(Mobs.Suivi s, Etat e, Speciaux.Mob sp, long t) {
        IACombat.Etat ia = s.ia;
        // une attaque vient de finir : la tête découverte (Porte-Fardeau)
        if (ia != null) {
            if (e.derniereAttaque != null && ia.enCours == null && sp.ouverture() != null) e.ouvertureJusqua = t + sp.ouverture().duree();
            e.derniereAttaque = ia.enCours;
            if (ia.engage && !e.appelFait && !ia.conditions.containsKey("avant_appel") && aUnAppel(s)) ia.conditions.put("avant_appel", 1);
        }
        if (sp.lueur() != null && t % 5 == 0) lueur(s, e, sp.lueur());
        if (sp.eteintLumiere() != null && t % 10 == 0) eteindre(s, e, sp.eteintLumiere());
        if (sp.manger() != null) manger(s, e, sp.manger(), t);
        if (sp.perceptionMouvement() && ia != null && ia.engage && t % 10 == 0) perdreDeVue(s, e, t);
        if (sp.sceau() != null && t % 2 == 0) sceau(s, e, sp.sceau(), t);
        if (sp.regard() != null && t % 2 == 0) regardSubi(s, e, sp.regard(), t);
        if (sp.sac() != null && t % 10 == 0) glaner(s, e, sp.sac(), t);
        if (sp.offrande() != null && t % 5 == 0) offrir(s, e, sp.offrande(), t);
        if (sp.teteLanterne() != null) tete(s, e, sp.teteLanterne(), t);
        if (sp.lanternePosee() != null) lanterne(s, e, sp.lanternePosee(), t);
        if (sp.recomposition() != null && e.recomposeJusqua > 0 && t >= e.recomposeJusqua) finRecomposition(s, e, sp);
        if (sp.rotation() > 0) tourner(s, e, sp.rotation());
        if (ia != null && ia.engage && s.d.combat().meca().fuite() != null && s.d.combat().meca().siArrive() == null) refuge(s, e, t);
        if (ia != null && e.auRefuge && ia.engage) ia.immobile = Math.max(ia.immobile, t + 2);
        if (ia != null && e.figeJusqua > t) { ia.immobile = Math.max(ia.immobile, t + 2); ia.inactif = Math.max(ia.inactif, t + 2); }
        if (ia != null && !ia.engage) {
            if (e.tete != null) { retirerSatellite(e.tete); e.tete = null; }
            if (e.lanterne != null && !s.ia.rentre) { retirerSatellite(e.lanterne); e.lanterne = null; e.lanternePosee = false; }
            e.forme = 0; e.recomposeJusqua = 0; e.pieceCassee = false; e.auRefuge = false; e.refugeCherche = false; e.refuge = null;
            e.appelFait = false; e.aveugleJusqua = 0;
        }
    }

    private static boolean aUnAppel(Mobs.Suivi s) {
        for (Competence c : s.d.combat().competences()) if (c.sp().appel() > 0) return true;
        return false;
    }

    // ================================================================== lueur et lumières éteintes

    private void lueur(Mobs.Suivi s, Etat e, Speciaux.Lueur lu) {
        Modeles.Instance m = modele(s);
        Location ou = m != null && lu.os() != null ? m.positionOs(lu.os()) : null;
        if (ou == null) ou = s.e.getLocation().add(0, 1.2, 0);
        ou.getWorld().spawnParticle(Particle.DUST, ou, 3, 0.12, 0.12, 0.12, 0, new Particle.DustOptions(Color.fromRGB(lu.couleur()), 1.1f));
        Block b = ou.getBlock();
        if (e.lumiere != null && e.lumiere.getBlock().equals(b)) return;
        eteindreLueur(e);
        if (b.getType() != Material.AIR && b.getType() != Material.CAVE_AIR) return;
        BlockData d = Material.LIGHT.createBlockData();
        if (d instanceof Levelled lv) lv.setLevel(Math.min(lv.getMaximumLevel(), lu.luminosite()));
        b.setBlockData(d, false);
        e.lumiere = b.getLocation();
    }

    private static void eteindreLueur(Etat e) {
        if (e.lumiere == null) return;
        Block b = e.lumiere.getBlock();
        if (b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
        e.lumiere = null;
    }

    /** Lumières à {@code rayon} blocs : torches, lanternes et feux de camp passent à leur version d'âme (−4 à −5 niveaux). */
    private void eteindre(Mobs.Suivi s, Etat e, Speciaux.EteintLumiere el) {
        Location l = s.e.getLocation();
        World w = l.getWorld();
        int r = (int) Math.ceil(el.rayon());
        // rallumer ce qui est trop loin
        for (Iterator<Map.Entry<Location, BlockData>> it = e.eteints.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Location, BlockData> x = it.next();
            if (!x.getKey().getWorld().equals(w) || x.getKey().distanceSquared(l) > (el.rayon() + 2) * (el.rayon() + 2)) {
                rallumer(x.getKey(), x.getValue());
                it.remove();
            }
        }
        for (int dx = -r; dx <= r; dx++) for (int dy = -3; dy <= 4; dy++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > el.rayon() * el.rayon()) continue;
            Block b = w.getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
            String ame = ame(b.getType());
            if (ame == null) continue;
            BlockData avant = b.getBlockData();
            try {
                b.setBlockData(Bukkit.createBlockData(avant.getAsString().replace("minecraft:" + b.getType().getKey().getKey(), "minecraft:" + ame)), false);
                e.eteints.put(b.getLocation(), avant);
            } catch (IllegalArgumentException ignore) { }
        }
    }

    private static String ame(Material m) {
        return switch (m) {
            case TORCH -> "soul_torch";
            case WALL_TORCH -> "soul_wall_torch";
            case LANTERN -> "soul_lantern";
            case CAMPFIRE -> "soul_campfire";
            case JACK_O_LANTERN -> "carved_pumpkin";
            default -> null;
        };
    }

    private static void rallumer(Location ou, BlockData avant) {
        Block b = ou.getBlock();
        String n = b.getType().name();
        if (n.startsWith("SOUL_") || b.getType() == Material.CARVED_PUMPKIN) b.setBlockData(avant, false);
    }

    private void nettoyer(Etat e) {
        eteindreLueur(e);
        for (Map.Entry<Location, BlockData> x : e.eteints.entrySet()) rallumer(x.getKey(), x.getValue());
        e.eteints.clear();
        if (e.tete != null) retirerSatellite(e.tete);
        if (e.lanterne != null) retirerSatellite(e.lanterne);
        for (Entity x : e.ballot) x.remove();
        e.ballot.clear();
    }

    // ================================================================== coups reçus

    /** Partie du modèle visée par le joueur (rayon de son regard, os le plus proche à 0,7 bloc), ou null. */
    private Speciaux.Partie partieVisee(Mobs.Suivi s, Speciaux.Mob sp, Player p, List<String> autres) {
        Modeles.Instance m = modele(s);
        if (m == null || p == null) return null;
        Location oeil = p.getEyeLocation();
        Vector dir = oeil.getDirection();
        String meilleur = null;
        double best = 0.7 * 0.7;
        for (ModeleBB.Partie pa : m.modele().parties()) {
            String n = pa.nom() == null ? "" : pa.nom().toLowerCase(Locale.ROOT);
            boolean utile = sp.partieDeLOs(n) != null;
            for (String x : autres) if (x != null && n.contains(x)) utile = true;
            if (!utile) continue;
            Location o = m.positionOs(pa.nom());
            if (o == null || !o.getWorld().equals(oeil.getWorld())) continue;
            Vector v = o.toVector().subtract(oeil.toVector());
            double tt = v.dot(dir);
            if (tt < 0 || tt > 8) continue;
            double d2 = v.lengthSquared() - tt * tt;
            if (d2 < best) { best = d2; meilleur = n; }
        }
        if (meilleur == null) return null;
        Speciaux.Partie pa = sp.partieDeLOs(meilleur);
        return pa != null ? pa : new Speciaux.Partie(meilleur, List.of(meilleur), 0, null, 1, 1, null, null);
    }

    /**
     * Dégâts d'un coup reçu après les comportements du bestiaire (avant la garde) : invulnérable, intangible, recomposition,
     * immunité aux tirs, faiblesses, parties, ouverture, sceau, hitbox basse, Lame-orpheline qui se rend.
     */
    double recu(Mobs.Suivi s, double montant, Player source) {
        Speciaux.Mob sp = sp(s);
        Competence enCours = s.ia == null ? null : s.ia.enCours;
        boolean special = sp != Speciaux.Mob.AUCUN || (enCours != null && enCours.sp().immuniteProjectiles() > 0);
        String type = Bukkit.getCurrentTick() - ticContexte <= 1 ? typeCoup : null;
        String nature = Bukkit.getCurrentTick() - ticContexte <= 1 ? natureCoup : null;
        if (!special) return montant;
        long t = now();
        Etat e = etat(s);
        if (nature == null && source != null) nature = source.getLocation().distanceSquared(s.e.getLocation()) > 4.5 * 4.5 ? "distance" : "melee";
        if (source != null && montant > 0) {
            double[] mo = e.moyennes.computeIfAbsent(source.getUniqueId(), k -> new double[2]);
            mo[0] += montant; mo[1]++;
            if (mo[1] > 8) { mo[0] *= 8 / mo[1]; mo[1] = 8; }
        }
        // immunité aux tirs pendant une attaque (Danse-parade)
        if (enCours != null && "distance".equals(nature) && enCours.sp().immuniteProjectiles() > 0 && t - s.ia.t0 <= enCours.sp().immuniteProjectiles()) {
            s.e.getWorld().spawnParticle(Particle.CRIT, s.e.getLocation().add(0, 1, 0), 12, 0.4, 0.5, 0.4, 0.3);
            s.e.getWorld().playSound(s.e.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1.4f);
            return 0;
        }
        if (sp == Speciaux.Mob.AUCUN) return montant;
        // l'Attentive : intangible quand personne ne la regarde
        if (sp.regard() != null && sp.regard().solide() && !e.regardee) {
            s.e.getWorld().spawnParticle(Particle.SMOKE, s.e.getLocation().add(0, 1.4, 0), 10, 0.3, 0.6, 0.3, 0.01);
            if (source != null) source.sendActionBar(Messages.mini("<gray>Tes coups la traversent : il faut qu'on la regarde."));
            return 0;
        }
        // l'Armure veuve se recompose : les coups cassent une pièce de la forme suivante
        if (sp.recomposition() != null && e.recomposeJusqua > t) {
            e.accumule += montant;
            if (!e.pieceCassee && e.accumule >= sp.recomposition().pieceCasseeA() * e.formeSuivante) casserPiece(s, e, sp.recomposition());
            return 0;
        }
        // la goule qui mange s'arrête
        if (e.mange || e.corps != null) arreterRepas(s, e);
        e.dernierCoup = t;
        // hitbox basse (Squelette rampant) : un coup porté trop haut passe au-dessus
        if (sp.hitboxBasse() > 0 && source != null && "melee".equals(nature) && tropHaut(s, source, sp.hitboxBasse())) {
            source.sendActionBar(Messages.mini("<gray>Trop haut : vise plus bas."));
            return 0;
        }
        double m = montant;
        Speciaux.Faiblesse fa = sp.faiblesse();
        if (fa != null) {
            if ("distance".equals(nature)) m *= fa.distance();
            if ("zone".equals(nature)) m *= fa.zone();
            if (type != null) {
                Double f = fa.types().get(type);
                if (f != null) {
                    m *= f;
                    if (fa.ramollit() > 0) e.ramolliJusqua = t + fa.ramollit();
                }
            }
        }
        if (sp.figeePar() != null && type != null && sp.figeePar().types().contains(type)) {
            e.figeJusqua = t + sp.figeePar().duree();
            mobs.ia().entraver(s, sp.figeePar().duree() * 50L, false, false);
            s.e.getWorld().spawnParticle(Particle.SNOWFLAKE, s.e.getLocation().add(0, 1, 0), 30, 0.5, 0.6, 0.5, 0.02);
            mobs.ia().noter(s, "figee", 1);
            evenement(source, s, sp.figeePar().evenement());
        }
        // parties (cordes, branches, cuve, mèche, tête)
        if (source != null && ("melee".equals(nature) || "distance".equals(nature))) {
            List<String> autres = new ArrayList<>();
            if (sp.pointFaiblePartie() != null) autres.add(sp.pointFaiblePartie().partie());
            if (sp.ouverture() != null) { autres.add(sp.ouverture().partie()); autres.add("head"); autres.add("h_head"); }
            Speciaux.Partie pa = partieVisee(s, sp, source, autres);
            if (pa != null) {
                e.dernierePartie = pa.nom();
                e.dernierePartieTic = t;
                m *= pa.multiplicateur();
                if (pa.coups() > 0) coupSurPartie(s, e, pa, source);
                if (sp.ouverture() != null && t < e.ouvertureJusqua && (pa.nom().contains(sp.ouverture().partie()) || pa.nom().contains("head"))) {
                    m *= sp.ouverture().multiplicateur();
                    s.e.getWorld().spawnParticle(Particle.ENCHANTED_HIT, s.e.getLocation().add(0, 1.6, 0), 10, 0.2, 0.2, 0.2, 0.1);
                }
            }
        }
        // sceau recollé : figé, ses coups de dos font plus mal
        if (sp.sceau() != null && t < e.sceauJusqua && source != null && dans_le_dos(s, source)) m *= sp.sceau().degatsDos();
        // Passeur : tête détruite, corps aveugle (+20 %) ; lanterne posée : le corps ne prend presque plus rien
        if (sp.teteLanterne() != null && t < e.aveugleJusqua) m *= sp.teteLanterne().degatsRecus();
        if (sp.lanternePosee() != null && e.lanternePosee && e.lanterne != null) m *= 1 - sp.lanternePosee().reductionCorps();
        // la Lame-orpheline ne meurt pas : elle se rend
        if (sp.invincible() != null && s.vie - m < sp.invincible().seRendA()) m = Math.max(0, s.vie - sp.invincible().seRendA());
        return Math.max(0, m);
    }

    /** Après un coup (Vie déjà retirée) : formes de l'Armure veuve, Lame-orpheline qui se rend. */
    void apresCoup(Mobs.Suivi s, Player source) {
        Speciaux.Mob sp = sp(s);
        if (sp == Speciaux.Mob.AUCUN || s.vie <= 0) return;
        Etat e = etat(s);
        if (sp.invincible() != null && s.vie <= sp.invincible().seRendA() + 1e-6) rendre(s, e, sp, source);
        if (sp.recomposition() != null && sp.formes().size() > 1 && e.recomposeJusqua == 0) {
            double seuil = 1;
            for (int k = 0; k <= e.forme && k < sp.formes().size(); k++) seuil -= sp.formes().get(k);
            if (e.forme < sp.formes().size() - 1 && s.vie <= seuil * s.vieMax + 1e-6) recomposer(s, e, sp, seuil);
        }
    }

    private boolean dans_le_dos(Mobs.Suivi s, Player p) {
        Location ml = s.e.getLocation(), pl = p.getLocation();
        return Mecaniques.angle(ml.getYaw(), pl.getX() - ml.getX(), pl.getZ() - ml.getZ()) >= 120;
    }

    /** Vrai si le regard du joueur passe au-dessus de la hitbox basse (à plus de {@code h} + 0,35 bloc du sol du mob). */
    private static boolean tropHaut(Mobs.Suivi s, Player p, double h) {
        Location oeil = p.getEyeLocation(), l = s.e.getLocation();
        Vector dir = oeil.getDirection();
        double dx = l.getX() - oeil.getX(), dz = l.getZ() - oeil.getZ();
        double hz = Math.hypot(dir.getX(), dir.getZ());
        if (hz < 1e-3) return false;
        double t = (dx * dir.getX() + dz * dir.getZ()) / (hz * hz);
        if (t <= 0) return false;
        double y = oeil.getY() + dir.getY() * t;
        return y - l.getY() > h + 0.35;
    }

    /** Point faible lié à une partie : vrai si le dernier coup (ce tic) l'a touchée. */
    boolean partieTouchee(Mobs.Suivi s, String partie) {
        Etat e = etats.get(s.e.getUniqueId());
        return e != null && e.dernierePartie != null && now() - e.dernierePartieTic <= 1 && e.dernierePartie.contains(partie);
    }

    /** Point faible touché (la mèche du Cierge de dos) : la condition et l'événement. */
    void pointFaibleTouche(Mobs.Suivi s, Player source) {
        Speciaux.Mob sp = sp(s);
        if (sp.pointFaiblePartie() == null) return;
        mobs.ia().noter(s, sp.pointFaiblePartie().partie() + "_eteinte", 1);
        evenement(source, s, sp.pointFaiblePartie().evenement());
    }

    private void coupSurPartie(Mobs.Suivi s, Etat e, Speciaux.Partie pa, Player source) {
        if (e.cassees.contains(pa.nom()) && !"un-tir-de-moins".equals(pa.effet())) return;
        int n = e.coupsParties.merge(pa.nom(), 1, Integer::sum);
        Location l = s.e.getLocation().add(0, 1.2, 0);
        l.getWorld().spawnParticle(Particle.CRIT, l, 6, 0.3, 0.3, 0.3, 0.1);
        if (n % pa.coups() != 0) return;
        String effet = pa.effet() == null ? "" : pa.effet();
        if (effet.equals("un-tir-de-moins")) {
            if (e.branchesCoupees >= pa.nombre()) return;
            e.branchesCoupees++;
            String[] noms = {"haute", "gauche", "basse"};
            jouer(s, "branche_coupee_" + noms[Math.min(noms.length - 1, e.branchesCoupees - 1)], false);
            if (s.ia != null && s.ia.munitions > 0) s.ia.munitions = Math.min(s.ia.munitions, Math.max(0, munitionsMax(s, Integer.MAX_VALUE)));
            mobs.ia().noter(s, "branche_coupee", 1);
            if (e.branchesCoupees >= pa.nombre()) e.cassees.add(pa.nom());
        } else {
            e.cassees.add(pa.nom());
            if (effet.equals("coupe-aura") && s.ia != null) s.ia.purifieJusqua = Long.MAX_VALUE;
        }
        if (pa.condition() != null) mobs.ia().noter(s, pa.condition(), 1);
        l.getWorld().playSound(l, Sound.BLOCK_CHAIN_BREAK, 1f, 0.8f);
        l.getWorld().spawnParticle(Particle.BLOCK, l, 20, 0.3, 0.3, 0.3, 0, Material.BONE_BLOCK.createBlockData());
        evenement(source, s, pa.evenement());
    }

    /** Munitions maximales (branches coupées : une bougie de moins chacune). */
    int munitionsMax(Mobs.Suivi s, int nombre) {
        Etat e = etats.get(s.e.getUniqueId());
        Mecaniques.Munitions mu = s.d.combat().meca().munitions();
        int base = Math.min(nombre, mu == null ? nombre : mu.nombre());
        return e == null ? base : Math.max(0, base - e.branchesCoupees);
    }

    /** Défense divisée par deux (Cierge ramolli par le feu). */
    boolean ramolli(Mobs.Suivi s) {
        Etat e = etats.get(s.e.getUniqueId());
        return e != null && now() < e.ramolliJusqua;
    }

    // ================================================================== ciblage

    /** Vrai si ce mob perçoit ce joueur à {@code distance} blocs (Sauteur au mouvement, vision de la Faucheuse, lumière). */
    boolean percoit(Mobs.Suivi s, Player p, double distance) {
        Speciaux.Mob sp = sp(s);
        if (sp == Speciaux.Mob.AUCUN) return true;
        if (sp.perceptionMouvement() && p.isSneaking() && immobile(p, 10)) return false;
        Speciaux.Vision v = sp.vision();
        if (v != null) {
            Location ml = s.e.getLocation(), pl = p.getLocation();
            double angle = Mecaniques.angle(ml.getYaw(), pl.getX() - ml.getX(), pl.getZ() - ml.getZ());
            double portee = angle <= v.angle() / 2 ? v.devant() : v.derriere();
            if (p.isSneaking()) portee *= v.accroupi();
            if (distance > portee) return false;
        }
        Etat e = etats.get(s.e.getUniqueId());
        return e == null || !e.dormant;
    }

    /** Le Sauteur perd le joueur accroupi et immobile depuis 2 s. */
    private void perdreDeVue(Mobs.Suivi s, Etat e, long t) {
        for (Iterator<Map.Entry<UUID, Double>> it = s.ia.menace.entrySet().iterator(); it.hasNext(); ) {
            Player p = Bukkit.getPlayer(it.next().getKey());
            if (p == null) continue;
            if (p.isSneaking() && immobile(p, 10)) {
                long depuis = e.pasPercu.computeIfAbsent(p.getUniqueId(), k -> t);
                if (t - depuis >= 40) {
                    it.remove();
                    if (p.getUniqueId().equals(s.ia.cible)) s.ia.cible = null;
                    e.pasPercu.remove(p.getUniqueId());
                }
            } else e.pasPercu.remove(p.getUniqueId());
        }
    }

    /** Cible préférée : la lumière la plus forte (Mèche-sourde), une cible au hasard si la tête est détruite (Passeur). */
    Player preferer(Mobs.Suivi s, Player choisie) {
        Speciaux.Mob sp = sp(s);
        if (sp == Speciaux.Mob.AUCUN || s.ia == null) return choisie;
        Etat e = etat(s);
        long t = now();
        if (sp.teteLanterne() != null && t < e.aveugleJusqua) {
            if (t >= e.prochainHasard || choisie == null) {
                List<Player> l = new ArrayList<>();
                for (UUID u : s.ia.menace.keySet()) { Player p = Bukkit.getPlayer(u); if (p != null && mobs.ia().cibleValide(p, s.e)) l.add(p); }
                if (!l.isEmpty()) { choisie = l.get(hasard.nextInt(l.size())); s.ia.cible = choisie.getUniqueId(); }
                e.prochainHasard = t + 40;
            } else {
                Player p = s.ia.cible == null ? null : Bukkit.getPlayer(s.ia.cible);
                if (p != null) choisie = p;
            }
            return choisie;
        }
        if (sp.cibleLumiere()) {
            Player meilleur = null;
            int best = 0;
            for (UUID u : s.ia.menace.keySet()) {
                Player p = Bukkit.getPlayer(u);
                if (p == null || !mobs.ia().cibleValide(p, s.e)) continue;
                int lu = lumiere(p);
                if (lu > best) { best = lu; meilleur = p; }
            }
            if (meilleur != null) { s.ia.cible = meilleur.getUniqueId(); return meilleur; }
        }
        return choisie;
    }

    /** Lumière tenue en main par le joueur (0 à 15). */
    static int lumiere(Player p) {
        return Math.max(lumiere(p.getInventory().getItemInMainHand().getType()), lumiere(p.getInventory().getItemInOffHand().getType()));
    }

    static int lumiere(Material m) {
        return switch (m) {
            case LANTERN, GLOWSTONE, SEA_LANTERN, JACK_O_LANTERN, SHROOMLIGHT, LIGHT, BEACON, LAVA_BUCKET, OCHRE_FROGLIGHT,
                 VERDANT_FROGLIGHT, PEARLESCENT_FROGLIGHT -> 15;
            case TORCH, END_ROD -> 14;
            case SOUL_LANTERN, SOUL_TORCH -> 10;
            case REDSTONE_TORCH -> 7;
            default -> 0;
        };
    }

    // ================================================================== attaques

    /** Conditions d'une attaque du bestiaire : colle, cibles-min, après la musique, conditions, hors du regard. */
    boolean attaquePossible(Mobs.Suivi s, Competence a, Player cible, double distance) {
        Speciaux.Attaque x = a.sp();
        if (x == Speciaux.Attaque.AUCUNE) return true;
        long t = now();
        if (x.colle() > 0 && !colle(s, cible, Math.max(a.porteeMax(), a.rayon()) + 0.3, x.colle())) return false;
        if (x.apresJeu() > 0 && t - s.ia.debut < x.apresJeu()) return false;
        if (x.horsRegard()) {
            Etat e = etats.get(s.e.getUniqueId());
            if (e != null && e.regardee) return false;
        }
        if (!x.ligneDeVue() && a.forme() == Competence.Forme.PROJECTILE) { /* sans ligne de vue : rien à vérifier ici */ }
        if (x.ciblesMin() > 1) {
            int n = 0;
            Location l = s.e.getLocation();
            double r = Math.max(a.rayon(), a.porteeMax());
            for (Player p : l.getWorld().getPlayers()) if (mobs.ia().cibleValide(p, s.e) && p.getLocation().distanceSquared(l) <= r * r) n++;
            if (n < x.ciblesMin()) return false;
        }
        if (!x.si().isEmpty()) {
            boolean ok = false;
            for (String c : x.si()) {
                String k = c.replace(' ', '_').replace("é", "e");
                if (k.contains("dernier")) ok |= dernierDuGroupe(s);
                else if (k.contains("branche")) ok |= s.ia.conditions.containsKey("branche_coupee");
                else ok |= s.ia.conditions.containsKey(k);
            }
            if (!ok) return false;
        }
        return true;
    }

    /** Vrai si la cible est restée à sa portée {@code tics} tics d'affilée. */
    private boolean colle(Mobs.Suivi s, Player cible, double portee, int tics) {
        if (cible == null) return false;
        Etat e = etat(s);
        long t = now();
        String cle = "colle:" + cible.getUniqueId();
        boolean pres = cible.getLocation().distanceSquared(s.e.getLocation()) <= portee * portee;
        if (!pres) { e.coupsParties.remove(cle); return false; }
        int depuis = e.coupsParties.computeIfAbsent(cle, k -> (int) t);
        return t - depuis >= tics;
    }

    /** Suivi de « colle » : appelé chaque tic de combat (il faut voir le joueur partir). */
    private boolean dernierDuGroupe(Mobs.Suivi s) {
        for (Mobs.Suivi o : mobs.suivis()) {
            if (o == s || !o.d.combattant() || o.vie <= 0 || !o.e.isValid() || !o.e.getWorld().equals(s.e.getWorld())) continue;
            boolean meme = s.apparition != null ? s.apparition.equals(o.apparition) : o.d.id().equals(s.d.id());
            if (meme && o.e.getLocation().distanceSquared(s.e.getLocation()) < 40 * 40) return false;
        }
        return true;
    }

    /** Animation d'une attaque : une branche par tir (la première encore entière), sinon la sienne. */
    String animationAttaque(Mobs.Suivi s, Competence c) {
        List<String> br = c.sp().branches();
        if (br.isEmpty()) return c.animation();
        Etat e = etat(s);
        Mecaniques.Munitions mu = s.d.combat().meca().munitions();
        // demarrer a déjà retiré la munition de ce tir
        int tires = mu == null || s.ia == null || s.ia.munitions < 0 ? 0
            : Math.max(0, munitionsMax(s, mu.nombre()) - s.ia.munitions - (c.munitions() ? 1 : 0));
        int k = Math.min(br.size() - 1, e.branchesCoupees + tires);
        return br.get(Math.max(0, k));
    }

    /** Centre d'un cercle : sa lanterne posée ({@code centre: lanterne}), sinon lui. */
    Location centre(Mobs.Suivi s, Competence c, Location defaut) {
        if (!"lanterne".equals(c.sp().centre())) return defaut;
        Etat e = etats.get(s.e.getUniqueId());
        return e != null && e.lanterne != null && e.lanterne.valide() ? e.lanterne.affichage.getLocation().clone().add(0, -0.2, 0) : defaut;
    }

    /** Un joueur en l'air saute l'attaque ({@code sautable}). */
    static boolean saute(Competence c, Player p) {
        if (!c.sp().sautable()) return false;
        Block dessous = p.getLocation().clone().subtract(0, 0.2, 0).getBlock();
        return dessous.isPassable() && p.getVelocity().getY() > -0.5;
    }

    /** Le coup d'une attaque vient de partir : appel, charge contre une paroi, sacrifice. */
    void apresExecution(Mobs.Suivi s, Mob mob, Competence c, Player vise) {
        long t = now();
        if (c.sp().evenementRatee() != null) {
            // saisie ratée : la goule reste à découvert (fenêtre du Guerrier, E01-Q071)
            Etat e = etat(s);
            if (!(e.derniereTouche == t && c.id().equals(e.toucheAttaque))) e.rateeJusqua = t + c.recuperation() + 40;
        }
        Speciaux.Attaque x = c.sp();
        if (x == Speciaux.Attaque.AUCUNE) return;
        if (x.appel() > 0) {
            Etat e = etat(s);
            e.appelFait = true;
            s.ia.conditions.remove("avant_appel");
            Location l = mob.getLocation();
            l.getWorld().playSound(l, Sound.ENTITY_GHAST_SCREAM, 1.2f, 1.4f);
            l.getWorld().spawnParticle(Particle.SONIC_BOOM, l.clone().add(0, 1, 0), 1);
            for (Mobs.Suivi o : mobs.suivis()) {
                if (o == s || !o.d.combattant() || o.ia == null || o.ia.engage || o.vie <= 0 || !o.e.getWorld().equals(l.getWorld())) continue;
                if (o.e.getLocation().distanceSquared(l) > x.appel() * x.appel() || vise == null) continue;
                mobs.ia().ajouterMenace(o, vise, 5);
            }
        }
        if (x.contreParoi() > 0 && c.forme() == Competence.Forme.LIGNE) charger(s, mob, c, x.contreParoi());
        if (x.sacrifice()) {
            mobs.marquerSansButin(s);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (s.e instanceof LivingEntity le && le.isValid() && !le.isDead()) { s.vie = 0; le.setHealth(0); }
            });
        }
    }

    /** Charge : il fonce sur la ligne ; arrêté par une paroi avant le bout, il est étourdi. */
    private void charger(Mobs.Suivi s, Mob mob, Competence c, int etourdi) {
        Vector dir = s.ia.direction == null ? mob.getLocation().getDirection().setY(0).normalize() : s.ia.direction.clone();
        Location l = mob.getLocation();
        double libre = 0;
        for (double k = 0.5; k <= c.rayon(); k += 0.5) {
            Block pieds = l.getWorld().getBlockAt((int) Math.floor(l.getX() + dir.getX() * k), l.getBlockY(), (int) Math.floor(l.getZ() + dir.getZ() * k));
            if (!pieds.isPassable() || !pieds.getRelative(0, 1, 0).isPassable()) break;
            libre = k;
        }
        mob.getPathfinder().stopPathfinding();
        mob.setVelocity(dir.clone().multiply(Math.max(0.2, libre / 5.0)).setY(0.05));
        s.ia.bondJusqua = now() + 10;
        if (libre < c.rayon() - 0.6) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!s.e.isValid() || s.vie <= 0) return;
                mobs.ia().entraver(s, etourdi * 50L, false, false);
                Location ou = s.e.getLocation();
                ou.getWorld().playSound(ou, Sound.BLOCK_ANVIL_LAND, 1f, 0.6f);
                ou.getWorld().spawnParticle(Particle.BLOCK, ou.clone().add(dir.clone().multiply(1.2)).add(0, 1, 0), 40, 0.5, 0.6, 0.5, 0,
                    ou.clone().add(dir.clone().multiply(1.5)).add(0, 1, 0).getBlock().getBlockData());
                jouer(s, "etourdi", false);
                mobs.ia().noter(s, "contre_paroi", 1);
                mobs.ia().annoncer(s, "<gold>Le Passeur percute la paroi : il est étourdi !");
            }, 8);
        }
    }

    /** Fragment d'âme : la Vie maximale du joueur touché baisse un temps (le plugin du combat lit {@link #fragments}). */
    void touche(Mobs.Suivi s, Competence c, Player p) {
        Etat e = etat(s);
        e.derniereTouche = now();
        e.toucheAttaque = c.id();
        Speciaux.Fragment f = c.sp().fragment();
        if (f == null) return;
        long now = System.currentTimeMillis();
        List<double[]> l = fragments.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        l.removeIf(x -> x[0] <= now);
        if (l.size() >= f.cumul()) l.remove(0);
        l.add(new double[]{now + f.duree() * 1000L, f.vieMax()});
        p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1.2, 0), 12, 0.3, 0.4, 0.3, 0.02);
        p.sendActionBar(Messages.mini("<dark_aqua>Un fragment de ton âme reste accroché à la faux (" + l.size() + "/" + f.cumul() + ")."));
    }

    /** Total des fragments d'âme du joueur (−0,1 par fragment), 0 sans fragment. */
    double fragments(Player p) {
        List<double[]> l = fragments.get(p.getUniqueId());
        if (l == null) return 0;
        long now = System.currentTimeMillis();
        l.removeIf(x -> x[0] <= now);
        double t = 0;
        for (double[] x : l) t += x[1];
        return Math.max(-0.9, t);
    }

    /** Fin de vie ({@code fin-de-vie}) : il joue cette attaque sur place (l'âme enragée explose), puis disparaît. */
    void finDeVie(Mobs.Suivi s) {
        Speciaux.Mob sp = sp(s);
        if (sp.finDeVie() == null || !(s.e instanceof Mob mob)) return;
        for (Competence c : s.d.combat().competences()) {
            if (!c.id().equals(sp.finDeVie())) continue;
            mobs.ia().exploser(s, mob, c);
            return;
        }
    }

    // ================================================================== Sauteur, Attentive, goule, feu-follet

    private void sceau(Mobs.Suivi s, Etat e, Speciaux.Sceau sc, long t) {
        if (t < e.sceauJusqua) {
            if (t % 20 == 0) s.e.getWorld().spawnParticle(Particle.ENCHANT, s.e.getLocation().add(0, 1.8, 0), 8, 0.3, 0.2, 0.3, 0.2);
            return;
        }
        Location ml = s.e.getLocation();
        for (Player p : ml.getWorld().getPlayers()) {
            if (!IACombat.valide(p, s.e)) continue;
            boolean ok = p.isSneaking() && p.getLocation().distanceSquared(ml) <= 2.5 * 2.5 && dans_le_dos(s, p);
            if (!ok) { e.sceau.remove(p.getUniqueId()); continue; }
            int n = e.sceau.merge(p.getUniqueId(), 2, Integer::sum);
            if (n % 6 == 0) p.sendActionBar(Messages.mini("<gold>Tu recolles le sceau… " + Math.min(100, n * 100 / sc.dos()) + " %"));
            if (n >= sc.dos()) {
                e.sceau.clear();
                e.sceauJusqua = t + sc.fige();
                e.figeJusqua = t + sc.fige();
                mobs.ia().entraver(s, sc.fige() * 50L, false, false);
                if (s.ia != null) { s.ia.menace.clear(); s.ia.cible = null; }
                ml.getWorld().playSound(ml, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 0.8f);
                p.sendActionBar(Messages.mini("<green>Sceau recollé : il est figé."));
                mobs.ia().noter(s, "sceau_recolle", 1);
                evenement(p, s, sc.evenement());
                return;
            }
        }
    }

    private void regardSubi(Mobs.Suivi s, Etat e, Speciaux.RegardSubi rg, long t) {
        Location centre = s.e.getLocation().add(0, s.e.getHeight() * 0.6, 0);
        boolean regardee = false;
        for (Player p : centre.getWorld().getPlayers()) {
            if (!IACombat.valide(p, s.e) || p.getLocation().distanceSquared(centre) > 32 * 32) { e.regardes.remove(p.getUniqueId()); continue; }
            Location oeil = p.getEyeLocation();
            Vector v = centre.toVector().subtract(oeil.toVector());
            double d = v.length();
            boolean voit = d > 0.1 && v.multiply(1 / d).dot(oeil.getDirection()) > Math.cos(Math.toRadians(d < 4 ? 35 : 22)) && p.hasLineOfSight(s.e);
            if (!voit) { e.regardes.remove(p.getUniqueId()); continue; }
            regardee = true;
            int n = e.regardes.merge(p.getUniqueId(), 2, Integer::sum);
            if (rg.aveugleApres() > 0 && n >= rg.aveugleApres()) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, rg.aveuglement(), 0, false, false));
                p.sendActionBar(Messages.mini("<dark_purple>Les trous de son visage s'allument : tu ne vois plus rien."));
                e.regardes.put(p.getUniqueId(), -rg.aveuglement());
            }
        }
        e.regardee = regardee;
        if (rg.solide() && s.ia != null && regardee && s.ia.engage) {
            // regardée : solide et immobile
            s.ia.immobile = Math.max(s.ia.immobile, t + 3);
        }
        if (!regardee && t % 6 == 0) s.e.getWorld().spawnParticle(Particle.SMOKE, s.e.getLocation().add(0, 1.2, 0), 4, 0.25, 0.5, 0.25, 0.005);
    }

    private void manger(Mobs.Suivi s, Etat e, Speciaux.Manger ma, long t) {
        if (!(s.e instanceof Mob mob)) return;
        if (e.corps != null && t - e.corps.tic() > ma.corps()) { arreterRepas(s, e); return; }
        if (e.corps == null) {
            if (t % 10 != 0 || s.vie >= s.vieMax || t - e.dernierCoup < 40 || (s.ia != null && s.ia.enCours != null)) return;
            Corps meilleur = null;
            double best = ma.rayon() * ma.rayon();
            for (Corps c : corps) {
                if (t - c.tic() > ma.corps() || !c.ou().getWorld().equals(mob.getWorld())) continue;
                double d = c.ou().distanceSquared(mob.getLocation());
                if (d < best) { best = d; meilleur = c; }
            }
            if (meilleur == null) return;
            e.corps = meilleur;
            return;
        }
        Location ou = e.corps.ou();
        if (mob.getLocation().distanceSquared(ou) > 1.6 * 1.6) {
            if (t % 10 == 0) mob.getPathfinder().moveTo(ou, 1.1);
            return;
        }
        mob.getPathfinder().stopPathfinding();
        if (!e.mange) {
            e.mange = true;
            jouer(s, "manger", true);
            Bukkit.getPluginManager().callEvent(new VoraceMobActionEvent(s.e, s.d.id(), "combat", "manger"));
        }
        if (t % 5 == 0) {
            mobs.combats().soigner(s, s.vieMax * ma.soin() / 4);
            mob.getWorld().spawnParticle(Particle.BLOCK, ou.clone().add(0, 0.3, 0), 4, 0.2, 0.1, 0.2, 0, Material.BONE_BLOCK.createBlockData());
            if (t % 20 == 0) mob.getWorld().playSound(ou, Sound.ENTITY_GENERIC_EAT, 0.8f, 0.7f);
        }
        if (s.vie >= s.vieMax) arreterRepas(s, e);
    }

    private void arreterRepas(Mobs.Suivi s, Etat e) {
        boolean mangeait = e.mange;
        e.mange = false;
        e.corps = null;
        Modeles.Instance m = modele(s);
        String n = s.d.animation("manger");
        if (m != null && n != null && n.equals(m.animation())) m.arreterAnimation();
        if (mangeait) Bukkit.getPluginManager().callEvent(new VoraceMobActionEvent(s.e, s.d.id(), "manger", "combat"));
    }

    /** Feu-follet : il fuit vers le cul-de-sac le plus proche (point de zone), puis y reste pour appeler. */
    private void refuge(Mobs.Suivi s, Etat e, long t) {
        if (!(s.e instanceof Mob mob) || e.auRefuge) return;
        Mecaniques.Fuite f = s.d.combat().meca().fuite();
        if (!e.refugeCherche) {
            e.refugeCherche = true;
            e.refuge = mobs.endroitProche(mob.getLocation(), f.vers(), Math.max(f.rayon(), 30), f.blocs());
        }
        if (s.ia.enCours != null) return;
        Location l = mob.getLocation();
        if (e.refuge == null || l.distanceSquared(e.refuge) < 2.5 * 2.5) {
            e.auRefuge = true;
            mob.getPathfinder().stopPathfinding();
            return;
        }
        if (t % 10 == 0) mob.getPathfinder().moveTo(e.refuge, 1.4 * s.d.combat().ia().vitesse());
        Modeles.Instance m = modele(s);
        String an = s.d.animation("fuite");
        if (a(m, an) && !an.equals(m.animation())) m.jouerParDessus(an, true);
    }

    // ================================================================== Glaneur

    @EventHandler(priority = EventPriority.HIGHEST)
    public void mortJoueur(PlayerDeathEvent ev) {
        Player p = ev.getEntity();
        if (ev.getKeepInventory() || ev.getDrops().isEmpty()) return;
        boolean glaneur = false;
        for (Mobs.Suivi s : mobs.suivis()) {
            if (sp(s).sac() != null && s.e.isValid() && s.e.getWorld().equals(p.getWorld()) && s.e.getLocation().distanceSquared(p.getLocation()) < 256 * 256) { glaneur = true; break; }
        }
        if (!glaneur) return;
        List<ItemStack> objets = new ArrayList<>();
        for (ItemStack o : ev.getDrops()) if (o != null && o.getType() != Material.AIR) objets.add(o.clone());
        if (objets.isEmpty()) return;
        ev.getDrops().clear();
        Location ou = p.getLocation();
        Location sol = Mobs.sol(ou.getWorld(), ou.getX(), ou.getY(), ou.getZ(), false);
        Sac sac = new Sac(p.getUniqueId(), objets, sol != null ? sol : ou, now());
        poserSac(sac);
        sacsAuSol.add(sac);
    }

    private void poserSac(Sac sac) {
        World w = sac.ou.getWorld();
        sac.affichage = w.spawn(sac.ou.clone().add(0, 0.3, 0), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.BUNDLE));
            d.setPersistent(false);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()));
        });
        sac.prise = w.spawn(sac.ou.clone(), Interaction.class, i -> {
            i.setInteractionWidth(0.8f);
            i.setInteractionHeight(0.6f);
            i.setPersistent(false);
        });
    }

    private void glaner(Mobs.Suivi s, Etat e, Speciaux.Sac sa, long t) {
        if (!(s.e instanceof Mob mob) || (s.ia != null && s.ia.engage)) return;
        if (e.sacVise != null && !sacsAuSol.contains(e.sacVise)) e.sacVise = null;
        if (e.sacVise == null && e.sacs.size() < sa.sacsMax()) {
            double best = 200 * 200;
            for (Sac x : sacsAuSol) {
                if (t - x.tic < sa.ramasseApres() || !x.ou.getWorld().equals(mob.getWorld())) continue;
                double d = x.ou.distanceSquared(mob.getLocation());
                if (d < best) { best = d; e.sacVise = x; }
            }
        }
        if (e.sacVise == null) return;
        if (mob.getLocation().distanceSquared(e.sacVise.ou) > 2.2 * 2.2) {
            mob.getPathfinder().moveTo(e.sacVise.ou, 1.0);
            return;
        }
        Sac x = e.sacVise;
        e.sacVise = null;
        sacsAuSol.remove(x);
        if (x.affichage != null) x.affichage.remove();
        if (x.prise != null) x.prise.remove();
        e.sacs.add(x);
        mob.getWorld().playSound(mob.getLocation(), Sound.ITEM_BUNDLE_INSERT, 1f, 0.7f);
        jouer(s, "ramasser", false);
        Player p = Bukkit.getPlayer(x.proprietaire);
        if (p != null) p.sendMessage(Messages.mini("<gray>Le Glaneur a ramassé ton sac. Le tuer te le rend ; il accepte aussi un troc."));
    }

    /** Rend un sac à son propriétaire (en ligne : dans son sac, le reste à ses pieds ; sinon à son arrivée). */
    private void rendreSac(Sac x) {
        Player p = Bukkit.getPlayer(x.proprietaire);
        if (p == null) {
            aRendre.computeIfAbsent(x.proprietaire, k -> new ArrayList<>()).addAll(x.objets);
            sauverSacs();
            return;
        }
        donner(p, x.objets);
        p.sendMessage(Messages.mini("<green>Ton sac t'est rendu."));
    }

    private static void donner(Player p, List<ItemStack> objets) {
        for (ItemStack o : objets) {
            for (ItemStack reste : p.getInventory().addItem(o.clone()).values()) p.getWorld().dropItemNaturally(p.getLocation(), reste);
        }
    }

    @EventHandler
    public void arrivee(PlayerJoinEvent ev) {
        List<ItemStack> l = aRendre.remove(ev.getPlayer().getUniqueId());
        if (l == null) return;
        sauverSacs();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!ev.getPlayer().isOnline()) { aRendre.put(ev.getPlayer().getUniqueId(), l); sauverSacs(); return; }
            donner(ev.getPlayer(), l);
            ev.getPlayer().sendMessage(Messages.mini("<green>Le Glaneur est tombé pendant ton absence : ton sac t'est rendu."));
        }, 40);
    }

    private void lireSacs() {
        if (!fichierSacs.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(fichierSacs);
        for (String k : y.getKeys(false)) {
            try {
                List<ItemStack> l = new ArrayList<>();
                for (Object o : y.getList(k, List.of())) if (o instanceof ItemStack i) l.add(i);
                if (!l.isEmpty()) aRendre.put(UUID.fromString(k), l);
            } catch (IllegalArgumentException ignore) { }
        }
    }

    private void sauverSacs() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> x : aRendre.entrySet()) y.set(x.getKey().toString(), x.getValue());
        try {
            fichierSacs.getParentFile().mkdirs();
            y.save(fichierSacs);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("sacs-a-rendre.yml non sauvé : " + ex.getMessage());
        }
    }

    // ================================================================== interactions (sac, ballot, troc, offrande)

    @EventHandler(ignoreCancelled = true)
    public void interaction(PlayerInteractEntityEvent ev) {
        if (ev.getHand() != EquipmentSlot.HAND) return;
        Player p = ev.getPlayer();
        Entity x = ev.getRightClicked();
        // un sac au sol : son propriétaire le reprend
        for (Sac sac : new ArrayList<>(sacsAuSol)) {
            if (sac.prise == null || !sac.prise.equals(x)) continue;
            ev.setCancelled(true);
            if (!sac.proprietaire.equals(p.getUniqueId())) { p.sendActionBar(Messages.mini("<gray>Ce n'est pas ton sac.")); return; }
            sacsAuSol.remove(sac);
            if (sac.affichage != null) sac.affichage.remove();
            sac.prise.remove();
            donner(p, sac.objets);
            p.sendActionBar(Messages.mini("<green>Tu reprends ton sac."));
            return;
        }
        // le ballot de la Porte-Fardeau
        for (Mobs.Suivi s : mobs.suivis()) {
            Etat e = etats.get(s.e.getUniqueId());
            if (e != null && e.ballot.contains(x)) { ev.setCancelled(true); prendreBallot(s, e, p); return; }
        }
        Mobs.Suivi s = mobs.suivi(x);
        if (s == null) return;
        Speciaux.Mob sp = sp(s);
        // troc avec le Glaneur : l'objet en main contre son sac
        if (sp.sac() != null && sp.sac().troc() && (s.ia == null || !s.ia.engage)) {
            Etat e = etat(s);
            Sac sien = null;
            for (Sac sac : e.sacs) if (sac.proprietaire.equals(p.getUniqueId())) { sien = sac; break; }
            if (sien == null) { p.sendActionBar(Messages.mini("<gray>Le Glaneur n'a rien à toi.")); return; }
            ItemStack main = p.getInventory().getItemInMainHand();
            if (main.getType() == Material.AIR) { p.sendActionBar(Messages.mini("<gray>Le Glaneur attend quelque chose en échange (l'objet en main).")); return; }
            ev.setCancelled(true);
            p.getInventory().setItemInMainHand(null);
            e.sacs.remove(sien);
            donner(p, sien.objets);
            s.e.getWorld().playSound(s.e.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 0.6f);
            p.sendMessage(Messages.mini("<gold>Le Glaneur prend ton objet et te rend ton sac."));
            evenement(p, s, sp.sac().evenement());
            return;
        }
        // l'offrande de la Lame-orpheline : clic droit pour la prendre
        if (sp.offrande() != null && (s.ia == null || !s.ia.engage)) {
            Etat e = etat(s);
            ev.setCancelled(true);
            if (e.offreA == null || !e.offreA.equals(p.getUniqueId())) { e.offreA = p.getUniqueId(); e.offreProgres = 0; }
            e.offreProgres = Math.max(e.offreProgres, 1);
        }
    }

    // ================================================================== Lame-orpheline

    private void offrir(Mobs.Suivi s, Etat e, Speciaux.Offrande of, long t) {
        if (e.accepte || (s.ia != null && s.ia.engage)) return;
        Location ml = s.e.getLocation();
        Player proche = null;
        double best = 4 * 4;
        for (Player p : ml.getWorld().getPlayers()) {
            if (!IACombat.valide(p, s.e)) continue;
            double d = p.getLocation().distanceSquared(ml);
            if (d < best) { best = d; proche = p; }
        }
        // refus : un joueur à qui elle l'a tendue s'en va
        for (Iterator<UUID> it = e.proposes.iterator(); it.hasNext(); ) {
            Player p = Bukkit.getPlayer(it.next());
            if (p == null || !p.getWorld().equals(ml.getWorld()) || p.getLocation().distanceSquared(ml) > 7 * 7) {
                it.remove();
                if (p != null && (e.offreA == null || !e.offreA.equals(p.getUniqueId()) || e.offreProgres < of.interaction())) {
                    e.refus++;
                    p.sendActionBar(Messages.mini("<gray>La lame se redresse lentement (" + e.refus + "/" + of.refusMax() + ")."));
                    if (e.refus >= of.refusMax()) {
                        e.refus = 0;
                        mobs.ia().ajouterMenace(s, p, 20);
                        return;
                    }
                }
            }
        }
        if (proche == null) return;
        if (e.proposes.add(proche.getUniqueId())) {
            jouer(s, "offrande", true);
            proche.sendActionBar(Messages.mini("<gold>La lame se couche à l'horizontale, la poignée vers toi. <gray>(clic droit, puis reste près d'elle 3 s)"));
        }
        if (s.e instanceof Mob mob) IACombat.regarder(mob, proche.getLocation());
        if (e.offreA != null && e.offreA.equals(proche.getUniqueId()) && e.offreProgres > 0) {
            e.offreProgres += 5;
            if (e.offreProgres % 20 == 1) proche.sendActionBar(Messages.mini("<gold>Tu prends la poignée… " + Math.min(100, e.offreProgres * 100 / of.interaction()) + " %"));
            if (e.offreProgres >= of.interaction()) accepter(s, e, of, proche);
        }
    }

    private void accepter(Mobs.Suivi s, Etat e, Speciaux.Offrande of, Player p) {
        e.accepte = true;
        if (of.objet() != null) {
            ItemStack o = mobs.butin().objet(of.objet(), 1);
            if (o != null) donner(p, List.of(o));
        }
        s.e.getWorld().playSound(s.e.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 1f);
        p.sendMessage(Messages.mini("<gold>La Lame-orpheline t'a choisi."));
        evenement(p, s, of.evenement());
        mobs.marquerSansButin(s);
        mobs.retirer(s.e);
    }

    /** À 1 de Vie, la Lame-orpheline se rend : le combat s'arrête, elle retend sa poignée (aucune XP). */
    private void rendre(Mobs.Suivi s, Etat e, Speciaux.Mob sp, Player source) {
        double vie = s.vie;
        mobs.ia().rentrer(s);
        if (s.ia != null) s.ia.rentre = false;
        mobs.combats().fixerVie(s, Math.max(1, Math.max(vie, s.vieMax * 0.2)));
        e.proposes.clear();
        e.refus = 0;
        s.e.getWorld().playSound(s.e.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.6f, 1.6f);
        if (source != null) source.sendMessage(Messages.mini("<gold>La Lame-orpheline se rend et te tend de nouveau sa poignée."));
        evenement(source, s, sp.invincible().evenement());
    }

    // ================================================================== Porte-Fardeau

    private void poserBallot(Mobs.Suivi s, Etat e, Speciaux.Leurre le) {
        Location maison = new Location(s.e.getWorld(), s.mx, s.my, s.mz);
        Location sol = Mobs.sol(maison.getWorld(), maison.getX(), maison.getY(), maison.getZ(), false);
        e.ballotOu = sol != null ? sol : maison;
        ItemStack objet = mobs.butin().objet(le.objet().contains(":") ? le.objet() : "vorace:e1_" + le.objet(), 1);
        if (objet == null) objet = new ItemStack(Material.BROWN_WOOL);
        ItemStack o = objet;
        World w = e.ballotOu.getWorld();
        e.ballot.add(w.spawn(e.ballotOu.clone().add(0, 0.25, 0), ItemDisplay.class, d -> {
            d.setItemStack(o);
            d.setPersistent(false);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.6f, 0.45f, 0.6f), new Quaternionf()));
        }));
        e.ballot.add(w.spawn(e.ballotOu.clone(), Interaction.class, i -> { i.setInteractionWidth(0.9f); i.setInteractionHeight(0.6f); i.setPersistent(false); }));
        // elle attend sous l'eau, à quelques blocs du ballot
        Location eau = eauProche(e.ballotOu, 5);
        if (eau != null) s.e.teleport(eau);
        e.dormant = true;
        if (s.e instanceof LivingEntity l) l.setInvisible(true);
    }

    private static Location eauProche(Location l, int r) {
        World w = l.getWorld();
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -3; dy <= 1; dy++) {
            Block b = w.getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
            if (b.getType() != Material.WATER || b.getRelative(0, -1, 0).getType() == Material.WATER && b.getRelative(0, -2, 0).getType() == Material.WATER) continue;
            double d = dx * dx + dz * dz;
            if (d >= 4 && d < bd) { bd = d; best = b.getLocation().add(0.5, 0, 0.5); }
        }
        return best;
    }

    private void prendreBallot(Mobs.Suivi s, Etat e, Player p) {
        Speciaux.Leurre le = sp(s).leurre();
        for (Entity x : e.ballot) x.remove();
        e.ballot.clear();
        reveiller(s, e);
        if (le.immobilisation() > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, le.immobilisation(), 9, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, le.immobilisation(), 250, false, false));
        }
        p.sendActionBar(Messages.mini("<dark_aqua>Le linge est lourd, mouillé… et quelque chose tire de l'autre côté."));
        mobs.ia().ajouterMenace(s, p, 50);
        evenement(p, s, le.evenement());
    }

    private void reveiller(Mobs.Suivi s, Etat e) {
        if (!e.dormant) return;
        e.dormant = false;
        if (s.e instanceof LivingEntity l) l.setInvisible(false);
        if (e.ballotOu != null) {
            Location sortie = e.ballotOu.clone().add(hasard.nextDouble() * 2 - 1, 0, hasard.nextDouble() * 2 - 1);
            Location sol = Mobs.sol(sortie.getWorld(), sortie.getX(), sortie.getY(), sortie.getZ(), false);
            s.e.teleport(sol != null ? sol : e.ballotOu);
        }
        jouer(s, "apparition", false);
        s.e.getWorld().playSound(s.e.getLocation(), Sound.ENTITY_DROWNED_AMBIENT_WATER, 1.2f, 0.6f);
        s.e.getWorld().spawnParticle(Particle.SPLASH, s.e.getLocation().add(0, 1, 0), 40, 0.6, 0.8, 0.6, 0.2);
    }

    /** Frappé (ou ballot touché) : la Porte-Fardeau sort de l'eau. */
    void frappe(Mobs.Suivi s) {
        Etat e = etats.get(s.e.getUniqueId());
        if (e != null && e.dormant) {
            for (Entity x : e.ballot) x.remove();
            e.ballot.clear();
            reveiller(s, e);
        }
    }

    // ================================================================== Passeur : tête-lanterne et lanterne posée

    private Satellite satellite(Mobs.Suivi s, String quoi, Location ou, Material objet, float taille, double vie) {
        World w = ou.getWorld();
        ItemDisplay d = w.spawn(ou, ItemDisplay.class, x -> {
            x.setItemStack(new ItemStack(objet));
            x.setPersistent(false);
            x.setBrightness(new Display.Brightness(15, 15));
            x.setGlowing(true);
            x.setGlowColorOverride(Color.fromRGB(0x4CFF8A));
            x.setTeleportDuration(2);
            x.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(taille, taille, taille), new Quaternionf()));
        });
        Interaction i = w.spawn(ou.clone().add(0, -taille / 2, 0), Interaction.class, x -> {
            x.setInteractionWidth(taille * 0.9f);
            x.setInteractionHeight(taille);
            x.setResponsive(true);
            x.setPersistent(false);
        });
        Satellite sa = new Satellite(quoi, d, i, vie);
        satellites.put(i.getUniqueId(), s.e.getUniqueId());
        return sa;
    }

    private void retirerSatellite(Satellite sa) {
        if (sa == null) return;
        if (sa.prise != null) satellites.remove(sa.prise.getUniqueId());
        sa.retirer();
    }

    private void tete(Mobs.Suivi s, Etat e, Speciaux.TeteLanterne tl, long t) {
        if (s.ia == null || !s.ia.engage || e.lanternePosee) {
            if (e.tete != null && e.lanternePosee) { retirerSatellite(e.tete); e.tete = null; }
            return;
        }
        if (e.tete == null && t >= e.reformeA) {
            Location ou = s.e.getLocation().add(0, s.e.getHeight() + 1.2, 0);
            e.tete = satellite(s, "tete", ou, Material.SOUL_LANTERN, 0.9f, Math.max(1, s.vieMax * tl.vie()));
        }
        if (e.tete == null || !e.tete.valide()) return;
        if (t % 2 == 0) {
            double a = t * 0.06;
            Location ou = s.e.getLocation().add(Math.cos(a) * 1.4, s.e.getHeight() + 1.0 + Math.sin(t * 0.1) * 0.2, Math.sin(a) * 1.4);
            e.tete.affichage.teleport(ou);
            e.tete.prise.teleport(ou.clone().add(0, -0.45, 0));
            if (t % 6 == 0) ou.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, ou, 2, 0.1, 0.1, 0.1, 0.01);
        }
    }

    private void lanterne(Mobs.Suivi s, Etat e, Speciaux.LanternePosee lp, long t) {
        if (s.ia == null || !s.ia.engage || e.lanternePosee || s.ia.phase < lp.phase()) return;
        e.lanternePosee = true;
        Location ou = s.e.getLocation().add(s.e.getLocation().getDirection().setY(0).normalize().multiply(1.2)).add(0, 0.5, 0);
        Material m = Material.matchMaterial(lp.objet() == null ? "lantern" : lp.objet());
        e.lanterne = satellite(s, "lanterne", ou, m == null || !m.isItem() ? Material.SOUL_LANTERN : m, 1.1f, Math.max(1, s.vieMax * lp.vie()));
        ou.getWorld().playSound(ou, Sound.BLOCK_LANTERN_PLACE, 1.2f, 0.6f);
    }

    /** Un joueur frappe une partie détachée (tête-lanterne, lanterne) : elle prend son coup moyen sur ce boss. */
    @EventHandler(ignoreCancelled = true)
    public void frappeSatellite(io.papermc.paper.event.player.PrePlayerAttackEntityEvent ev) {
        UUID boss = satellites.get(ev.getAttacked().getUniqueId());
        if (boss == null) return;
        ev.setCancelled(true);
        Player p = ev.getPlayer();
        Mobs.Suivi s = mobs.suivi(Bukkit.getEntity(boss));
        if (s == null || s.vie <= 0) return;
        Etat e = etat(s);
        Satellite sa = e.tete != null && e.tete.prise != null && e.tete.prise.equals(ev.getAttacked()) ? e.tete
            : e.lanterne != null && e.lanterne.prise != null && e.lanterne.prise.equals(ev.getAttacked()) ? e.lanterne : null;
        if (sa == null) return;
        long t = now();
        String cle = "sat:" + p.getUniqueId();
        Integer dernier = e.coupsParties.get(cle);
        if (dernier != null && t - dernier < 10) return;   // un coup toutes les 0,5 s
        e.coupsParties.put(cle, (int) t);
        double[] mo = e.moyennes.get(p.getUniqueId());
        double coup = mo != null && mo[1] > 0 ? mo[0] / mo[1] : sa.vieMax / 25;
        sa.vie -= coup;
        Location ou = sa.affichage.getLocation();
        ou.getWorld().spawnParticle(Particle.SOUL, ou, 6, 0.2, 0.2, 0.2, 0.02);
        ou.getWorld().playSound(ou, Sound.BLOCK_GLASS_HIT, 1f, 1.2f);
        mobs.ia().ajouterMenace(s, p, coup);
        p.sendActionBar(Messages.mini("<green>" + (sa.quoi.equals("tete") ? "Tête-lanterne" : "Lanterne") + " : "
            + Math.max(0, Math.round(sa.vie)) + " / " + Math.round(sa.vieMax)));
        if (sa.vie > 0) return;
        retirerSatellite(sa);
        if (sa.quoi.equals("tete")) {
            Speciaux.TeteLanterne tl = sp(s).teteLanterne();
            e.tete = null;
            e.aveugleJusqua = t + tl.aveugle();
            e.reformeA = t + tl.aveugle() + tl.reforme();
            ou.getWorld().playSound(ou, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.7f);
            mobs.ia().annoncer(s, "<green>La tête-lanterne éclate : le Passeur frappe à l'aveugle !");
            mobs.ia().noter(s, "tete_detruite", 1);
        } else {
            // la lanterne se fêle : le Passeur s'effondre
            e.lanterne = null;
            ou.getWorld().playSound(ou, Sound.BLOCK_GLASS_BREAK, 1.4f, 0.5f);
            mobs.ia().noter(s, "lanterne_felee", 1);
            achever(s, p);
        }
    }

    private boolean contourner;

    /** Tue le mob (lanterne fêlée) : la Vie part d'un coup, au nom de {@code p}. */
    private void achever(Mobs.Suivi s, Player p) {
        if (!(s.e instanceof LivingEntity le) || !le.isValid()) return;
        contourner = true;
        try {
            double jeu = mobs.combats().infliger(s, s.vie + 1, p);
            le.damage(Math.max(jeu, le.getHealth() + 1));
        } finally {
            contourner = false;
        }
    }

    /** Vrai pendant un coup qui ne passe pas par le bestiaire (lanterne fêlée). */
    boolean contourne() { return contourner; }

    // ================================================================== Armure veuve

    private void recomposer(Mobs.Suivi s, Etat e, Speciaux.Mob sp, double seuil) {
        mobs.combats().fixerVie(s, seuil * s.vieMax);
        e.forme++;
        e.formeSuivante = sp.formes().get(Math.min(sp.formes().size() - 1, e.forme)) * s.vieMax;
        e.accumule = 0;
        e.pieceCassee = false;
        e.recomposeJusqua = now() + sp.recomposition().duree();
        mobs.ia().entraver(s, sp.recomposition().duree() * 50L, false, false);
        jouer(s, "se_recomposer", false);
        s.e.getWorld().playSound(s.e.getLocation(), Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.2f, 0.5f);
    }

    private void casserPiece(Mobs.Suivi s, Etat e, Speciaux.Recomposition rc) {
        e.pieceCassee = true;
        mobs.combats().fixerVie(s, Math.max(1, s.vie - rc.perteVie() * e.formeSuivante));
        if (s.ia != null) s.ia.bonus *= 1 - rc.perteDegats();
        mobs.ia().noter(s, "par_piece_cassee", 1);
        Location l = s.e.getLocation().add(0, 1.2, 0);
        l.getWorld().playSound(l, Sound.ENTITY_ITEM_BREAK, 1.4f, 0.6f);
        l.getWorld().spawnParticle(Particle.BLOCK, l, 40, 0.5, 0.6, 0.5, 0, Material.IRON_BLOCK.createBlockData());
        mobs.ia().annoncer(s, "<gold>Une pièce de l'armure se brise : elle se relèvera plus faible.");
    }

    private void finRecomposition(Mobs.Suivi s, Etat e, Speciaux.Mob sp) {
        e.recomposeJusqua = 0;
    }

    // ================================================================== rotation lente

    private void tourner(Mobs.Suivi s, Etat e, double degresParSeconde) {
        Location l = s.e.getLocation();
        if (Float.isNaN(e.yaw)) { e.yaw = l.getYaw(); return; }
        float voulu = l.getYaw();
        float d = ((voulu - e.yaw) % 360 + 540) % 360 - 180;
        float max = (float) (degresParSeconde / 20);
        if (Math.abs(d) > max) {
            e.yaw += Math.signum(d) * max;
            s.e.setRotation(e.yaw, l.getPitch());
            if (s.e instanceof LivingEntity le) { try { le.setBodyYaw(e.yaw); } catch (Throwable ignore) { } }
        } else e.yaw = voulu;
    }

    // ================================================================== entités de quête

    private void ticQuete(Mobs.Suivi s, Etat e, Speciaux.Quete q, long t) {
        if (q.dureeDeVie() > 0 && t - s.ne >= q.dureeDeVie()) { mobs.retirer(s.e); return; }
        if (q.ronde() != null && !e.rondeFinie && t >= e.rondeArret) ronde(s, e, q, t);
        if (q.regard() != null && t % 4 == 0) regarder(s, e, q, t);
    }

    private void ronde(Mobs.Suivi s, Etat e, Speciaux.Quete q, long t) {
        Mecaniques.Ronde r = q.ronde();
        double[] p = r.points().get(Math.min(e.rondeIndex, r.points().size() - 1));
        Location l = s.e.getLocation();
        double tx = p[0], tz = p[p.length - 1];
        double dx = tx - l.getX(), dz = tz - l.getZ(), d = Math.hypot(dx, dz);
        double pas = r.vitesse() / 20.0;
        if (d <= pas + 0.05) {
            int[] n = r.suivant(e.rondeIndex, e.rondeSens);
            boolean fin = !r.boucle() && (e.rondeIndex == r.points().size() - 1 && e.rondeSens > 0);
            if (fin) {
                e.rondeFinie = true;
                if (q.disparaitEnFin()) mobs.retirer(s.e);
                return;
            }
            if (r.bout(e.rondeIndex) && r.arret() > 0) e.rondeArret = t + r.arret();
            e.rondeIndex = n[0];
            e.rondeSens = n[1];
            return;
        }
        double nx = l.getX() + dx / d * pas, nz = l.getZ() + dz / d * pas;
        double y = l.getY();
        double haut = s.d.volant() ? (s.d.altMin() + s.d.altMax()) / 2 : 0;   // volante : elle flotte au-dessus du sol
        Location sol = Mobs.sol(l.getWorld(), nx, l.getY() - haut + 1, nz, false);
        if (sol != null && Math.abs(sol.getY() + haut - l.getY()) < 3.5) y = sol.getY() + haut;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        s.e.teleport(new Location(l.getWorld(), nx, y, nz, yaw, 0));
        Modeles.Instance m = modele(s);
        String an = s.d.animation("marche");
        if (an == null) an = "marche";
        if (a(m, an) && !an.equals(m.animation())) m.jouerParDessus(an, true);
    }

    /** L'entité voit-elle ce joueur ? Cône devant elle, portée (accroupi, lanterne en main), ligne de vue. */
    private boolean voit(Mobs.Suivi s, Speciaux.Regard rg, Player p) {
        if (!IACombat.valide(p, s.e) || !p.canSee(s.e)) return false;
        Location ml = s.e.getLocation(), pl = p.getLocation();
        double d = ml.distance(pl);
        double portee = Bestiaire.lumiere(p) > 0 ? rg.porteeLanterne() : p.isSneaking() ? rg.porteeAccroupi() : rg.portee();
        if (d > portee) return false;
        if (Mecaniques.angle(ml.getYaw(), pl.getX() - ml.getX(), pl.getZ() - ml.getZ()) > rg.angle() / 2) return false;
        return !rg.ligneDeVue() || !(s.e instanceof LivingEntity le) || le.hasLineOfSight(p);
    }

    private void regarder(Mobs.Suivi s, Etat e, Speciaux.Quete q, long t) {
        for (Player p : s.e.getWorld().getPlayers()) {
            Long fin = e.vus.get(p.getUniqueId());
            if (fin != null && t < fin) continue;
            if (!voit(s, q.regard(), p)) continue;
            Speciaux.Vu vu = q.vu();
            e.vus.put(p.getUniqueId(), t + 60 + (vu == null ? 0 : vu.signal()));
            String anim = vu != null && vu.animation() != null ? vu.animation() : s.d.animation("vu");
            if (anim != null) { Modeles.Instance m = modele(s); if (a(m, anim)) m.jouer(anim, false); }
            s.e.getWorld().playSound(s.e.getLocation(), Sound.ENTITY_GHAST_HURT, 0.9f, 0.5f);
            p.sendActionBar(Messages.mini("<dark_red>Elle t'a vu."));
            evenement(p, s, vu == null ? "vu" : vu.evenement());
            if (vu != null && vu.repousse() > 0) {
                Player cible = p;
                Bukkit.getScheduler().runTaskLater(plugin, () -> repousser(s, cible, vu), Math.max(1, vu.signal()));
            }
        }
    }

    private void repousser(Mobs.Suivi s, Player p, Speciaux.Vu vu) {
        if (!p.isOnline() || !s.e.isValid()) return;
        Location l = p.getLocation();
        Location vers = vu.vers() == null ? null : mobs.endroitProche(l, vu.vers(), 200, List.of());
        Vector dir = vers != null ? vers.toVector().subtract(l.toVector()).setY(0) : l.toVector().subtract(s.e.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
        dir.normalize();
        p.setVelocity(dir.multiply(Math.min(3, vu.repousse() * 0.33)).setY(0.35));
        p.getWorld().playSound(l, Sound.ENTITY_PHANTOM_FLAP, 1f, 0.6f);
    }

    // ================================================================== mort

    /** Un mob du bestiaire meurt : corps (goules), événement, sacs rendus, lumières rallumées, parties détachées retirées. */
    void mort(Mobs.Suivi s, Player tueur) {
        if (s.d.combattant()) corps.add(new Corps(s.e.getLocation(), now()));
        Etat e = etats.remove(s.e.getUniqueId());
        Speciaux.Mob sp = sp(s);
        if (tueur != null && sp.evenementMort() != null) evenement(tueur, s, sp.evenementMort());
        if (e == null) return;
        // tuée juste après une saisie ratée (fenêtre du Guerrier) : « saisie-ratee » de la saisie
        if (tueur != null && now() <= e.rateeJusqua) {
            for (Competence c : s.d.combat().competences()) if (c.sp().evenementRatee() != null) evenement(tueur, s, c.sp().evenementRatee());
        }
        for (Sac x : e.sacs) rendreSac(x);
        e.sacs.clear();
        nettoyer(e);
    }

    void vider() {
        for (Etat e : etats.values()) {
            for (Sac x : e.sacs) rendreSac(x);
            nettoyer(e);
        }
        etats.clear();
        for (Sac x : sacsAuSol) {
            if (x.affichage != null) x.affichage.remove();
            if (x.prise != null) x.prise.remove();
            rendreSac(x);
        }
        sacsAuSol.clear();
    }

    /** État pour /vmob info : parties cassées, regard, sacs, formes... */
    String etatTexte(Mobs.Suivi s) {
        Etat e = etats.get(s.e.getUniqueId());
        if (e == null) return "";
        StringBuilder b = new StringBuilder();
        if (!e.cassees.isEmpty()) b.append(" cassees=").append(String.join(",", e.cassees));
        if (e.branchesCoupees > 0) b.append(" branches=").append(e.branchesCoupees);
        if (e.regardee) b.append(" regardee=oui");
        if (!e.sacs.isEmpty()) b.append(" sacs=").append(e.sacs.size());
        if (e.forme > 0) b.append(" forme=").append(e.forme + 1);
        if (e.dormant) b.append(" dormant=oui");
        if (e.mange) b.append(" mange=oui");
        if (e.auRefuge) b.append(" refuge=oui");
        if (e.tete != null) b.append(" tete=").append(Math.round(e.tete.vie));
        if (e.lanterne != null) b.append(" lanterne=").append(Math.round(e.lanterne.vie));
        if (now() < e.sceauJusqua) b.append(" scelle=oui");
        return b.toString();
    }
}
