package fr.vorace.mobs;

import fr.vorace.Messages;
import fr.vorace.api.VoraceAPI;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Locale;

/**
 * Vie, niveau et barre des mobs de combat ({@link Combat}). La Vie est gardée par vorace-mob (avec l'entité) ; la
 * santé du jeu suit la même proportion sur 100, pour que le jeu sache quand le mob meurt et qui l'a tué. Les dégâts
 * du plugin du combat passent par {@link #infliger} ; les autres (feu, chute, coup d'un serveur sans plugin de combat)
 * sont convertis : 1 point de santé du jeu = 1 % de la Vie. Les stats suivent le niveau ({@link Combat#stat(String, int)}).
 *
 * <p>Au-dessus du mob : son nom (couleur du rang) et son niveau, et en combat une barre de vie dessinée (glyphes de
 * Vorace installés par vorace-mob : fond, traîne claire des derniers dégâts, Vie, cadre selon le rang).
 */
final class Combats {

    /** Santé maximale du jeu d'un mob de combat (la Vie réelle est dans vorace-mob). */
    static final double SANTE = 100;
    /** Le mob reste « en combat » (barre visible) tant de millisecondes après un coup reçu ou donné. */
    static final long DUREE_COMBAT = 10_000;
    /** Avance d'un glyphe de la barre (200 px affichés à l'échelle 1/4, + 1). */
    static final int AVANCE = 51;
    private static final int PALIERS = 50;

