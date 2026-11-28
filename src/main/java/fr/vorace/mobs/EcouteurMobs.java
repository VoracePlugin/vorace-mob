package fr.vorace.mobs;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

/** Suivi des mobs de Vorace : chargement et déchargement des chunks, coups reçus, morts. */
public final class EcouteurMobs implements Listener {

    private final VoraceMob plugin;

    public EcouteurMobs(VoraceMob plugin) {
        this.plugin = plugin;
    }

    /** Un coup de mob ne repousse pas par le recul du jeu : seulement par son réglage « repousse ». */
    @EventHandler(ignoreCancelled = true)
    public void reculDuJeu(io.papermc.paper.event.entity.EntityKnockbackEvent e) {
        if (e.getEntity() instanceof Player && plugin.mobs().ia().sansRecul(e.getEntity())) e.setCancelled(true);
    }

    /** Le paquet de vitesse qui suit un coup de mob figerait l'élan du joueur : il n'est pas envoyé. */
    @EventHandler(ignoreCancelled = true)
    public void vitesseApresCoup(org.bukkit.event.player.PlayerVelocityEvent e) {
        if (plugin.mobs().ia().sansRecul(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void chargement(EntitiesLoadEvent e) {
        plugin.mobs().charger(e.getEntities());
    }

    @EventHandler
    public void dechargement(EntitiesUnloadEvent e) {
        for (Entity x : e.getEntities()) plugin.mobs().decharger(x);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void coup(EntityDamageByEntityEvent e) {
        Entity source = e.getDamager();
        Player joueur = source instanceof Player p ? p
            : source instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        plugin.mobs().blesse(e.getEntity(), joueur);
    }

    /** Mob de combat : les dégâts qui ne sont pas passés par le plugin du combat retirent leur part de la Vie. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void degats(EntityDamageEvent e) {
        Mobs.Suivi s = plugin.mobs().suivi(e.getEntity());
        if (s != null && !s.d.combattant() && s.d.quete().invulnerable()) { e.setCancelled(true); return; }   // entité de quête
        if (s != null && s.d.combattant()) plugin.mobs().combats().degatsDuJeu(e, s);
    }

    /** La santé du jeu d'un mob de combat suit sa Vie : le jeu ne la soigne pas tout seul. */
    @EventHandler(ignoreCancelled = true)
    public void soin(EntityRegainHealthEvent e) {
        Mobs.Suivi s = plugin.mobs().suivi(e.getEntity());
        if (s != null && s.d.combattant()) e.setCancelled(true);
    }

    @EventHandler
    public void mort(EntityDeathEvent e) {
        Mobs.Suivi s = plugin.mobs().suivi(e.getEntity());
        if (s != null && s.d.combattant()) {
            // rien du jeu ne tombe d'un mob de combat : son butin viendra de sa table de butin
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
        plugin.mobs().mort(e.getEntity());
    }

    /** Pendant un coup porté par vorace-mob, vorace-core ne relance pas l'animation d'attaque (elle a déjà joué). */
    @EventHandler(ignoreCancelled = true)
    public void animation(fr.vorace.api.VoraceModeleAnimationEvent e) {
        Mobs.Suivi s = plugin.mobs() == null ? null : plugin.mobs().suivi(e.getBase());
        if (s != null && s.sansAnimAuto) e.setCancelled(true);
    }

    @EventHandler
    public void mortJoueur(PlayerDeathEvent e) {
        if (plugin.mobs() != null) plugin.mobs().ia().oublier(e.getEntity());
    }

    @EventHandler
    public void depart(PlayerQuitEvent e) {
        if (plugin.mobs() == null) return;
        plugin.mobs().ia().oublier(e.getPlayer());
        plugin.mobs().spectateurParti(e.getPlayer());
    }

    /** Mode spectateur (à terre, caméra d'un ultime) : les porteurs invisibles des modèles sont cachés tout de suite. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mode(org.bukkit.event.player.PlayerGameModeChangeEvent e) {
        if (plugin.mobs() != null) plugin.mobs().spectateur(e.getPlayer(), e.getNewGameMode() == org.bukkit.GameMode.SPECTATOR);
    }

    @EventHandler
    public void monde(PlayerChangedWorldEvent e) {
        if (plugin.mobs() != null) plugin.mobs().ia().oublier(e.getPlayer());
    }
}
