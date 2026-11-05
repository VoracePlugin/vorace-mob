package fr.vorace.mobs;

import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Un mob de Vorace change d'action (repos, errer, manger, boire, dormir, rejoindre, rentrer, fuir, attaquer).
 * Pour réagir (sons, dialogues, quêtes) ou piloter le mob soi-même avec l'API de déplacement de Paper.
 */
public final class VoraceMobActionEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Entity entite;
    private final String mob, ancienne, nouvelle;

    public VoraceMobActionEvent(Entity entite, String mob, String ancienne, String nouvelle) {
        this.entite = entite;
        this.mob = mob;
        this.ancienne = ancienne;
        this.nouvelle = nouvelle;
    }

    public Entity getEntite() { return entite; }
    /** Identifiant du mob (fichier mobs/*.yml). */
    public String getMob() { return mob; }
    /** Action précédente, en minuscules (« errer »...). */
    public String getAncienne() { return ancienne; }
    public String getNouvelle() { return nouvelle; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
