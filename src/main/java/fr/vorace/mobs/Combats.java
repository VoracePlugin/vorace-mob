package fr.vorace.mobs;

import fr.vorace.Messages;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Locale;

/**
 * Vie, niveau et barre des mobs de combat ({@link Combat}). La Vie est gardée par vorace-mob (avec l'entité) ; la
 * santé du jeu suit la même proportion sur 100, pour que le jeu sache quand le mob meurt et qui l'a tué. Les dégâts
 * du plugin du combat passent par {@link #infliger} ; les autres (feu, chute, coup d'un serveur sans plugin de combat)
 * sont convertis : 1 point de santé du jeu = 1 % de la Vie.
 */
final class Combats {

    /** Santé maximale du jeu d'un mob de combat (la Vie réelle est dans vorace-mob). */
    static final double SANTE = 100;
    /** Le mob reste « en combat » (barre visible) tant de millisecondes après un coup reçu ou donné. */
    static final long DUREE_COMBAT = 10_000;
    private static final int CASES = 20;

    private final VoraceMob plugin;
    private final NamespacedKey cleVie;

    Combats(VoraceMob plugin) {
        this.plugin = plugin;
        cleVie = NamespacedKey.fromString("vorace:mob_vie");
    }

    /** Clé de données où invoquer() range « vie;niveau » d'un mob qui vient d'apparaître. */
    NamespacedKey cle() { return cleVie; }

    /** Reprend (ou prépare) la Vie et le niveau d'un mob de combat chargé. */
    void charger(Mobs.Suivi s) {
        Combat c = s.d.combat();
        String v = s.e.getPersistentDataContainer().get(cleVie, PersistentDataType.STRING);
        s.vieMax = c.vie();
        s.vie = c.vie();
        s.niveau = c.niveauMin();
        if (v != null) {
            String[] p = v.split(";");
            try {
                if (p.length > 0 && !p[0].isEmpty()) s.vie = Math.max(0, Math.min(c.vie(), Double.parseDouble(p[0])));
                if (p.length > 1) s.niveau = Integer.parseInt(p[1]);
            } catch (NumberFormatException ignore) { }
        }
        if (s.e instanceof LivingEntity le) {
            AttributeInstance max = Mobs.attribut(le, "max_health");
            if (max != null) max.setBaseValue(SANTE);
            AttributeInstance armure = Mobs.attribut(le, "armor");  // la défense du mob, c'est sa Résistance, pas l'armure du jeu
            if (armure != null) armure.setBaseValue(0);
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
     */
    double infliger(Mobs.Suivi s, double montant) {
        s.compteTic = Bukkit.getCurrentTick();
        s.dernierCombat = System.currentTimeMillis();
        s.vie = Math.max(0, s.vie - Math.max(0, montant));
        sauver(s);
        afficher(s);
        if (!(s.e instanceof LivingEntity le)) return 0;
        double avant = le.getHealth();
        if (s.vie <= 0) return avant + 1;
        return Math.max(0.001, avant - Combat.santeDuJeu(s.vie, s.vieMax, SANTE));
    }

    /** Rend de la Vie au mob (soins de ses alliés, régénération). */
    void soigner(Mobs.Suivi s, double montant) {
        if (s.vie <= 0 || montant <= 0) return;
        s.vie = Math.min(s.vieMax, s.vie + montant);
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
        double montant = e.getFinalDamage() * s.vieMax / SANTE;
        double jeu = infliger(s, montant);
        for (EntityDamageEvent.DamageModifier m : EntityDamageEvent.DamageModifier.values()) {
            if (m != EntityDamageEvent.DamageModifier.BASE && e.isApplicable(m)) e.setDamage(m, 0);
        }
        e.setDamage(EntityDamageEvent.DamageModifier.BASE, jeu);
    }

    boolean enCombat(Mobs.Suivi s) {
        return System.currentTimeMillis() - s.dernierCombat < DUREE_COMBAT || s.c.action() == Cerveau.Action.ATTAQUER;
    }

    /** Nom, niveau et (en combat) barre de vie au-dessus du mob, au point « tag_ » de son modèle. */
    void afficher(Mobs.Suivi s) {
        Combat c = s.d.combat();
        if (c == null || !c.barre() || !s.e.isValid()) return;
        String nom = s.d.nom() != null ? s.d.nom() : s.d.id();
        Combat.Rang r = c.rang();
        String t = r.couleur() + r.marque() + Messages.echapper(nom) + " <gray>Niv. " + s.niveau;
        if (enCombat(s) && s.vie > 0) t += "\n" + Combat.barre(s.vie, s.vieMax, CASES) + " <white>" + (int) Math.ceil(s.vie);
        // avec un modèle animé, vorace-core affiche le nom à l'os « tag_ » ; sinon le nom du jeu reste visible
        boolean modele = plugin.modeles() != null && plugin.modeles().instance(s.e) != null;
        if (modele && !s.dansEquipe) cacherNomDuJeu(s);
        if (s.e.isCustomNameVisible() == modele) s.e.setCustomNameVisible(!modele);
        if (t.equals(s.texteNom)) return;
        s.texteNom = t;
        s.e.customName(Messages.brut(t));
    }

    /** Toutes les demi-secondes : la barre apparaît ou disparaît avec le combat. */
    void tic(Iterable<Mobs.Suivi> suivis) {
        for (Mobs.Suivi s : suivis) if (s.d.combattant() && s.e.isValid()) afficher(s);
    }

    /** Mort : la barre part avec lui, et rien du jeu ne tombe (le butin viendra de sa table de butin). */
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
        return String.format(Locale.ROOT, " niveau=%d vie=%.0f/%.0f rang=%s", s.niveau, s.vie, s.vieMax, s.d.combat().rang().nom());
    }
}
