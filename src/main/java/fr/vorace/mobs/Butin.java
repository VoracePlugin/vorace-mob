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
            l.addAll(objets(lot.objet(), n));
        }
        return l;
    }

    /**
     * Les objets d'une ligne tirée : une pile, ou {@code n} exemplaires d'un objet de vorace-item qui ne s'empile pas
     * (arme, armure). Vide si l'objet est inconnu.
     */
    List<ItemStack> objets(String id, int n) {
        List<ItemStack> l = new ArrayList<>();
        if (!estItem(id)) {
            ItemStack o = objet(id, n);
            if (o != null) l.add(o);
            return l;
        }
        while (n > 0) {
            ItemStack o = objetItem(id.trim().substring(5).trim());
            if (o == null) break;
            int pile = Math.max(1, Math.min(n, o.getMaxStackSize()));
            o.setAmount(pile);
            l.add(o);
            n -= pile;
        }
        return l;
    }

    /** Ligne de butin d'un modèle de vorace-item : « item:&lt;modèle&gt; ». */
    static boolean estItem(String id) {
        return id != null && id.trim().toLowerCase(Locale.ROOT).startsWith("item:");
    }

    /**
     * Un exemplaire d'un modèle de vorace-item (plugin VoraceItem), à son rang de base et à une place tirée au hasard
     * dans sa fourchette de stats ; null si vorace-item n'est pas là ou si le modèle n'existe pas. Appelé par réflexion :
     * vorace-mob n'a pas besoin de vorace-item pour compiler ni pour tourner.
     */
    ItemStack objetItem(String modele) {
        try {
            Class<?> api = Class.forName("fr.vorace.item.api.VoraceItemAPI", true,
                Bukkit.getPluginManager().getPlugin("VoraceItem") == null ? Butin.class.getClassLoader()
                    : Bukkit.getPluginManager().getPlugin("VoraceItem").getClass().getClassLoader());
            for (java.lang.reflect.Method m : api.getMethods()) {
                if (!m.getName().equals("creerExemplaire") || m.getParameterCount() != 5) continue;
                Object o = m.invoke(null, modele, null, hasard.nextDouble(), null, null);
                return o instanceof ItemStack i ? i : null;
            }
        } catch (Throwable ignore) { }
        return null;
    }

    /** « minecraft:diamond », « diamond » ou « vorace:rubis » ; null si inconnu (« item:... » : voir {@link #objets}). */
    ItemStack objet(String id, int n) {
        String t = id.trim();
        if (estItem(t)) {
            ItemStack o = objetItem(t.substring(5).trim());
            if (o != null) o.setAmount(Math.max(1, Math.min(n, o.getMaxStackSize())));
            return o;
        }
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
