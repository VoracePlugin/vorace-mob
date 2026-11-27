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
            String t = id.trim().toLowerCase(Locale.ROOT);
            Rangs.Spectre sp = t.startsWith("vorace:") && plugin.mobs() != null ? plugin.mobs().rangs().spectre(t.substring(7)) : null;
            if (sp != null && n > 0) {
                // rang porté : chaque exemplaire tire son rang dans le spectre de l'objet ; une pile par rang
                Map<String, Integer> parRang = new java.util.LinkedHashMap<>();
                for (int k = 0; k < n; k++) parRang.merge(sp.tirer(hasard.nextDouble()), 1, Integer::sum);
                for (Map.Entry<String, Integer> x : parRang.entrySet()) {
                    ItemStack o = objet(id, x.getValue());
                    if (o == null) continue;
                    marquerRang(o, x.getKey(), sp.base());
                    l.add(o);
                }
                return l;
            }
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

    static final org.bukkit.NamespacedKey CLE_RANG = new org.bukkit.NamespacedKey("vorace", "rang"),
        CLE_RANG_BASE = new org.bukkit.NamespacedKey("vorace", "rang_base");

    /**
     * Un exemplaire tiré à un autre rang que son rang de base : le rang est écrit sur l'objet ({@code vorace:rang},
     * {@code vorace:rang_base}, lus par le barème de Matsuri Économie), son nom prend la couleur du rang et sa description
     * le nom du rang. Au rang de base, l'objet reste tel quel (il s'empile avec les autres).
     */
    static void marquerRang(ItemStack o, String rang, String base) {
        if (rang == null || rang.equals(base)) return;
        Rangs.Rang r = Rangs.rang(rang), b = Rangs.rang(base);
        if (r == null) return;
        o.editMeta(m -> {
            m.getPersistentDataContainer().set(CLE_RANG, org.bukkit.persistence.PersistentDataType.STRING, rang);
            if (base != null) m.getPersistentDataContainer().set(CLE_RANG_BASE, org.bukkit.persistence.PersistentDataType.STRING, base);
            net.kyori.adventure.text.format.TextColor c = net.kyori.adventure.text.format.TextColor.fromHexString(r.couleur());
            net.kyori.adventure.text.Component nom = m.hasDisplayName() ? m.displayName() : null;
            if (nom != null) {
                String brut = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(nom);
                m.displayName(net.kyori.adventure.text.Component.text(brut).color(c)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
            List<net.kyori.adventure.text.Component> lore = m.lore();
            if (lore != null && b != null) {
                List<net.kyori.adventure.text.Component> nv = new ArrayList<>();
                for (net.kyori.adventure.text.Component ligne : lore) {
                    nv.add(ligne.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder().matchLiteral(b.nom()).once()
                        .replacement(net.kyori.adventure.text.Component.text(r.nom()).color(c)).build()));
                }
                m.lore(nv);
            }
        });
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

    /** Part des contributions au-dessus de laquelle un membre tire sa table (élites, élites ++ et boss). */
    static final double PART_MEMBRE = 0.10;

    /**
     * Membres qui tirent leur table : pour un élite, un élite ++ ou un boss, chaque joueur qui a fait au moins 10 % des
     * contributions (dégâts + soins et protections) ; sinon le seul gagnant (50,01 %). Le gagnant vient en premier.
     */
    static List<UUID> membres(Combat.Rang rang, Map<UUID, Double> degats, Map<UUID, Double> soutiens) {
        List<UUID> l = new ArrayList<>();
        UUID g = gagnant(degats);
        if (g == null) return l;
        l.add(g);
        if (rang == Combat.Rang.NORMAL) return l;
        Map<UUID, Double> total = new HashMap<>(degats);
        soutiens.forEach((k, v) -> total.merge(k, Math.max(0, v), Double::sum));
        double somme = 0;
        for (double v : total.values()) somme += Math.max(0, v);
        if (somme <= 0) return l;
        for (Map.Entry<UUID, Double> x : total.entrySet()) {
            if (!x.getKey().equals(g) && x.getValue() / somme >= PART_MEMBRE - 1e-9) l.add(x.getKey());
        }
        return l;
    }

    /**
     * Tire les lignes de {@code butin-special} pour un joueur : conditions notées pendant le combat ({@code si:}), premier
     * kill ({@code premierKill} : le joueur n'a jamais tué ce mob). Les pièces du Chasseur vont dans {@code pieces}.
     */
    List<ItemStack> tirerSpecial(Combat c, Map<String, Integer> conditions, boolean premierKill, List<ItemStack> pieces) {
        List<ItemStack> l = new ArrayList<>();
        for (Combat.LotSpecial ls : c.butinSpecial()) {
            if (ls.premierKill() && !premierKill) continue;
            double ch = ls.chance(ls.si() == null ? 1 : conditions.getOrDefault(ls.si(), 0));
            if (ch <= 0 || hasard.nextDouble() >= ch) continue;
            Combat.Lot lot = ls.lot();
            int n = lot.min() + (lot.max() > lot.min() ? hasard.nextInt(lot.max() - lot.min() + 1) : 0);
            if (n <= 0) continue;
            (ls.chasseur() ? pieces : l).addAll(objets(lot.objet(), n));
        }
        return l;
    }

    /** Joueurs qui ont déjà tué chaque mob (premier kill), lus et sauvés dans donnees/premiers-kills.yml. */
    private final Map<String, java.util.Set<UUID>> premiers = new HashMap<>();
    private boolean premiersLus;

    private java.nio.file.Path fichierPremiers() { return plugin.getDataFolder().toPath().resolve("donnees").resolve("premiers-kills.yml"); }

    /** Vrai (et noté) si c'est la première fois que ce joueur tue ce mob. */
    boolean premierKill(String mob, UUID joueur) {
        if (!premiersLus) {
            premiersLus = true;
            java.io.File f = fichierPremiers().toFile();
            if (f.isFile()) {
                org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                for (String k : y.getKeys(false)) {
                    java.util.Set<UUID> set = new java.util.HashSet<>();
                    for (String u : y.getStringList(k)) { try { set.add(UUID.fromString(u)); } catch (IllegalArgumentException ignore) { } }
                    premiers.put(k, set);
                }
            }
        }
        if (!premiers.computeIfAbsent(mob, k -> new java.util.HashSet<>()).add(joueur)) return false;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            premiers.forEach((k, v) -> y.set(k, v.stream().map(UUID::toString).sorted().toList()));
            java.nio.file.Files.createDirectories(fichierPremiers().getParent());
            y.save(fichierPremiers().toFile());
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("premiers-kills.yml non sauvé : " + e.getMessage());
        }
        return true;
    }

    /**
     * Mort d'un mob de combat : chaque membre qui y a droit tire sa table (le gagnant seul pour un monstre normal), puis
     * l'événement, puis les objets réservés à chacun et l'XP du jeu au gagnant. Un mob relevé (sans butin) ne donne rien.
     */
    void mort(Mobs.Suivi s, Location ou) {
        Combat c = s.d.combat();
        Map<UUID, Double> degats = s.ia == null ? Map.of() : new HashMap<>(s.ia.degats);
        Map<UUID, Double> soutiens = s.ia == null ? Map.of() : new HashMap<>(s.ia.soutiens);
        Map<String, Integer> conditions = s.ia == null ? Map.of() : new HashMap<>(s.ia.conditions);
        UUID g = gagnant(degats);
        Player gagnant = g == null ? null : Bukkit.getPlayer(g);
        Apparition a = plugin.mobs().apparition(s.apparition);
        List<ItemStack> objets = new ArrayList<>();
        Map<UUID, List<ItemStack>> autres = new HashMap<>(), pieces = new HashMap<>();
        if (!s.sansButin) {
            for (UUID u : membres(c.rang(), degats, soutiens)) {
                Player p = Bukkit.getPlayer(u);
                if (p == null || !p.isOnline()) continue;
                List<ItemStack> l = tirer(c), pr = new ArrayList<>();
                // point d'apparition : un objet en plus (régions de l'étage 1)
                if (a != null && a.bonus() != null && hasard.nextDouble() < a.bonus().chance()) l.addAll(objets(a.bonus().objet(), a.bonus().min()));
                if (!c.butinSpecial().isEmpty()) {
                    boolean premier = c.butinSpecial().stream().anyMatch(Combat.LotSpecial::premierKill) ? premierKill(s.d.id(), u) : false;
                    l.addAll(tirerSpecial(c, conditions, premier, pr));
                }
                if (!pr.isEmpty()) pieces.put(u, pr);
                if (u.equals(g)) objets.addAll(l);
                else autres.put(u, l);
            }
        }
        double xp = s.sansButin ? 0 : c.xpAuNiveau(s.niveau) * (a == null ? 1 : a.xpMult());
        VoraceMobMortEvent ev = new VoraceMobMortEvent(s.e, s.d.id(), c.rang().nom(), s.niveau, Map.copyOf(degats), gagnant, objets, xp,
            autres, pieces, s.sansButin);
        Bukkit.getPluginManager().callEvent(ev);
        for (Map.Entry<UUID, List<ItemStack>> x : ev.getButinDesMembres().entrySet()) lacher(ou, x.getKey(), x.getValue());
        if (gagnant == null) return;
        lacher(ou, gagnant.getUniqueId(), ev.getObjets());
        if (ev.isXpDuJeu() && ev.getXp() > 0) gagnant.giveExp((int) Math.round(ev.getXp()));
    }

    private static void lacher(Location ou, UUID pour, List<ItemStack> objets) {
        if (objets == null) return;
        for (ItemStack o : objets) {
            if (o == null || o.getType().isAir()) continue;
            Item i = ou.getWorld().dropItemNaturally(ou, o);
            i.setOwner(pour);
            i.setCanMobPickup(false);
        }
    }

    /** Pour vérifier une table : « /vmob butin <mob> ». */
    List<String> problemes(Combat c) {
        List<String> l = new ArrayList<>();
        for (Combat.Lot lot : c.butin()) if (objet(lot.objet(), 1) == null) l.add(lot.objet());
        for (Combat.LotSpecial ls : c.butinSpecial()) if (objet(ls.lot().objet(), 1) == null) l.add(ls.lot().objet());
        return l;
    }
}
