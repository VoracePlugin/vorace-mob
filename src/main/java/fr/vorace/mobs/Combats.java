package fr.vorace.mobs;

import fr.vorace.Messages;
import fr.vorace.api.VoraceAPI;
import fr.vorace.modeles.ModeleBB;
import fr.vorace.modeles.Modeles;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Vie, niveau et barre des mobs de combat ({@link Combat}). La Vie est gardée par vorace-mob (avec l'entité) ; la
 * santé du jeu suit la même proportion sur 100, pour que le jeu sache quand le mob meurt et qui l'a tué. Les dégâts
 * du plugin du combat passent par {@link #infliger} ; les autres (feu, chute, coup d'un serveur sans plugin de combat)
 * sont convertis : 1 point de santé du jeu = 1 % de la Vie. Les stats suivent le niveau ({@link Combat#stat(String, int)}).
 *
 * <p>Au-dessus du mob, en combat seulement : sa <b>barre de vie</b>, faite de quatre affichages d'objets tournés vers
 * le joueur (cadre selon le rang, Vie, traîne claire des derniers dégâts, vide) qui chevauchent le mob. La Vie et la
 * traîne glissent (interpolation du jeu). Pas de nom (il est dans le bestiaire), sauf {@code combat.nom-visible: true}.
 */
final class Combats {

    /** Santé maximale du jeu d'un mob de combat (la Vie réelle est dans vorace-mob). */
    static final double SANTE = 100;
    /** Le mob reste « en combat » (barre visible) tant de millisecondes après un coup reçu ou donné. */
    static final long DUREE_COMBAT = 10_000;
    /** Barre : largeur et hauteur du cadre et de son intérieur (blocs, à l'échelle 1). */
    static final float CADRE_L = 1.2f, CADRE_H = 0.2f, INT_L = 1.07f, INT_H = 0.114f;

    private final VoraceMob plugin;
    private final Mobs mobs;
    private final NamespacedKey cleVie;
    /** mob → ses quatre affichages (cadre, Vie, traîne, vide). */
    private final Map<UUID, ItemDisplay[]> barres = new HashMap<>();
    private boolean objetsAbsents;

    Combats(VoraceMob plugin, Mobs mobs) {
        this.plugin = plugin;
        this.mobs = mobs;
        cleVie = NamespacedKey.fromString("vorace:mob_vie");
    }

    /** Clé de données où invoquer() range « vie;niveau » d'un mob qui vient d'apparaître. */
    NamespacedKey cle() { return cleVie; }

    /** Reprend (ou prépare) la Vie et le niveau d'un mob de combat chargé. */
    void charger(Mobs.Suivi s) {
        Combat c = s.d.combat();
        String v = s.e.getPersistentDataContainer().get(cleVie, PersistentDataType.STRING);
        s.niveau = c.niveauMin();
        double vie = -1;
        if (v != null) {
            String[] p = v.split(";");
            try {
                if (p.length > 0 && !p[0].isEmpty()) vie = Double.parseDouble(p[0]);
                if (p.length > 1) s.niveau = Integer.parseInt(p[1]);
            } catch (NumberFormatException ignore) { }
        }
        s.vieMax = c.vieAuNiveau(s.niveau);
        s.vie = vie < 0 ? s.vieMax : Math.max(0, Math.min(s.vieMax, vie));
        s.vieTraine = s.vie;
        s.ia = new IACombat.Etat();
        if (s.e instanceof LivingEntity le) {
            AttributeInstance max = Mobs.attribut(le, "max_health");
            if (max != null) max.setBaseValue(SANTE);
            AttributeInstance armure = Mobs.attribut(le, "armor");  // la défense du mob, c'est sa Résistance, pas l'armure du jeu
            if (armure != null) armure.setBaseValue(0);
            AttributeInstance vit = Mobs.attribut(le, "movement_speed");
            if (vit != null) s.vitesseBase = vit.getBaseValue();
            if (s.vie > 0) le.setHealth(Combat.santeDuJeu(s.vie, s.vieMax, SANTE));
            if (le.getEquipment() != null) le.getEquipment().clear();
            le.setCanPickupItems(false);
        }
        s.texteNom = null;
        afficher(s);
    }

    void sauver(Mobs.Suivi s) {
        try {
            s.e.getPersistentDataContainer().set(cleVie, PersistentDataType.STRING, String.format(Locale.ROOT, "%.2f;%d", s.vie, s.niveau));
        } catch (Throwable ignore) { }
    }

    /**
     * Retire {@code montant} de Vie au mob et renvoie les dégâts du jeu à lui faire subir pour que sa santé suive (à 0 de
     * Vie : de quoi le tuer). À appeler pendant l'événement de dégâts du coup (ses autres dégâts ne sont plus comptés).
     * {@code source} : le joueur qui frappe (menace et butin), ou null.
     */
    double infliger(Mobs.Suivi s, double montant, Player source) {
        montant = mobs.ia().garde(s, montant, source);   // garde de face, point faible (bestiaire)
        s.compteTic = Bukkit.getCurrentTick();
        s.dernierCombat = System.currentTimeMillis();
        double reel = Math.min(s.vie, Math.max(0, montant));
        if (s.vieTraine < s.vie) s.vieTraine = s.vie;
        s.traineDepuis = mobs.tics();
        s.vie = Math.max(0, s.vie - Math.max(0, montant));
        if (source != null && s.ia != null) {
            s.ia.degats.merge(source.getUniqueId(), reel, Double::sum);
            mobs.ia().ajouterMenace(s, source, reel);
            mobs.ia().coupRecu(s, source);
        }
        sauver(s);
        afficher(s);
        if (!(s.e instanceof LivingEntity le)) return 0;
        double avant = le.getHealth();
        if (s.vie <= 0) return avant + 1;
        return Math.max(0.001, avant - Combat.santeDuJeu(s.vie, s.vieMax, SANTE));
    }

    /**
     * Vie maximale selon les joueurs du combat ({@code vie-groupe}) : Vie du niveau × (1 + k × (joueurs − 1)). Quand elle
     * change, le mob garde le même pourcentage de Vie (et sa traîne). Au retour au calme, elle redevient celle d'un joueur.
     */
    void ajusterGroupe(Mobs.Suivi s) {
        Combat c = s.d.combat();
        if (c == null || s.ia == null) return;
        double max = c.vieAuNiveau(s.niveau) * c.facteurVie(s.ia.joueurs());
        if (Math.abs(max - s.vieMax) < 1e-6 || max <= 0) return;
        double ratio = s.vieMax <= 0 ? 1 : s.vie / s.vieMax, ratioT = s.vieMax <= 0 ? 1 : s.vieTraine / s.vieMax;
        s.vieMax = max;
        s.vie = Math.max(0, Math.min(max, ratio * max));
        s.vieTraine = Math.max(s.vie, Math.min(max, ratioT * max));
        sauver(s);
        if (s.e instanceof LivingEntity le && le.isValid() && s.vie > 0) le.setHealth(Combat.santeDuJeu(s.vie, s.vieMax, SANTE));
        s.barreV = s.barreT = -1;
        afficher(s);
    }

    /** Rend de la Vie au mob (soins de ses alliés, retour au calme). */
    /** Pose sa Vie à {@code vie} (mob relevé avec une part de sa Vie). */
    void fixerVie(Mobs.Suivi s, double vie) {
        if (s.vie <= 0) return;
        s.vie = Math.max(1, Math.min(s.vieMax, vie));
        s.vieTraine = s.vie;
        sauver(s);
        if (s.e instanceof LivingEntity le && le.isValid()) le.setHealth(Combat.santeDuJeu(s.vie, s.vieMax, SANTE));
        afficher(s);
    }

    void soigner(Mobs.Suivi s, double montant) {
        if (s.vie <= 0 || montant <= 0) return;
        s.vie = Math.min(s.vieMax, s.vie + montant);
        s.vieTraine = Math.max(s.vieTraine, s.vie);
        sauver(s);
        if (s.e instanceof LivingEntity le && le.isValid()) le.setHealth(Combat.santeDuJeu(s.vie, s.vieMax, SANTE));
        afficher(s);
    }

    /**
     * Dégâts qui ne viennent pas du plugin du combat (feu, chute, lave, serveur sans plugin de combat) : 1 point du jeu
     * = 1 % de la Vie. Les dégâts déjà comptés par {@link #infliger} dans le même tic sont laissés tels quels.
     */
    @SuppressWarnings("deprecation")  // modificateurs de dégâts : seule façon de retirer l'armure et les potions du jeu
    void degatsDuJeu(EntityDamageEvent e, Mobs.Suivi s) {
        if (s.compteTic == Bukkit.getCurrentTick()) return;
        Player source = null;
        if (e instanceof EntityDamageByEntityEvent ee) {
            if (ee.getDamager() instanceof Player p) source = p;
            else if (ee.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) source = p;
        }
        double montant = e.getFinalDamage() * s.vieMax / SANTE;
        double jeu = infliger(s, montant, source);
        for (EntityDamageEvent.DamageModifier m : EntityDamageEvent.DamageModifier.values()) {
            if (m != EntityDamageEvent.DamageModifier.BASE && e.isApplicable(m)) e.setDamage(m, 0);
        }
        e.setDamage(EntityDamageEvent.DamageModifier.BASE, jeu);
    }

    boolean enCombat(Mobs.Suivi s) {
        return System.currentTimeMillis() - s.dernierCombat < DUREE_COMBAT || (s.ia != null && s.ia.engage);
    }

    // ------------------------------------------------------------------ barre au-dessus du mob

    /** Taille de la barre selon le rang. */
    static float echelle(Combat.Rang r) {
        return switch (r) {
            case NORMAL -> 1f;
            case ELITE -> 1.15f;
            case ELITE_PP -> 1.35f;
            default -> 1.6f;
        };
    }

    static String cadre(Combat.Rang r) {
        return switch (r) {
            case NORMAL -> "normal";
            case ELITE -> "elite";
            case ELITE_PP -> "elite_pp";
            default -> "boss";
        };
    }

    /**
     * Les trois morceaux de l'intérieur de la barre, de gauche à droite : Vie de 0 à v, traîne de v à t, vide de t à 1.
     * Renvoie pour chacun {centre x, largeur} en blocs (échelle 1), sans recouvrement.
     */
    static float[][] morceaux(double vie, double traine, double vieMax) {
        float v = vieMax <= 0 ? 0 : (float) Math.max(0, Math.min(1, vie / vieMax));
        if (vie > 0) v = Math.max(v, 0.02f);   // encore en vie : toujours un peu de rouge
        float t = vieMax <= 0 ? 0 : (float) Math.max(v, Math.min(1, traine / vieMax));
        float g = -INT_L / 2;
        return new float[][] {
            {g + INT_L * v / 2, INT_L * v},
            {g + INT_L * (v + t) / 2, INT_L * (t - v)},
            {g + INT_L * (t + 1) / 2, INT_L * (1 - t)}};
    }

    /** Barre de vie (en combat seulement) ; nom et niveau seulement si le mob le demande. */
    void afficher(Mobs.Suivi s) {
        Combat c = s.d.combat();
        if (c == null || !s.e.isValid()) return;
        boolean modele = plugin.modeles() != null && plugin.modeles().instance(s.e) != null;
        if (modele && !s.dansEquipe) cacherNomDuJeu(s);
        if (c.nomVisible()) {
            String nom = s.d.nom() != null ? s.d.nom() : s.d.id();
            String t = c.rang().couleur() + c.rang().marque() + Messages.echapper(nom) + " <#9d968c>Nv." + s.niveau;
            if (s.e.isCustomNameVisible() == modele) s.e.setCustomNameVisible(!modele);
            if (modele) etiquette(s);
            if (!t.equals(s.texteNom)) { s.texteNom = t; s.e.customName(Messages.mini(t)); }
        } else if (s.e.customName() != null) {
            s.e.customName(null);
            s.e.setCustomNameVisible(false);
        }
        if (!c.barre() || s.vie <= 0 || !enCombat(s)) { retirerBarre(s.e.getUniqueId()); return; }
        barre(s, c);
    }

    private void barre(Mobs.Suivi s, Combat c) {
        ItemDisplay[] b = barres.get(s.e.getUniqueId());
        float k = echelle(c.rang());
        if (b == null || !valide(b)) {
            if (b != null) retirerBarre(s.e.getUniqueId());
            if (objetsAbsents) return;
            ItemStack[] objets = {objet("vmob_barre_cadre_" + cadre(c.rang())), objet("vmob_barre_vie"), objet("vmob_barre_traine"), objet("vmob_barre_vide")};
            for (ItemStack o : objets) {
                if (o != null) continue;
                objetsAbsents = true;
                plugin.getLogger().warning("Barre de vie : objets « vmob_barre_... » absents de Vorace (redémarre le serveur une fois). Pas de barre en attendant.");
                return;
            }
            Location l = s.e.getLocation();
            l.setYaw(0);
            l.setPitch(0);
            b = new ItemDisplay[4];
            for (int i = 0; i < 4; i++) {
                final ItemStack o = objets[i];
                b[i] = l.getWorld().spawn(l, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setItemStack(o);
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setViewRange(0.5f);
                    d.setShadowRadius(0);
                    d.setTransformation(new Transformation(new Vector3f(0, 0.5f, 0), new Quaternionf(), new Vector3f(0.001f, 0.001f, 0.001f), new Quaternionf()));
                });
                s.e.addPassenger(b[i]);
            }
            barres.put(s.e.getUniqueId(), b);
            s.barreV = s.barreT = -1;
            s.barreY = hauteur(s);
        }
        float v = (float) (s.vie / s.vieMax), t = (float) (Math.max(s.vie, s.vieTraine) / s.vieMax);
        if (Math.abs(v - s.barreV) < 1e-4 && Math.abs(t - s.barreT) < 1e-4) return;
        boolean premier = s.barreV < 0;
        s.barreV = v;
        s.barreT = t;
        float y = s.barreY;
        float[][] m = morceaux(s.vie, Math.max(s.vie, s.vieTraine), s.vieMax);
        poser(b[0], 0, y, 0.004f, CADRE_L * k, CADRE_H * k, premier);
        for (int i = 0; i < 3; i++) poser(b[i + 1], m[i][0] * k, y, 0, Math.max(0.0001f, m[i][1] * k), INT_H * k, premier);
    }

    private static void poser(ItemDisplay d, float x, float y, float z, float largeur, float hauteur, boolean premier) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(premier ? 0 : 3);
        d.setTransformation(new Transformation(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(largeur, hauteur, 0.02f), new Quaternionf()));
    }

    private static boolean valide(ItemDisplay[] b) {
        for (ItemDisplay d : b) if (d == null || !d.isValid() || d.getVehicle() == null) return false;
        return true;
    }

    private static ItemStack objet(String id) {
        try { return VoraceAPI.objet(id, 1); } catch (Throwable e) { return null; }
    }

    /**
     * Hauteur de la barre au-dessus du point où chevauchent ses affichages (le haut du mob) : l'os « tag_ » de son modèle
     * s'il en a un, sinon juste au-dessus de sa tête.
     */
    private float hauteur(Mobs.Suivi s) {
        double haut = s.e.getLocation().getY() + s.e.getHeight();
        try {
            Modeles.Instance m = plugin.modeles() == null ? null : plugin.modeles().instance(s.e);
            if (m != null) {
                for (ModeleBB.Partie p : m.modele().parties()) {
                    if (!p.etiquette()) continue;
                    Location l = m.positionOs(p.nom());
                    if (l != null) return (float) Math.max(-1, Math.min(8, l.getY() - haut + 0.1));
                }
            }
        } catch (Throwable ignore) { }
        return 0.35f;
    }

    private void retirerBarre(UUID mob) {
        ItemDisplay[] b = barres.remove(mob);
        if (b == null) return;
        for (ItemDisplay d : b) if (d != null && d.isValid()) d.remove();
    }

    /** Arrêt du plugin : plus aucune barre. */
    void vider() {
        for (UUID u : new java.util.ArrayList<>(barres.keySet())) retirerBarre(u);
    }

    /**
     * Nom visible ({@code combat.nom-visible}) : l'étiquette de vorace-core (un affichage de texte) a un fond gris par
     * défaut ; on le rend transparent.
     */
    private void etiquette(Mobs.Suivi s) {
        Entity deja = s.etiquette == null ? null : Bukkit.getEntity(s.etiquette);
        if (deja != null && deja.isValid()) return;
        s.etiquette = null;
        for (Entity x : s.e.getNearbyEntities(2, 5, 2)) {
            if (!(x instanceof TextDisplay td) || s.e.customName() == null || !s.e.customName().equals(td.text())) continue;
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            td.setDefaultBackground(false);
            td.setLineWidth(300);
            s.etiquette = td.getUniqueId();
            return;
        }
    }

    /** Toutes les 2 tics : traîne des derniers dégâts qui rattrape la Vie, barre qui apparaît ou disparaît. */
    void tic(Iterable<Mobs.Suivi> suivis, long tic) {
        for (Mobs.Suivi s : suivis) {
            if (!s.d.combattant() || !s.e.isValid()) continue;
            if (s.vieTraine > s.vie && tic - s.traineDepuis > 10) {
                s.vieTraine = Math.max(s.vie, s.vieTraine - Math.max(s.vieMax * 0.02, (s.vieTraine - s.vie) * 0.2));
            }
            if (s.vieTraine > s.vie || tic % 10 == 0) afficher(s);
        }
        if (tic % 20 == 0) {
            // barres orphelines (mob retiré, déchargé) : retirées
            for (Iterator<Map.Entry<UUID, ItemDisplay[]>> it = barres.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, ItemDisplay[]> x = it.next();
                Entity mob = Bukkit.getEntity(x.getKey());
                if (mob != null && mob.isValid() && !mob.isDead() && mobs.suivi(mob) != null) continue;
                for (ItemDisplay d : x.getValue()) if (d != null && d.isValid()) d.remove();
                it.remove();
            }
        }
    }

    /** Mort : la barre part avec lui. */
    void mort(Mobs.Suivi s) {
        s.vie = 0;
        retirerBarre(s.e.getUniqueId());
        Team t = equipe();
        if (t != null) t.removeEntry(s.e.getUniqueId().toString());
    }

    /**
     * Un mob avec un modèle est invisible ; le jeu montrerait quand même son nom quand on le vise. Une équipe « jamais
     * de nom » le cache.
     */
    private void cacherNomDuJeu(Mobs.Suivi s) {
        Team t = equipe();
        if (t == null) return;
        t.addEntry(s.e.getUniqueId().toString());
        s.dansEquipe = true;
    }

    private Team equipe() {
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            Team t = sb.getTeam("vorace_mobs");
            if (t == null) {
                t = sb.registerNewTeam("vorace_mobs");
                t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            return t;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Pour /vmob info. */
    static String etat(Mobs.Suivi s) {
        if (!s.d.combattant()) return "";
        Player c = s.ia == null || s.ia.cible == null ? null : Bukkit.getPlayer(s.ia.cible);
        String ia = s.ia == null ? "" : String.format(Locale.ROOT, " combat=%s mode=%s attaques=%d", s.ia.engage ? "oui" : s.ia.rentre ? "rentre" : "non",
            s.ia.mode, s.ia.attaques) + " effets=" + IACombat.effetsPoses
            + (c != null && c.getWorld().equals(s.e.getWorld()) ? String.format(Locale.ROOT, " cible=%.1f vue=%s", c.getLocation().distance(s.e.getLocation()),
                s.e instanceof LivingEntity le && le.hasLineOfSight(c) ? "oui" : "non") : "");
        String groupe = s.ia != null && s.d.combat().suitLeGroupe() ? " joueurs=" + s.ia.joueurs() : "";
        String meca = (s.ia != null && s.ia.munitions >= 0 ? " munitions=" + s.ia.munitions : "") + (s.sansButin ? " sans-butin=oui" : "")
            + (s.rondeIndex >= 0 ? " ronde=" + s.rondeIndex : "") + (s.assis ? " assis=oui" : "")
            + (s.d.combat().meca().fuite() != null ? " fuite=" + (s.arrive >= 0 ? "arrive" : s.fuiteVers != null ? "en-route" : "aucune") : "");
        return String.format(Locale.ROOT, " niveau=%d vie=%.0f/%.0f rang=%s", s.niveau, s.vie, s.vieMax, s.d.combat().rang().nom()) + groupe + ia + meca;
    }
}
