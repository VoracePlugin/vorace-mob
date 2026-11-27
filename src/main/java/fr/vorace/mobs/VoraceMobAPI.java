package fr.vorace.mobs;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * API de vorace-mob pour les autres plugins (dépendance : com.github.VoracePlugin:vorace-mob).
 * Événement : {@link VoraceMobActionEvent} à chaque changement d'action d'un mob.
 */
public final class VoraceMobAPI {

    private static VoraceMob plugin;

    private VoraceMobAPI() { }

    static void initialiser(VoraceMob p) { plugin = p; }

    private static Mobs mobs() { return plugin == null ? null : plugin.mobs(); }

    /** Fait apparaître un mob (mobs/&lt;fichier&gt;.yml) ; sa maison est cet endroit. Null si le mob est inconnu. */
    public static Entity invoquerMob(String id, Location ou) {
        Mobs m = mobs();
        return m == null ? null : m.invoquer(id, ou, null, null);
    }

    /** Comme {@link #invoquerMob(String, Location)} ; {@code niveau} &gt; 0 impose le niveau d'un mob de combat. */
    public static Entity invoquerMob(String id, Location ou, int niveau) {
        Mobs m = mobs();
        return m == null ? null : m.invoquer(id, ou, null, null, niveau);
    }

    /** Retire un mob de Vorace sans butin ni XP (barre de vie et modèle compris), comme {@code /vmob retirer}. */
    public static void retirerMob(Entity e) {
        Mobs m = mobs();
        if (m != null) m.retirer(e);
    }

    /**
     * Vrai si ce point est dans une zone sûre ({@code sure: true} dans zones/*.yml) : aucun mob de combat n'y apparaît ni
     * n'y entre, et un joueur qui s'y trouve n'est plus leur cible.
     */
    public static boolean zoneSure(Location l) {
        Mobs m = mobs();
        return m != null && m.zoneSure(l);
    }

    /** Identifiant du mob de cette entité, ou null si ce n'est pas un mob de vorace-mob. */
    public static String mobVorace(Entity e) {
        Mobs m = mobs();
        Mobs.Suivi s = m == null ? null : m.suivi(e);
        return s == null ? null : s.definition().id();
    }