    private final VoraceMob plugin;
    private final Mobs mobs;
    private final NamespacedKey cleVie;
    private Boolean glyphes;

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
        s.compteTic = Bukkit.getCurrentTick();
        s.dernierCombat = System.currentTimeMillis();
        double reel = Math.min(s.vie, Math.max(0, montant));
        if (s.vieTraine < s.vie) s.vieTraine = s.vie;
        s.traineDepuis = mobs.tics();
        s.vie = Math.max(0, s.vie - Math.max(0, montant));
        if (source != null && s.ia != null) {
            s.ia.degats.merge(source.getUniqueId(), reel, Double::sum);
            mobs.ia().ajouterMenace(s, source, reel);
        }
        sauver(s);
        afficher(s);
        if (!(s.e instanceof LivingEntity le)) return 0;
        double avant = le.getHealth();
        if (s.vie <= 0) return avant + 1;
        return Math.max(0.001, avant - Combat.santeDuJeu(s.vie, s.vieMax, SANTE));
    }

    /** Rend de la Vie au mob (soins de ses alliés, retour au calme). */
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

    /** Les glyphes de la barre sont-ils chargés par Vorace ? (vérifié une fois) */
    private boolean glyphes() {
        if (glyphes == null) {
            try { glyphes = VoraceAPI.glyphe("vmob_fond") != null && VoraceAPI.glyphe("vmob_vie_" + PALIERS) != null; }
            catch (Throwable t) { glyphes = false; }
            if (!glyphes) plugin.getLogger().info("Barre de vie : glyphes pas encore chargés par Vorace (redémarre ou /vorace reload), barre en texte en attendant.");
        }
        return glyphes;
    }

    /** Texte MiniMessage de la barre : fond, traîne, Vie, cadre superposés (décalages de {@value #AVANCE} px). */
    static String barreGlyphes(double vie, double traine, double vieMax, Combat.Rang rang) {
        int v = palier(vie, vieMax), t = Math.max(v, palier(traine, vieMax));
        String cadre = switch (rang) {
            case NORMAL -> "normal";
            case ELITE -> "elite";
            case ELITE_PP -> "elite_pp";
            default -> "boss";
        };
        String d = "<decalage:-" + AVANCE + ">";
        return String.format(Locale.ROOT, "<white><glyphe:vmob_fond>%s<glyphe:vmob_traine_%02d>%s<glyphe:vmob_vie_%02d>%s<glyphe:vmob_cadre_%s>",
            d, t, d, v, d, cadre);
    }

    /** Palier de remplissage (0 à 50) ; un mob encore en vie a toujours au moins un palier. */
    static int palier(double vie, double vieMax) {
        if (vie <= 0 || vieMax <= 0) return 0;
        return Math.max(1, Math.min(PALIERS, (int) Math.round(PALIERS * vie / vieMax)));
    }

    /** Nom, niveau et (en combat) barre de vie au-dessus du mob, au point « tag_ » de son modèle. */
    void afficher(Mobs.Suivi s) {
        Combat c = s.d.combat();
        if (c == null || !c.barre() || !s.e.isValid()) return;
        String nom = s.d.nom() != null ? s.d.nom() : s.d.id();
        Combat.Rang r = c.rang();
        String t = r.couleur() + r.marque() + Messages.echapper(nom) + " <#9d968c>Nv." + s.niveau;
        if (enCombat(s) && s.vie > 0) {
            t += "\n" + (glyphes() ? barreGlyphes(s.vie, s.vieTraine, s.vieMax, r) : Combat.barre(s.vie, s.vieMax, 20));
        }
        // avec un modèle animé, vorace-core affiche le nom à l'os « tag_ » ; sinon le nom du jeu reste visible
        boolean modele = plugin.modeles() != null && plugin.modeles().instance(s.e) != null;
        if (modele && !s.dansEquipe) cacherNomDuJeu(s);
        if (s.e.isCustomNameVisible() == modele) s.e.setCustomNameVisible(!modele);
        if (modele) etiquette(s);
        if (t.equals(s.texteNom)) return;
        s.texteNom = t;
        s.e.customName(Messages.mini(t));
    }

    /**
     * L'étiquette de vorace-core (un affichage de texte) a un fond gris par défaut : on le rend transparent, sans ombre,
     * pour que la barre dessinée soit propre.
     */
    private void etiquette(Mobs.Suivi s) {
        Entity deja = s.etiquette == null ? null : Bukkit.getEntity(s.etiquette);
        if (deja != null && deja.isValid()) return;
        s.etiquette = null;
        for (Entity x : s.e.getNearbyEntities(2, 5, 2)) {
            if (!(x instanceof TextDisplay td) || s.e.customName() == null || !s.e.customName().equals(td.text())) continue;
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            td.setDefaultBackground(false);
            td.setShadowed(false);
            td.setLineWidth(300);
            s.etiquette = td.getUniqueId();
            return;
        }
    }

    /** Toutes les 2 tics : traîne des derniers dégâts qui rattrape la Vie, barre qui apparaît ou disparaît. */
    void tic(Iterable<Mobs.Suivi> suivis, long tic) {
        for (Mobs.Suivi s : suivis) {
            if (!s.d.combattant() || !s.e.isValid()) continue;
            if (s.vieTraine > s.vie && tic - s.traineDepuis > 12) {
                s.vieTraine = Math.max(s.vie, s.vieTraine - Math.max(s.vieMax * 0.015, (s.vieTraine - s.vie) * 0.18));
            }
            if (s.vieTraine > s.vie || tic % 10 == 0) afficher(s);
        }
    }

    /** Mort : la barre part avec lui. */
    void mort(Mobs.Suivi s) {
        s.vie = 0;
        Team t = equipe();
        if (t != null) t.removeEntry(s.e.getUniqueId().toString());
    }

    /**
     * Un mob avec un modèle est invisible ; le jeu montrerait quand même son nom quand on le vise. Une équipe « jamais
     * de nom » le cache : seul le nom au-dessus du modèle (vorace-core) reste.
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
        org.bukkit.entity.Player c = s.ia == null || s.ia.cible == null ? null : Bukkit.getPlayer(s.ia.cible);
        String ia = s.ia == null ? "" : String.format(Locale.ROOT, " combat=%s mode=%s attaques=%d", s.ia.engage ? "oui" : s.ia.rentre ? "rentre" : "non",
            s.ia.mode, s.ia.attaques)
            + (c != null && c.getWorld().equals(s.e.getWorld()) ? String.format(Locale.ROOT, " cible=%.1f vue=%s", c.getLocation().distance(s.e.getLocation()),
                s.e instanceof LivingEntity le && le.hasLineOfSight(c) ? "oui" : "non") : "");
        return String.format(Locale.ROOT, " niveau=%d vie=%.0f/%.0f rang=%s", s.niveau, s.vie, s.vieMax, s.d.combat().rang().nom()) + ia;
    }
}
