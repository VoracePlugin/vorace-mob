package fr.vorace.mobs;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Un comportement du bestiaire a eu lieu pour un joueur (sceau recollé, ballot ramassé, vu par la Faucheuse fantôme,
 * troc avec le Glaneur...). {@code evenement} est la clé écrite dans la configuration du mob (par exemple
 * {@code e1.sceau_recolle}) : le plugin des quêtes la compte telle quelle.
 */
public final class VoraceMobEvenementEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player joueur;
    private final Entity entite;
    private final String mob, evenement;

    public VoraceMobEvenementEvent(Player joueur, Entity entite, String mob, String evenement) {
        this.joueur = joueur;
        this.entite = entite;
        this.mob = mob;
        this.evenement = evenement;
    }

    /** Le joueur concerné. */
    public Player getJoueur() { return joueur; }
    /** Le mob (son entité), ou null s'il n'existe plus. */
    public Entity getEntite() { return entite; }
    /** Identifiant du mob (« e1_sauteur_scelle »). */
    public String getMob() { return mob; }
    /** Clé de l'événement (« e1.sceau_recolle »). */
    public String getEvenement() { return evenement; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
