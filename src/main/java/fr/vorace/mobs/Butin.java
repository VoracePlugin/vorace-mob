package fr.vorace.mobs;

import fr.vorace.api.VoraceAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Butin d'un mob de combat : sa table ({@code combat.butin}), tirée à sa mort, et l'attribution des 50,01 %.
 */
final class Butin {

    private final VoraceMob plugin;
    private final Random hasard = new Random();

    Butin(VoraceMob plugin) { this.plugin = plugin; }

    /** Le gagnant : au moins 50,01 % des dégâts, sinon celui qui en a infligé le plus (même résultat, règle de Babel). */
    static UUID gagnant(Map<UUID, Double> degats) {
        UUID meilleur = null;
        double best = 0, total = 0;
        for (Map.Entry<UUID, Double> x : degats.entrySet()) {
            total += x.getValue();
            if (x.getValue() > best) { best = x.getValue(); meilleur = x.getKey(); }
        }
        return total <= 0 ? null : meilleur;
    }

    /** Tire la table de butin. */
    List<ItemStack> tirer(Combat c) {
        List<ItemStack> l = new ArrayList<>();
        for (Combat.Lot lot : c.butin()) {
            if (hasard.nextDouble() >= lot.chance()) continue;
            int n = lot.min() + (lot.max() > lot.min() ? hasard.nextInt(lot.max() - lot.min() + 1) : 0);
            if (n <= 0) continue;
            ItemStack o = objet(lot.objet(), n);
            if (o != null) l.add(o);
        }
        return l;
    }

    /** « minecraft:diamond », « diamond » ou « vorace:rubis » ; null si inconnu. */
    ItemStack objet(String id, int n) {
        String t = id.trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("vorace:")) {
            try { return VoraceAPI.objet(t.substring(7), n); } catch (Throwable e) { return null; }
        }
        Material m = Material.matchMaterial(t);
        return m == null || !m.isItem() ? null : new ItemStack(m, n);
    }

    /** Mort d'un mob de combat : événement, puis objets réservés au gagnant et XP du jeu. */
    void mort(Mobs.Suivi s, Location ou) {
        Combat c = s.d.combat();
        Map<UUID, Double> degats = s.ia == null ? Map.of() : new HashMap<>(s.ia.degats);
        UUID g = gagnant(degats);
        Player gagnant = g == null ? null : Bukkit.getPlayer(g);
        List<ItemStack> objets = gagnant == null ? new ArrayList<>() : tirer(c);
        VoraceMobMortEvent ev = new VoraceMobMortEvent(s.e, s.d.id(), c.rang().nom(), s.niveau, Map.copyOf(degats), gagnant, objets, c.xpAuNiveau(s.niveau));
        Bukkit.getPluginManager().callEvent(ev);
        if (gagnant == null) return;
        for (ItemStack o : ev.getObjets()) {
            if (o == null || o.getType().isAir()) continue;
            Item i = ou.getWorld().dropItemNaturally(ou, o);
            i.setOwner(gagnant.getUniqueId());
            i.setCanMobPickup(false);
        }
        if (ev.isXpDuJeu() && ev.getXp() > 0) gagnant.giveExp((int) Math.round(ev.getXp()));
    }

    /** Pour vérifier une table : « /vmob butin <mob> ». */
    List<String> problemes(Combat c) {
        List<String> l = new ArrayList<>();
        for (Combat.Lot lot : c.butin()) if (objet(lot.objet(), 1) == null) l.add(lot.objet());
        return l;
    }
}
