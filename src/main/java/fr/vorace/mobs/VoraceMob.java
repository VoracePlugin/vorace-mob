package fr.vorace.mobs;

import fr.vorace.Vorace;
import fr.vorace.modeles.Modeles;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * vorace-mob : les mobs de Vorace (cerveau, besoins, troupeaux, laisse, zones, apparitions, volants, décors vivants).
 * Dépend de vorace-core (plugin « Vorace ») pour les modèles animés.
 */
public final class VoraceMob extends JavaPlugin {

    private Vorace core;
    private Mobs mobs;

    /** Le plugin vorace-core (modèles animés, pack). */
    public Vorace core() { return core; }

    /** Les modèles animés de vorace-core, ou null s'ils n'ont pas pu démarrer. */
    public Modeles modeles() { return core == null ? null : core.modeles(); }

    /** Les mobs, ou null s'ils n'ont pas pu démarrer. */
    public Mobs mobs() { return mobs; }

    @Override
    public void onEnable() {
        if (!(Bukkit.getPluginManager().getPlugin("Vorace") instanceof Vorace v) || !v.isEnabled()) {
            getLogger().severe("vorace-mob a besoin de vorace-core (plugin « Vorace ») : désactivé.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        core = v;
        saveDefaultConfig();
        reprendreAnciensFichiers();
        try {
            mobs = new Mobs(this);
            mobs.charger();
            Bukkit.getPluginManager().registerEvents(new EcouteurMobs(this), this);
            mobs.demarrer();
        } catch (Throwable t) {
            mobs = null;
            getLogger().log(Level.SEVERE, "Impossible de démarrer les mobs", t);
        }
        VoraceMobAPI.initialiser(this);
        CommandeMob c = new CommandeMob(this);
        PluginCommand pc = getCommand("vmob");
        if (pc != null) { pc.setExecutor(c); pc.setTabCompleter(c); }
    }

    @Override
    public void onDisable() {
        if (mobs != null) {
            try { mobs.arreter(); } catch (Throwable t) { getLogger().warning("Arrêt des mobs : " + t); }
        }
    }

    /** Relit config.yml, les mobs, les zones et les apparitions. */
    public void recharger() {
        reloadConfig();
        if (mobs != null) mobs.charger();
    }

    /**
     * Avant la séparation (Vorace 1.1), les mobs étaient dans plugins/Vorace. Au premier démarrage, leurs dossiers
     * (mobs, zones, apparitions et le registre des apparitions) sont copiés dans plugins/VoraceMob ; les originaux restent.
     */
    private void reprendreAnciensFichiers() {
        Path ancien = core.getDataFolder().toPath();
        Path nouveau = getDataFolder().toPath();
        for (String d : List.of("mobs", "zones", "apparitions")) {
            Path src = ancien.resolve(d), dst = nouveau.resolve(d);
            if (!Files.isDirectory(src) || Files.exists(dst)) continue;
            try (Stream<Path> fichiers = Files.walk(src)) {
                for (Path f : fichiers.toList()) {
                    Path cible = dst.resolve(src.relativize(f).toString());
                    if (Files.isDirectory(f)) Files.createDirectories(cible);
                    else Files.copy(f, cible, StandardCopyOption.COPY_ATTRIBUTES);
                }
                getLogger().info("plugins/Vorace/" + d + " repris dans plugins/VoraceMob/" + d);
            } catch (IOException e) {
                getLogger().warning("Impossible de reprendre plugins/Vorace/" + d + " : " + e.getMessage());
            }
        }
        Path reg = ancien.resolve("donnees").resolve("apparitions.yml"), regNouveau = nouveau.resolve("donnees").resolve("apparitions.yml");
        if (Files.isRegularFile(reg) && !Files.exists(regNouveau)) {
            try {
                Files.createDirectories(regNouveau.getParent());
                Files.copy(reg, regNouveau);
            } catch (IOException e) {
                getLogger().warning("Impossible de reprendre le registre des apparitions : " + e.getMessage());
            }
        }
    }
}
