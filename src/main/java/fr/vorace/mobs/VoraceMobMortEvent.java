package fr.vorace.mobs;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Un mob de combat meurt. Le butin et l'XP vont au <b>gagnant</b> : le joueur qui a infligé au moins 50,01 % des dégâts,
 * sinon celui qui en a infligé le plus. Le butin tombe au sol, réservé au gagnant. Le plugin du combat du serveur peut
 * changer les objets, donner l'XP lui-même (écart de niveau, groupe) et couper l'XP du jeu ({@link #setXpDuJeu}).
 * <p>Un seul butin par mob, au gagnant (pas de duplication) : {@link #getButinDesMembres} est vide depuis 1.11.0
 * (gardé pour les plugins qui le lisent ; ce qu'on y met tombe encore pour ce joueur). Les pièces
 * prélevables du Chasseur ({@code butin-special} {@code chasseur: true}) ne tombent pas : {@link #getPiecesPrelevables}.
 */
public final class VoraceMobMortEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Entity entite;
    private final String mob, rang;
    private final int niveau;
    private final Map<UUID, Double> degats;
    private final Player gagnant;
    private final List<ItemStack> objets;
    private double xp;
    private boolean xpDuJeu = true;
    private final Map<UUID, List<ItemStack>> membres, pieces;
    private final boolean sansButin;

    public VoraceMobMortEvent(Entity entite, String mob, String rang, int niveau, Map<UUID, Double> degats, Player gagnant,
                              List<ItemStack> objets, double xp) {
        this(entite, mob, rang, niveau, degats, gagnant, objets, xp, new java.util.HashMap<>(), new java.util.HashMap<>(), false);
    }

    public VoraceMobMortEvent(Entity entite, String mob, String rang, int niveau, Map<UUID, Double> degats, Player gagnant,
                              List<ItemStack> objets, double xp, Map<UUID, List<ItemStack>> membres, Map<UUID, List<ItemStack>> pieces,
                              boolean sansButin) {
        this.entite = entite; this.mob = mob; this.rang = rang; this.niveau = niveau; this.degats = degats;
        this.gagnant = gagnant; this.objets = objets; this.xp = xp; this.membres = membres; this.pieces = pieces;
        this.sansButin = sansButin;
    }

    public Entity getEntite() { return entite; }
    /** Identifiant du mob. */
    public String getMob() { return mob; }
    /** normal, elite, elite++, mini-boss ou boss. */
    public String getRang() { return rang; }
    public int getNiveau() { return niveau; }
    /** Dégâts infligés par chaque joueur (Vie réellement retirée). */
    public Map<UUID, Double> getDegats() { return degats; }
    /** Celui qui reçoit le butin et l'XP, ou null (personne n'a frappé). */
    public Player getGagnant() { return gagnant; }
    /** Objets qui vont tomber pour le gagnant (modifiable). */
    public List<ItemStack> getObjets() { return objets; }
    /**
     * Élites, élites ++ et boss : butin des autres membres du combat (au moins 10 % des dégâts, soins ou protections),
     * joueur → objets, réservés à chacun (modifiable). Vide pour un monstre normal.
     */
    public Map<UUID, List<ItemStack>> getButinDesMembres() { return membres; }
    /**
     * Pièces prélevables du Chasseur ({@code butin-special} avec {@code chasseur: true}), joueur → objets. Elles ne
     * tombent pas : le plugin des métiers les donne après la Dépouille et le Prélèvement (modifiable).
     */
    public Map<UUID, List<ItemStack>> getPiecesPrelevables() { return pieces; }
    /** Vrai pour un mob relevé (Squelette-mosaïque relevé par un tas d'os) : ni butin ni XP. */
    public boolean isSansButin() { return sansButin; }
    /** XP du mob à son niveau (avant l'écart de niveau avec le joueur). */
    public double getXp() { return xp; }
    public void setXp(double xp) { this.xp = Math.max(0, xp); }
    /** Vrai (par défaut) : vorace-mob donne l'XP en points d'expérience du jeu au gagnant. */
    public boolean isXpDuJeu() { return xpDuJeu; }
    public void setXpDuJeu(boolean v) { xpDuJeu = v; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
