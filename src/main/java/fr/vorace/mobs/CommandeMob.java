package fr.vorace.mobs;

import fr.vorace.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /vmob [liste|invoquer &lt;mob&gt; [nombre]|info [début|*] [joueur]|apparitions|retirer [rayon]|recharger] */
final class CommandeMob implements TabExecutor {

    private final VoraceMob plugin;

    CommandeMob(VoraceMob plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (!s.hasPermission("vorace.mob.admin")) { s.sendMessage(Messages.brut("<red>Permission manquante : vorace.mob.admin")); return true; }
        // mêmes arguments qu'avant la séparation (/vorace mob ...) : a[0] = « mob », a[1] = action
        String[] a = new String[args.length + 1];
        a[0] = "mob";
        System.arraycopy(args, 0, a, 1, args.length);
        mob(s, a);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> r = new ArrayList<>();
        if (!s.hasPermission("vorace.mob.admin")) return r;
        if (a.length == 1) {
            for (String o : List.of("liste", "invoquer", "info", "apparitions", "retirer", "recharger")) if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) r.add(o);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("invoquer") && plugin.mobs() != null) {
            for (DefinitionMob d : plugin.mobs().definitions()) if (d.id().startsWith(a[1].toLowerCase(Locale.ROOT))) r.add(d.id());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("info")) {
            Bukkit.getOnlinePlayers().forEach(p -> r.add(p.getName()));
            r.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(a[2].toLowerCase(Locale.ROOT)));
        }
        return r;
    }

    /** /vmob [liste|invoquer <mob> [nombre]|info|apparitions|retirer [rayon]|recharger] */
    private void mob(CommandSender s, String[] a) {
        Mobs m = plugin.mobs();
        if (m == null) { s.sendMessage(Messages.brut("<red>Les mobs sont désactivés ou n'ont pas pu démarrer (voir la console).")); return; }
        String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "liste";
        Player p = s instanceof Player j ? j : null;
        org.bukkit.Location ici = p != null ? p.getLocation() : org.bukkit.Bukkit.getWorlds().get(0).getSpawnLocation();
        switch (act) {
            case "invoquer" -> {
                if (a.length < 3) { s.sendMessage(Messages.brut("<red>Utilisation : /vmob invoquer <mob> [nombre]")); return; }
                DefinitionMob d = m.definition(a[2]);
                if (d == null) { s.sendMessage(Messages.brut("<red>Mob inconnu : " + Messages.echapper(a[2]))); return; }
                int n = 1;
                try { if (a.length > 3) n = Math.max(1, Math.min(32, Integer.parseInt(a[3]))); } catch (NumberFormatException ignore) { }
                org.bukkit.Location ou = p != null ? p.getLocation() : new org.bukkit.Location(ici.getWorld(), ici.getBlockX() + 0.5, ici.getBlockY(), ici.getBlockZ() - 8.5);
                String troupeau = n > 1 && d.cohesion() > 0 ? "invoques-" + Long.toString(System.nanoTime(), 36) : null;
                int faits = 0;
                for (int k = 0; k < n; k++) if (m.invoquer(d.id(), ou.clone().add((k % 3) - 1, 0, (k / 3) - 1), null, troupeau) != null) faits++;
                s.sendMessage(Messages.brut("<green>" + faits + " " + Messages.echapper(d.id()) + " invoqué(s)" + (troupeau != null ? " en troupeau." : ".")));
            }
            case "info" -> {
                s.sendMessage(Messages.brut("<gray>" + m.suivis().size() + " mob(s) de Vorace chargé(s)"
                    + (m.derniereErreur() == null ? "" : ", dernière erreur : <red>" + Messages.echapper(m.derniereErreur()))));
                String filtre = a.length > 2 && !a[2].equals("*") ? a[2].toLowerCase(Locale.ROOT) : null;
                // depuis la console : /vmob info <début du nom|*> <joueur> relève les mobs autour de ce joueur
                Player autour = a.length > 3 ? org.bukkit.Bukkit.getPlayerExact(a[3]) : null;
                if (autour != null) ici = autour.getLocation();
                List<Mobs.Suivi> proches = filtre == null ? m.proches(ici, 32, 16)
                    : m.proches(ici, 48, 1000).stream().filter(x -> x.definition().id().startsWith(filtre)).limit(48).toList();
                if (proches.isEmpty()) { s.sendMessage(Messages.brut("<yellow>Aucun mob de Vorace à moins de 32 blocs.")); return; }
                for (Mobs.Suivi x : proches) s.sendMessage(Messages.brut("<white>" + Messages.echapper(x.etat())));
            }
            case "apparitions" -> {
                for (Apparition ap : m.apparitions()) s.sendMessage(Messages.brut("<white> " + Messages.echapper(ap.id()) + " <gray>: "
                    + m.membres(ap.id()) + "/" + ap.nombre() + " " + Messages.echapper(String.join(", ", ap.mobs())) + " à "
                    + (int) ap.x() + " " + (int) ap.y() + " " + (int) ap.z() + " (" + Messages.echapper(ap.monde()) + ")"));
                s.sendMessage(Messages.brut("<gray>" + m.apparitions().size() + " point(s) d'apparition."));
            }
            case "retirer" -> {
                double rayon = 8;
                try { if (a.length > 2) rayon = Double.parseDouble(a[2]); } catch (NumberFormatException ignore) { }
                Mobs.Suivi x = m.plusProche(ici, rayon);
                if (x == null) { s.sendMessage(Messages.brut("<yellow>Aucun mob de Vorace à moins de " + (int) rayon + " blocs.")); return; }
                org.bukkit.entity.Entity e = x.entite();
                m.mort(e);
                if (plugin.modeles() != null && plugin.modeles().instance(e) != null) plugin.modeles().retirer(plugin.modeles().instance(e));
                if (e.isValid()) e.remove();
                s.sendMessage(Messages.brut("<green>Mob retiré."));
            }
            case "recharger" -> {
                plugin.recharger();
                s.sendMessage(Messages.brut("<green>Mobs, zones et apparitions relus."));
            }
            default -> {
                s.sendMessage(Messages.brut("<gray>" + m.definitions().size() + " mob(s), " + m.zones().size() + " zone(s), " + m.apparitions().size()
                    + " point(s) d'apparition, " + m.suivis().size() + " mob(s) chargé(s) :"));
                for (DefinitionMob d : m.definitions()) s.sendMessage(Messages.brut("<white> " + Messages.echapper(d.id()) + " <gray>("
                    + Messages.echapper(d.type()) + ", " + d.caractere().name().toLowerCase(Locale.ROOT) + (d.modeles().isEmpty() ? "" : ", modèle " + Messages.echapper(String.join("/", d.modeles()))) + ")"));
                s.sendMessage(Messages.brut("<gray>/vmob invoquer <mob> [nombre] | info [début du nom|*] [joueur] | apparitions | retirer [rayon] | recharger"));
            }
        }
    }
}
