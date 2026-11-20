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

/** /vmob [liste|invoquer &lt;mob&gt; [nombre] [niveau]|info [début|*] [joueur]|apparitions|retirer [rayon]|recharger] */
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
            for (String o : List.of("liste", "invoquer", "info", "menace", "butin", "point", "apparitions", "retirer", "recharger")) if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) r.add(o);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("invoquer") && plugin.mobs() != null) {
            for (DefinitionMob d : plugin.mobs().definitions()) if (d.id().startsWith(a[1].toLowerCase(Locale.ROOT))) r.add(d.id());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("butin") && plugin.mobs() != null) {
            for (DefinitionMob d : plugin.mobs().definitions()) if (d.combattant() && d.id().startsWith(a[1].toLowerCase(Locale.ROOT))) r.add(d.id());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("point") && plugin.mobs() != null) {
            if ("retirer".startsWith(a[1].toLowerCase(Locale.ROOT))) r.add("retirer");
            for (DefinitionMob d : plugin.mobs().definitions()) if (d.id().startsWith(a[1].toLowerCase(Locale.ROOT))) r.add(d.id());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("point") && a[1].equalsIgnoreCase("retirer") && plugin.mobs() != null) {
            for (Apparition ap : plugin.mobs().apparitions()) if (ap.id().startsWith(a[2].toLowerCase(Locale.ROOT))) r.add(ap.id());
        } else if (a.length >= 3 && a.length <= 5 && a[0].equalsIgnoreCase("point") && !a[1].equalsIgnoreCase("retirer")) {
            List<String> o = a.length == 3 ? List.of("1", "2", "3", "4") : a.length == 4 ? List.of("3", "5", "8") : List.of("180", "300", "600");
            for (String x : o) if (x.startsWith(a[a.length - 1])) r.add(x);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("menace")) {
            for (String o : List.of("8", "16", "32")) if (o.startsWith(a[1])) r.add(o);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("invoquer")) {
            for (String o : List.of("1", "3", "5", "8")) if (o.startsWith(a[2])) r.add(o);
        } else if (a.length == 4 && a[0].equalsIgnoreCase("invoquer") && plugin.mobs() != null) {
            // niveau imposé : les niveaux possibles du mob de combat
            DefinitionMob d = plugin.mobs().definition(a[1]);
            if (d != null && d.combattant()) {
                for (int n = d.combat().niveauMin(); n <= d.combat().niveauMax() && r.size() < 20; n++) if (String.valueOf(n).startsWith(a[3])) r.add(String.valueOf(n));
            }
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
                if (a.length < 3) { s.sendMessage(Messages.brut("<red>Utilisation : /vmob invoquer <mob> [nombre] [niveau]")); return; }
                DefinitionMob d = m.definition(a[2]);
                if (d == null) { s.sendMessage(Messages.brut("<red>Mob inconnu : " + Messages.echapper(a[2]))); return; }
                int n = 1;
                try { if (a.length > 3) n = Math.max(1, Math.min(32, Integer.parseInt(a[3]))); } catch (NumberFormatException ignore) { }
                int niveau = 0;
                try { if (a.length > 4) niveau = Math.max(1, Integer.parseInt(a[4])); } catch (NumberFormatException ignore) { }
                org.bukkit.Location ou = p != null ? p.getLocation() : new org.bukkit.Location(ici.getWorld(), ici.getBlockX() + 0.5, ici.getBlockY(), ici.getBlockZ() - 8.5);
                String troupeau = n > 1 && d.cohesion() > 0 ? "invoques-" + Long.toString(System.nanoTime(), 36) : null;
                int faits = 0;
                for (int k = 0; k < n; k++) if (m.invoquer(d.id(), ou.clone().add((k % 3) - 1, 0, (k / 3) - 1), null, troupeau, niveau) != null) faits++;
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
                    + (int) ap.x() + " " + (int) ap.y() + " " + (int) ap.z() + " (" + Messages.echapper(ap.monde()) + ")"
                    + (ap.niveau() > 0 ? ", niveau " + ap.niveau() : "") + (ap.cycle() > 0 ? ", cycle " + (int) ap.cycle() + " s" : "")));
                s.sendMessage(Messages.brut("<gray>" + m.apparitions().size() + " point(s) d'apparition."));
            }
            case "retirer" -> {
                double rayon = 8;
                try { if (a.length > 2) rayon = Double.parseDouble(a[2]); } catch (NumberFormatException ignore) { }
                Mobs.Suivi x = m.plusProche(ici, rayon);
                if (x == null) { s.sendMessage(Messages.brut("<yellow>Aucun mob de Vorace à moins de " + (int) rayon + " blocs.")); return; }
                m.retirer(x.entite());
                s.sendMessage(Messages.brut("<green>Mob retiré."));
            }
            case "point" -> {
                // point d'apparition à l'endroit du joueur, gardé dans apparitions/points.yml
                if (p == null) { s.sendMessage(Messages.brut("<red>Commande de joueur.")); return; }
                if (a.length < 3) { s.sendMessage(Messages.brut("<red>Utilisation : /vmob point <mob> [nombre] [rayon] [réapparition s] | /vmob point retirer <id>")); return; }
                java.nio.file.Path f = plugin.getDataFolder().toPath().resolve("apparitions").resolve("points.yml");
                org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f.toFile());
                if (a[2].equalsIgnoreCase("retirer")) {
                    if (a.length < 4 || !y.contains(a[3])) { s.sendMessage(Messages.brut("<red>Point inconnu dans apparitions/points.yml.")); return; }
                    y.set(a[3], null);
                } else {
                    DefinitionMob d = m.definition(a[2]);
                    if (d == null) { s.sendMessage(Messages.brut("<red>Mob inconnu : " + Messages.echapper(a[2]))); return; }
                    int n = 2, rea = 180;
                    double r = 4;
                    try {
                        if (a.length > 3) n = Math.max(1, Integer.parseInt(a[3]));
                        if (a.length > 4) r = Math.max(0, Double.parseDouble(a[4]));
                        if (a.length > 5) rea = Math.max(5, Integer.parseInt(a[5]));
                    } catch (NumberFormatException ex) { s.sendMessage(Messages.brut("<red>Nombre mal écrit.")); return; }
                    int k = 1;
                    while (y.contains(d.id() + "_" + k)) k++;
                    String id = d.id() + "_" + k;
                    org.bukkit.Location l = p.getLocation();
                    y.set(id + ".mob", d.id());
                    y.set(id + ".monde", l.getWorld().getName());
                    y.set(id + ".centre", List.of(Math.round(l.getX() * 10) / 10.0, Math.floor(l.getY()), Math.round(l.getZ() * 10) / 10.0));
                    y.set(id + ".rayon", r);
                    y.set(id + ".nombre", n);
                    y.set(id + ".reapparition", rea);
                    s.sendMessage(Messages.brut("<green>Point « " + id + " » : " + n + " " + d.id() + " dans " + r + " blocs, réapparition " + rea + " s."));
                }
                try {
                    java.nio.file.Files.createDirectories(f.getParent());
                    y.save(f.toFile());
                } catch (java.io.IOException ex) { s.sendMessage(Messages.brut("<red>Impossible d'écrire apparitions/points.yml : " + ex.getMessage())); return; }
                plugin.recharger();
                s.sendMessage(Messages.brut("<gray>apparitions/points.yml enregistré et relu."));
            }
            case "menace" -> {
                // état de combat du mob de combat le plus proche : cible, mode, attaque, table de menace
                if (p == null) { s.sendMessage(Messages.brut("<red>Commande de joueur.")); return; }
                double rayon = 16;
                try { if (a.length > 2) rayon = Double.parseDouble(a[2]); } catch (NumberFormatException ignore) { }
                Mobs.Suivi x = null;
                double best = rayon * rayon;
                for (Mobs.Suivi o : m.suivis()) {
                    if (!o.d.combattant() || !o.e.getWorld().equals(p.getWorld())) continue;
                    double d = o.e.getLocation().distanceSquared(p.getLocation());
                    if (d < best) { best = d; x = o; }
                }
                if (x == null) { s.sendMessage(Messages.brut("<yellow>Aucun mob de combat à moins de " + (int) rayon + " blocs.")); return; }
                s.sendMessage(Messages.brut("<white>" + Messages.echapper(x.d.id()) + " <gray>" + Combats.etat(x).trim() + "\n" + IACombat.etat(x)));
            }
            case "butin" -> {
                if (a.length < 3) { s.sendMessage(Messages.brut("<red>Utilisation : /vmob butin <mob>")); return; }
                DefinitionMob d = m.definition(a[2]);
                if (d == null || !d.combattant()) { s.sendMessage(Messages.brut("<red>Mob de combat inconnu : " + Messages.echapper(a[2]))); return; }
                s.sendMessage(Messages.brut("<gray>Butin de <white>" + d.id() + "<gray> (" + d.combat().butin().size() + " ligne(s), XP " + Math.round(d.combat().xp()) + ") :"));
                for (Combat.Lot l : d.combat().butin()) s.sendMessage(Messages.mini("<gray> - <white>" + Messages.echapper(l.objet()) + " <gray>× "
                    + l.min() + (l.max() > l.min() ? "-" + l.max() : "") + ", " + Math.round(l.chance() * 100) + " %"));
                List<String> inconnus = m.butin().problemes(d.combat());
                if (!inconnus.isEmpty()) s.sendMessage(Messages.brut("<red>Objets inconnus : " + Messages.echapper(String.join(", ", inconnus))));
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
                s.sendMessage(Messages.brut("<gray>/vmob invoquer <mob> [nombre] [niveau] | info [début du nom|*] [joueur] | menace [rayon] | butin <mob> | point <mob> [nombre] [rayon] [réapparition] | apparitions | retirer [rayon] | recharger"));
            }
        }
    }
}