    /** Action en cours du mob (« errer », « manger », « fuir »...), ou null. */
    public static String actionMob(Entity e) {
        Mobs m = mobs();
        Mobs.Suivi s = m == null ? null : m.suivi(e);
        return s == null ? null : s.cerveau().action().name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ combat

    private static Mobs.Suivi combattant(Entity e) {
        Mobs m = mobs();
        Mobs.Suivi s = m == null ? null : m.suivi(e);
        return s != null && s.definition().combattant() ? s : null;
    }

    /** Vrai si l'entité est un mob de combat de vorace-mob (bloc {@code combat:}). */
    public static boolean estCombattant(Entity e) {
        return combattant(e) != null;
    }

    /** Niveau du mob de combat, ou 0. */
    public static int niveau(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? 0 : s.niveau;
    }

    /** Rang (« normal », « elite », « elite++ », « mini-boss », « boss »), ou null si ce n'est pas un mob de combat. */
    public static String rang(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? null : s.definition().combat().rang().nom();
    }

    /** Stat du mob de combat (« resistance », « puissance_phys »...), 0 si elle n'est pas écrite. */
    public static double stat(Entity e, String cle) {
        Mobs.Suivi s = combattant(e);
        return s == null ? 0 : s.definition().combat().stat(cle, s.niveau);
    }

    /** Toutes les stats écrites du mob de combat (vide si ce n'en est pas un). */
    public static java.util.Map<String, Double> stats(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? java.util.Map.of() : s.definition().combat().statsAuNiveau(s.niveau);
    }

    /** Vie actuelle du mob de combat, ou 0. */
    public static double vie(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? 0 : s.vie;
    }

    /** Vie maximale du mob de combat, ou 0. */
    public static double vieMax(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? 0 : s.vieMax;
    }

    /**
     * Retire {@code montant} de Vie au mob de combat, pendant l'événement de dégâts du coup ; renvoie les dégâts du jeu à
     * mettre dans l'événement (base, sans armure) pour que sa santé suive, ou -1 si ce n'est pas un mob de combat.
     * Hors d'un événement : {@code entite.damage(infliger(entite, montant), source)} dans le même tic.
     */
    public static double infliger(Entity e, double montant) {
        return infliger(e, montant, null);
    }

    /** Comme {@link #infliger(Entity, double)} ; {@code source} : le joueur qui frappe (menace 1 par dégât, butin). */
    public static double infliger(Entity e, double montant, Entity source) {
        Mobs.Suivi s = combattant(e);
        return s == null ? -1 : plugin.mobs().combats().infliger(s, montant, source instanceof Player p ? p : null);
    }

    /** Nom affiché du mob (« Mèche-sourde »), ou null si ce n'est pas un mob de Vorace. */
    public static String nom(Entity e) {
        Mobs m = mobs();
        Mobs.Suivi s = m == null ? null : m.suivi(e);
        return s == null ? null : s.definition().nom() != null ? s.definition().nom() : s.definition().id();
    }

    /** Vrai si le mob de combat est en combat (il a une cible). */
    public static boolean enCombat(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s != null && s.ia != null && s.ia.engage;
    }

    /** Cible actuelle du mob de combat, ou null. */
    public static Player cible(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null || s.ia == null || s.ia.cible == null ? null : org.bukkit.Bukkit.getPlayer(s.ia.cible);
    }

    /** Ajoute de la menace (compétences de menace, Présence menaçante...). Faux si ce n'est pas un mob de combat. */
    public static boolean ajouterMenace(Entity e, Player joueur, double montant) {
        Mobs.Suivi s = combattant(e);
        if (s == null) return false;
        plugin.mobs().ia().ajouterMenace(s, joueur, montant);
        return true;
    }

    /**
     * Menace d'un soin : 0,5 par point de Vie rendu à {@code soigne}, partagée entre les mobs de combat qui l'ont dans leur
     * table (ils se tournent vers le soigneur).
     */
    public static void menaceDeSoin(Player soigneur, Player soigne, double soin) {
        Mobs m = mobs();
        if (m == null || soin <= 0) return;
        java.util.List<Mobs.Suivi> l = new java.util.ArrayList<>();
        for (Mobs.Suivi s : m.suivis()) if (s.ia != null && s.ia.engage && s.ia.menace.containsKey(soigne.getUniqueId())) l.add(s);
        for (Mobs.Suivi s : l) m.ia().ajouterMenace(s, soigneur, soin * 0.5 / l.size());
    }

    /**
     * Contrôle posé par le plugin du combat : pendant {@code ms}, le mob ne bouge plus ({@code bouger} faux) et/ou
     * n'attaque plus ({@code agir} faux : une attaque en train d'être annoncée est interrompue). Étourdissement,
     * Pétrification : (false, false) ; Enracinement : (false, true).
     */
    public static boolean entraver(Entity e, long ms, boolean bouger, boolean agir) {
        Mobs.Suivi s = combattant(e);
        if (s == null) return false;
        plugin.mobs().ia().entraver(s, ms, bouger, agir);
        return true;
    }

    /** Provocation : le mob prend aussitôt ce joueur pour cible ; à la fin, il garde 110 % de la plus haute menace. */
    public static boolean provoquer(Entity e, Player joueur, long ms) {
        Mobs.Suivi s = combattant(e);
        if (s == null) return false;
        plugin.mobs().ia().provoquer(s, joueur, ms);
        return true;
    }

    /** Aveuglement : la menace de sa cible est divisée par 2, il en cherche une autre. */
    public static boolean aveugler(Entity e, long ms) {
        Mobs.Suivi s = combattant(e);
        if (s == null) return false;
        plugin.mobs().ia().aveugler(s, ms);
        return true;
    }

    /** Peur : il fuit loin de {@code source} pendant {@code ms} (son cerveau de combat s'en charge). */
    public static boolean fuir(Entity e, Entity source, long ms) {
        Mobs.Suivi s = combattant(e);
        if (s == null) return false;
        plugin.mobs().ia().fuir(s, source, ms);
        return true;
    }

    /** Rend de la Vie au mob de combat. Faux si ce n'en est pas un. */
    public static boolean soigner(Entity e, double montant) {
        Mobs.Suivi s = combattant(e);
        if (s == null) return false;
        plugin.mobs().combats().soigner(s, montant);
        return true;
    }

    /**
     * Nombre de joueurs comptés dans le combat de ce mob ({@code vie-groupe}, {@code degats-groupe}) ; 0 si ce n'est pas
     * un mob de combat.
     */
    public static int joueursDuCombat(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null || s.ia == null ? 0 : s.ia.joueurs();
    }

    /**
     * Impose le nombre de joueurs d'un combat (par exemple les joueurs entrés dans l'arène d'un boss) ; sa Vie maximale
     * suit en gardant son pourcentage. 0 rend le comptage à vorace-mob (joueurs à 30 blocs à l'engagement, puis chaque
     * nouveau joueur qui le frappe). Faux si ce n'est pas un mob de combat.
     */
    public static boolean imposerJoueurs(Entity e, int joueurs) {
        Mobs.Suivi s = combattant(e);
        if (s == null) return false;
        plugin.mobs().ia().imposerJoueurs(s, joueurs);
        return true;
    }

    /** Vrai si ses coups ne doivent jamais faire tomber un joueur ({@code combat.jamais-mortel} : pantins d'entraînement). */
    public static boolean jamaisMortel(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s != null && s.definition().combat().meca().jamaisMortel();
    }

    /** Vrai si la garde de face de ce mob a été brisée au moins une fois pendant ce combat (butin « si: garde_brisee »). */
    public static boolean gardeBrisee(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s != null && s.ia != null && s.ia.gardeBrisee;
    }

    /**
     * Note une condition du butin {@code si:} pendant ce combat : {@code meche_eteinte}, {@code avant_appel},
     * {@code trois_cordes_coupees}... Une condition {@code par_...} ({@code par_branche_coupee}, {@code par_piece_cassee})
     * se compte : chaque note ajoute {@code fois} et la chance de la ligne est multipliée d'autant (1 au plus).
     * {@code garde_brisee} est notée par vorace-mob. Faux si ce n'est pas un mob de combat.
     */
    public static boolean noterCondition(Entity e, String condition, int fois) {
        Mobs.Suivi s = combattant(e);
        if (s == null || s.ia == null) return false;
        plugin.mobs().ia().noter(s, condition, fois);
        return true;
    }

    /** Comme {@link #noterCondition(Entity, String, int)}, une fois. */
    public static boolean noterCondition(Entity e, String condition) { return noterCondition(e, condition, 1); }

    /** Nombre de fois qu'une condition du butin a été notée dans ce combat (0 sinon). */
    public static int condition(Entity e, String condition) {
        Mobs.Suivi s = combattant(e);
        return s == null || s.ia == null || condition == null ? 0 : s.ia.conditions.getOrDefault(condition.toLowerCase(java.util.Locale.ROOT).trim(), 0);
    }

    /**
     * Soins ou protection qu'un joueur apporte au combat contre ce mob (un soin sur un allié, un bouclier, des dégâts
     * encaissés à la place d'un autre) : ils comptent comme des dégâts pour la part du butin des élites, élites ++ et boss
     * (10 % des contributions au moins). Faux si ce n'est pas un mob de combat engagé.
     */
    public static boolean contribuer(Entity e, Player joueur, double montant) {
        Mobs.Suivi s = combattant(e);
        if (s == null || s.ia == null || !s.ia.engage) return false;
        plugin.mobs().ia().contribuer(s, joueur, montant);
        return true;
    }

    /** Munitions restantes de ce mob ({@code combat.munitions}) ; −1 s'il n'en a pas ou n'est pas en combat. */
    public static int munitions(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null || s.ia == null ? -1 : s.ia.munitions;
    }

    /** Lui retire des munitions (une branche coupée : un tir de moins). Faux s'il n'en a pas. */
    public static boolean retirerMunitions(Entity e, int n) {
        Mobs.Suivi s = combattant(e);
        if (s == null || s.ia == null || s.ia.munitions < 0) return false;
        s.ia.munitions = Math.max(0, s.ia.munitions - Math.max(0, n));
        return true;
    }

    /** Vrai pour un mob relevé (tas d'os) : il ne donnera ni butin ni XP. */
    public static boolean sansButin(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s != null && s.sansButin;
    }

    /**
     * Purification posée sur ce mob ({@code secondes}) : son aura ({@code combat.aura}, l'Os-violon) s'éteint le temps
     * qu'elle dure. Faux si ce n'est pas un mob de combat.
     */
    public static boolean purifier(Entity e, double secondes) {
        Mobs.Suivi s = combattant(e);
        if (s == null) return false;
        plugin.mobs().ia().purifier(s, Math.round(secondes * 1000));
        return true;
    }

    /** Rang porté par un objet de butin tiré à un autre rang que sa base (« rare »), ou null. */
    public static String rangObjet(org.bukkit.inventory.ItemStack o) {
        if (o == null || !o.hasItemMeta()) return null;
        return o.getItemMeta().getPersistentDataContainer().get(Butin.CLE_RANG, org.bukkit.persistence.PersistentDataType.STRING);
    }

    /** Le coup que ce mob porte en ce moment (pendant l'événement de dégâts de son coup), ou null. */
    public static CoupMob coupEnCours(Entity e) {
        Mobs.Suivi s = combattant(e);
        return s == null ? null : s.coupEnCours;
    }

    /** Effraie un mob (0 à 1) : il fuit, et son troupeau avec lui s'il a assez peur. Faux si ce n'en est pas un. */
    public static boolean effrayerMob(Entity e, double peur) {
        Mobs m = mobs();
        Mobs.Suivi s = m == null ? null : m.suivi(e);
        if (s == null) return false;
        s.cerveau().effrayer(peur);
        return true;
    }
}
