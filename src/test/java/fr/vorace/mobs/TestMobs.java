package fr.vorace.mobs;

import fr.vorace.mobs.Cerveau.Action;
import fr.vorace.mobs.Cerveau.Activite;
import fr.vorace.mobs.Cerveau.Caractere;
import fr.vorace.mobs.Cerveau.Perception;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Cerveau des mobs (besoins, émotions, choix), géométrie de la laisse, zones et lecture des fichiers. */
public final class TestMobs {
    static int echecs = 0;
    static void verifier(boolean ok, String quoi) { System.out.println((ok ? "OK    " : "ECHEC ") + quoi); if (!ok) echecs++; }

    /** Situation de base : à la maison, seul, personne autour, de jour, rien à manger ni à boire. */
    static Perception calme() {
        return new Perception(2, 20, false, Double.NaN, 0, Double.NaN, false, false, 10, false, false, false, true);
    }

    static Perception avec(Perception p, double distMaison, boolean horsZone, double distTroupeau, double cohesion, double distMenace,
                           boolean rapide, boolean discrete, boolean nuit, boolean manger, boolean boire, boolean surPlace) {
        return new Perception(distMaison, p.laisse(), horsZone, distTroupeau, cohesion, distMenace, rapide, discrete, p.detection(), nuit, manger, boire, surPlace);
    }

    /** Fait penser n fois (0,5 s) dans la même situation ; renvoie les actions vues. */
    static Set<Action> vivre(Cerveau c, Perception p, int n, Caractere car, Activite a) {
        Set<Action> vues = EnumSet.noneOf(Action.class);
        for (int i = 0; i < n; i++) vues.add(c.penser(p, 0.5, car, a));
        return vues;
    }

    public static void main(String[] args) throws Exception {
        Perception p0 = calme();

        // repos et errance alternent
        Cerveau c = new Cerveau(1);
        Set<Action> vues = vivre(c, p0, 240, Caractere.PAISIBLE, Activite.TOUJOURS);
        verifier(vues.contains(Action.REPOS) && vues.contains(Action.ERRER) && vues.size() == 2, "calme : repos et errance alternent, rien d'autre " + vues);

        // faim : va manger, mange sur place, puis arrête
        c = new Cerveau(2);
        c.faim = 0.8;
        Perception aManger = avec(p0, 2, false, Double.NaN, 0, Double.NaN, false, false, false, true, false, false);
        verifier(c.penser(aManger, 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) == Action.MANGER, "affamé avec de la nourriture : mange");
        Perception surPlace = avec(aManger, 2, false, Double.NaN, 0, Double.NaN, false, false, false, true, false, true);
        int n = 0;
        while (c.action() == Action.MANGER && n < 200) { c.penser(surPlace, 0.5, Caractere.PAISIBLE, Activite.TOUJOURS); n++; }
        verifier(c.faim() < 0.1 && c.action() != Action.MANGER && n < 60, "mange sur place puis passe à autre chose (" + n / 2 + " s)");
        Cerveau loin = new Cerveau(2);
        loin.faim = 0.8;
        loin.penser(aManger, 0.5, Caractere.PAISIBLE, Activite.TOUJOURS);
        for (int i = 0; i < 20; i++) loin.penser(aManger, 0.5, Caractere.PAISIBLE, Activite.TOUJOURS);
        verifier(loin.faim() > 0.75, "pas encore arrivé : la faim ne baisse pas");
        Cerveau sansNourriture = new Cerveau(2);
        sansNourriture.faim = 0.9;
        verifier(!vivre(sansNourriture, p0, 20, Caractere.PAISIBLE, Activite.TOUJOURS).contains(Action.MANGER), "affamé sans nourriture connue : ne reste pas planté à vouloir manger");

        // soif
        c = new Cerveau(3);
        c.soif = 0.9;
        verifier(c.penser(avec(p0, 2, false, Double.NaN, 0, Double.NaN, false, false, false, false, true, false), 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) == Action.BOIRE,
            "assoiffé près de l'eau : boit");

        // besoins qui montent avec le temps
        c = new Cerveau(4);
        double f0 = c.faim(), s0 = c.soif();
        vivre(c, p0, 240, Caractere.PAISIBLE, Activite.TOUJOURS);
        verifier(Math.abs(c.faim() - f0 - 120 / Cerveau.FAIM) < 1e-6 && c.soif() > s0, "faim et soif montent avec le temps (2 min)");

        // sommeil
        c = new Cerveau(5);
        Perception nuit = avec(p0, 2, false, Double.NaN, 0, Double.NaN, false, false, true, false, false, true);
        verifier(c.penser(nuit, 0.5, Caractere.PAISIBLE, Activite.DIURNE) == Action.DORMIR, "diurne la nuit : dort");
        vivre(c, nuit, 400, Caractere.PAISIBLE, Activite.DIURNE);
        verifier(c.action() == Action.DORMIR, "dort toute la nuit");
        vivre(c, p0, 4, Caractere.PAISIBLE, Activite.DIURNE);
        verifier(c.action() != Action.DORMIR, "se réveille au jour, reposé");
        verifier(new Cerveau(5).penser(p0, 0.5, Caractere.PAISIBLE, Activite.NOCTURNE) == Action.DORMIR, "nocturne le jour : dort");
        Cerveau fatigue = new Cerveau(6);
        fatigue.fatigue = 1;
        verifier(fatigue.penser(p0, 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) == Action.DORMIR, "épuisé : dort même de jour");

        // laisse et zone
        c = new Cerveau(7);
        Perception dehors = avec(p0, 25, false, Double.NaN, 0, Double.NaN, false, false, false, false, false, true);
        verifier(c.penser(dehors, 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) == Action.RENTRER, "hors de la laisse : rentre");
        verifier(c.penser(avec(p0, 15, false, Double.NaN, 0, Double.NaN, false, false, false, false, false, true), 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) == Action.RENTRER,
            "rentre jusqu'à être bien revenu (pas seulement à la limite)");
        c.penser(avec(p0, 10, false, Double.NaN, 0, Double.NaN, false, false, false, false, false, true), 0.5, Caractere.PAISIBLE, Activite.TOUJOURS);
        verifier(c.action() != Action.RENTRER, "revenu : passe à autre chose");
        verifier(new Cerveau(8).penser(avec(p0, 2, true, Double.NaN, 0, Double.NaN, false, false, false, false, false, true), 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) == Action.RENTRER,
            "hors de sa zone : rentre");

        // troupeau
        c = new Cerveau(9);
        verifier(c.penser(avec(p0, 2, false, 12, 6, Double.NaN, false, false, false, false, false, true), 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) == Action.REJOINDRE,
            "loin du troupeau : le rejoint");
        verifier(!vivre(new Cerveau(9), avec(p0, 2, false, 3, 6, Double.NaN, false, false, false, false, false, true), 40, Caractere.PAISIBLE, Activite.TOUJOURS).contains(Action.REJOINDRE),
            "près du troupeau : reste tranquille");

        // caractères
        Perception joueur5 = avec(p0, 2, false, Double.NaN, 0, 5, false, false, false, false, false, true);
        verifier(new Cerveau(10).penser(joueur5, 0.5, Caractere.CRAINTIF, Activite.TOUJOURS) == Action.FUIR, "craintif : fuit un joueur à 5 blocs");
        verifier(new Cerveau(10).penser(avec(p0, 2, false, Double.NaN, 0, 8, false, true, false, false, false, true), 0.5, Caractere.CRAINTIF, Activite.TOUJOURS) != Action.FUIR,
            "craintif : un joueur accroupi à 8 blocs passe inaperçu");
        verifier(new Cerveau(10).penser(joueur5, 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) != Action.FUIR, "paisible : un joueur qui marche à côté ne l'effraie pas");
        verifier(new Cerveau(10).penser(avec(p0, 2, false, Double.NaN, 0, 2, true, false, false, false, false, true), 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) == Action.FUIR,
            "paisible : un joueur qui fonce dessus le fait fuir");
        verifier(new Cerveau(10).penser(avec(p0, 2, false, Double.NaN, 0, 8, false, false, false, false, false, true), 0.5, Caractere.AGRESSIF, Activite.TOUJOURS) == Action.ATTAQUER,
            "agressif : attaque un joueur remarqué");
        Cerveau terr = new Cerveau(11);
        Perception tout = avec(p0, 2, false, Double.NaN, 0, 3, false, false, false, false, false, true);
        verifier(terr.penser(tout, 0.5, Caractere.TERRITORIAL, Activite.TOUJOURS) != Action.ATTAQUER, "territorial : n'attaque pas tout de suite");
        int t = 0;
        while (terr.action() != Action.ATTAQUER && t < 40) { terr.penser(tout, 0.5, Caractere.TERRITORIAL, Activite.TOUJOURS); t++; }
        verifier(terr.action() == Action.ATTAQUER && t > 3, "territorial : attaque si on reste trop près (" + t / 2.0 + " s)");
        Cerveau frappe = new Cerveau(12);
        frappe.blesser(Caractere.TERRITORIAL);
        verifier(frappe.penser(avec(p0, 2, false, Double.NaN, 0, 6, false, false, false, false, false, true), 0.5, Caractere.TERRITORIAL, Activite.TOUJOURS) == Action.ATTAQUER,
            "territorial frappé : riposte");
        Cerveau peureux = new Cerveau(13);
        peureux.blesser(Caractere.PAISIBLE);
        verifier(peureux.penser(avec(p0, 2, false, Double.NaN, 0, 6, false, false, false, false, false, true), 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) == Action.FUIR,
            "paisible frappé : fuit");
        vivre(peureux, p0, 40, Caractere.PAISIBLE, Activite.TOUJOURS);
        verifier(peureux.action() != Action.FUIR && peureux.peur() < 0.1, "la peur retombe quand la menace est partie");
        Cerveau debandade = new Cerveau(14);
        debandade.effrayer(0.8);
        verifier(debandade.penser(p0, 0.5, Caractere.PAISIBLE, Activite.TOUJOURS) == Action.FUIR, "un compagnon effrayé fait fuir (débandade)");
        Cerveau affame = new Cerveau(15);
        affame.faim = 0.9;
        verifier(affame.penser(avec(p0, 2, false, Double.NaN, 0, 4, true, false, false, true, false, true), 0.5, Caractere.CRAINTIF, Activite.TOUJOURS) == Action.FUIR,
            "la peur passe avant la faim");
        Cerveau endormi = new Cerveau(16);
        endormi.penser(nuit, 0.5, Caractere.CRAINTIF, Activite.DIURNE);
        verifier(endormi.penser(avec(p0, 2, false, Double.NaN, 0, 4, true, false, true, false, false, true), 0.5, Caractere.CRAINTIF, Activite.DIURNE) == Action.FUIR,
            "réveillé par un joueur qui approche en courant : fuit");

        // sauvegarde
        Cerveau a = new Cerveau(20);
        a.faim = 0.25; a.soif = 0.5; a.fatigue = 0.75;
        Cerveau b = new Cerveau(21);
        b.relire(a.sauver());
        verifier(Math.abs(b.faim() - 0.25) < 1e-3 && Math.abs(b.soif() - 0.5) < 1e-3 && Math.abs(b.fatigue() - 0.75) < 1e-3, "besoins sauvés et relus");
        b.relire("n'importe quoi");
        verifier(Math.abs(b.faim() - 0.25) < 1e-3, "sauvegarde abîmée ignorée");

        // géométrie
        Random r = new Random(3);
        boolean dansLaisse = true;
        for (int i = 0; i < 200; i++) {
            double[] q = Geometrie.errance(r.nextDouble() * 30 - 15, r.nextDouble() * 30 - 15, 0, 0, 20, r, (x, z) -> true);
            if (Math.hypot(q[0], q[1]) > 20) dansLaisse = false;
        }
        verifier(dansLaisse, "errance : toujours dans la laisse");
        double[] q = Geometrie.errance(0, 0, 0, 0, 20, r, (x, z) -> x > 0);
        verifier(q[0] > 0, "errance : le filtre (zone, sol) est respecté");
        double[] f = Geometrie.fuite(0, 0, -5, 0, 0, 0, 30, 10);
        verifier(f[0] > 9 && Math.abs(f[1]) < 1e-6, "fuite : droit devant, à l'opposé du joueur");
        double[] bord = Geometrie.fuite(18, 0, 10, 0, 0, 0, 20, 10);
        verifier(Math.hypot(bord[0], bord[1]) <= 20 + 1e-6 && Geometrie.distance(bord[0], bord[1], 10, 0) > 8, "fuite au bord de la laisse : part de biais sans sortir");
        verifier(Geometrie.centre(new double[]{0, 4, 8}, new double[]{0, 0, 3}, 3)[0] == 4, "centre du troupeau");

        // zones
        YamlConfiguration zy = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.join("\n",
            "pre: { monde: world, rectangle: [0, 0, 10, 20], points: [ { type: eau, position: [5, 64, 18] }, { type: eau, position: [1, 64, 1] } ] }",
            "rond: { cercle: [100, 100, 5] }",
            "triangle: { polygone: [[0, 0], [10, 0], [0, 10]] }",
            "faux: { carre: 3 }")));
        List<String> pb = new ArrayList<>();
        Zone pre = Zone.lire("pre", zy.getConfigurationSection("pre"), pb), rond = Zone.lire("rond", zy.getConfigurationSection("rond"), pb),
            tri = Zone.lire("triangle", zy.getConfigurationSection("triangle"), pb), faux = Zone.lire("faux", zy.getConfigurationSection("faux"), pb);
        verifier(pre.contient(5, 10) && !pre.contient(11, 10) && rond.contient(103, 100) && !rond.contient(106, 100), "zones : rectangle et cercle");
        verifier(tri.contient(2, 2) && !tri.contient(8, 8), "zones : polygone");
        verifier(faux == null && pb.size() == 1, "zone sans forme signalée");
        // zone sûre
        YamlConfiguration zs = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.join("\n",
            "village: { monde: donjon, rectangle: [0, 0, 20, 20], sure: true }", "autre: { cercle: [0, 0, 5] }")));
        pb.clear();
        Zone village = Zone.lire("village", zs.getConfigurationSection("village"), pb), autre = Zone.lire("autre", zs.getConfigurationSection("autre"), pb);
        verifier(village.sure() && village.monde().equals("donjon") && village.contient(10, 10) && !autre.sure() && pb.isEmpty(), "zone sûre lue (sure: true), les autres ne le sont pas");
        verifier(!pre.sure() && !rond.sure() && !tri.sure(), "zones : sûres seulement si demandé");
        verifier(pre.plusProche("eau", 4, 15).y() == 64 && pre.plusProche("eau", 4, 15).z() == 18 && pre.plusProche("abri", 0, 0) == null, "point d'intérêt le plus proche");

        // fichiers
        String ex;
        try (InputStream in = TestMobs.class.getClassLoader().getResourceAsStream("exemples/mobs/exemple.yml")) {
            ex = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new java.io.StringReader(ex));
        pb.clear();
        List<DefinitionMob> defs = new ArrayList<>();
        for (String k : y.getKeys(false)) defs.add(DefinitionMob.lire(k, y.getConfigurationSection(k), pb));
        pb.forEach(x -> System.out.println("  problème : " + x));
        DefinitionMob golem = defs.get(0), mouton = defs.get(1);
        verifier(pb.isEmpty() && defs.size() == 3, "exemple : 3 mobs lus sans problème");
        verifier(golem.type().equals("iron_golem") && golem.modeles().equals(List.of("golem_rubis")) && golem.tailleMin() == 0.9 && golem.tailleMax() == 1.1
            && golem.caractere() == Caractere.TERRITORIAL && golem.activite() == Activite.TOUJOURS && golem.laisse() == 12 && !golem.boit()
            && "salut".equals(golem.animation("alerte")) && golem.ennui().equals(List.of("salut")) && golem.cerveau(), "golem : tous les réglages lus");
        verifier(mouton.cohesion() == 6 && mouton.modeles().isEmpty() && mouton.nourriture().contains("short_grass"), "mouton : troupeau et nourriture");
        YamlConfiguration yf = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.join("\n",
            "a: { type: decor }", "b: { caractere: furieux, ia: magique, troupeau: true }",
            "c: { type: decor, modele: oiseau, envol: 4, animations: { envol: fly } }",
            "p: { mob: b, centre: [1, 2, 3], nombre: 4, troupeau: true }", "q: { mob: b }")));
        pb.clear();
        verifier(DefinitionMob.lire("a", yf.getConfigurationSection("a"), pb) == null, "décor sans modèle refusé");
        DefinitionMob bb = DefinitionMob.lire("b", yf.getConfigurationSection("b"), pb);
        verifier(bb.caractere() == Caractere.PAISIBLE && bb.ia().equals("vorace") && bb.cohesion() == 6 && pb.size() == 3, "valeurs inconnues signalées, troupeau: true = 6 blocs");
        DefinitionMob oiseau = DefinitionMob.lire("c", yf.getConfigurationSection("c"), pb);
        verifier(oiseau.decor() && !oiseau.cerveau() && oiseau.envol() == 4 && "fly".equals(oiseau.animation("envol")), "décor qui s'envole");
        pb.clear();
        Apparition ap = Apparition.lire("p", yf.getConfigurationSection("p"), pb);
        verifier(ap != null && ap.nombre() == 4 && ap.troupeau() && ap.x() == 1 && ap.z() == 3 && ap.monde().equals("world") && ap.reapparition() == 300, "apparition lue");
        verifier(Apparition.lire("q", yf.getConfigurationSection("q"), pb) == null && pb.size() == 1, "apparition sans centre refusée");
        verifier(ap.niveau() == 0 && ap.cycle() == 0 && !ap.vagueDue(10_000_000, 1), "apparition : sans niveau ni cycle par défaut");
        YamlConfiguration ycv = YamlConfiguration.loadConfiguration(new java.io.StringReader(
            "v: { mob: b, centre: [0, 0, 0], nombre: 5, niveau: 12, cycle: 120 }"));
        Apparition vague = Apparition.lire("v", ycv.getConfigurationSection("v"), new ArrayList<>());
        verifier(vague != null && vague.niveau() == 12 && vague.cycle() == 120, "apparition : niveau et cycle lus");
        verifier(!vague.vagueDue(100_000, 0) && !vague.vagueDue(100_000, 50_000) && vague.vagueDue(170_000, 50_000) && vague.vagueDue(171_000, 50_000),
            "cycle : rien ne manque = pas de vague ; la vague vient 120 s après la première mort");

        // vol des oiseaux et des papillons (sol plat en y 64, un toit de 6 de haut entre x 10 et 14)
        Vol.Sol plat = (x, z) -> (x >= 10 && x < 14) ? 70 : 64;
        Vol vole = new Vol(1, false, 6.5, 4, 12, 0, 64, 0);
        verifier(vole.etat() == Vol.Etat.POSE, "oiseau posé au départ");
        vole.effrayer(-3, 0, plat, 0, 0, 30);
        verifier(vole.etat() == Vol.Etat.VOL, "un joueur approche : il s'envole");
        double hMax = 0, hMin = 999, loinMax = 0;
        boolean pose = false, auSol = true, revole = false;
        for (int i = 0; i < 20 * 120; i++) {
            vole.pas(0.05, plat, 0, 0, 30);
            if (vole.etat() == Vol.Etat.VOL) {
                double h = vole.y() - plat.hauteur(vole.x(), vole.z());
                hMax = Math.max(hMax, h); hMin = Math.min(hMin, h);
                if (pose) revole = true;
            }
            loinMax = Math.max(loinMax, Math.hypot(vole.x(), vole.z()));
            if (vole.etat() == Vol.Etat.POSE && i > 40) {
                pose = true;
                if (Math.abs(vole.y() - plat.hauteur(vole.x(), vole.z())) > 1e-6) auSol = false;
            }
        }
        verifier(hMin >= -0.01 && hMax <= 16, "en vol : jamais dans le sol, pas trop haut (" + String.format("%.1f à %.1f", hMin, hMax) + " blocs)");
        verifier(loinMax <= 30.5, "reste dans sa laisse (" + String.format("%.1f", loinMax) + " / 30)");
        verifier(pose && auSol, "se repose ailleurs, sur le sol ou un toit");
        verifier(revole, "repart de lui-même après un moment");
        Vol pap = new Vol(2, true, 1.6, 0.5, 2.5, 0, 64, 0);
        pap.effrayer(-1, 0, plat, -20, 0, 8);
        double papMax = 0;
        for (int i = 0; i < 20 * 60; i++) { pap.pas(0.05, plat, -20, 0, 8); if (pap.enVol()) papMax = Math.max(papMax, pap.y() - plat.hauteur(pap.x(), pap.z())); }
        verifier(papMax < 4.5, "papillon : volette bas (" + String.format("%.1f", papMax) + " blocs au plus)");
        YamlConfiguration yv = YamlConfiguration.loadConfiguration(new java.io.StringReader("p: { type: bat, vol: oiseau, altitude: [3, 8], envol: 5, animations: { vol: fly } }"));
        pb.clear();
        DefinitionMob pg = DefinitionMob.lire("p", yv.getConfigurationSection("p"), pb);
        verifier(pb.isEmpty() && pg.volant() && !pg.cerveau() && pg.altMin() == 3 && pg.altMax() == 8 && "fly".equals(pg.animation("vol")), "pigeon volant lu");

        // pigeon qui trottine au sol : petits pas sur le plat, jamais sur le toit voisin, toujours au sol
        YamlConfiguration ym = YamlConfiguration.loadConfiguration(new java.io.StringReader("p: { type: bat, vol: oiseau, marche: 0.9 }"));
        verifier(DefinitionMob.lire("p", ym.getConfigurationSection("p"), pb).marche() == 0.9, "vitesse de marche lue");
        Vol pigeon = new Vol(3, false, 6.5, 3, 9, 8, 64, 0).marcheur(0.9);
        double chemin = 0, px0 = pigeon.x(), pz0 = pigeon.z();
        boolean toujoursAuSol = true, aMarche = false, aPicore = false;
        for (int i = 0; i < 20 * 20; i++) {
            pigeon.pas(0.05, plat, 8, 0, 20);
            if (pigeon.enVol()) break;
            chemin += Math.hypot(pigeon.x() - px0, pigeon.z() - pz0);
            px0 = pigeon.x(); pz0 = pigeon.z();
            if (Math.abs(pigeon.y() - plat.hauteur(pigeon.x(), pigeon.z())) > 1e-6 || pigeon.y() != 64) toujoursAuSol = false;
            if (pigeon.marche()) aMarche = true; else if (aMarche) aPicore = true;
        }
        verifier(toujoursAuSol && aMarche && aPicore && chemin > 3, "pigeon : marche au sol puis s'arrête (" + String.format("%.1f", chemin) + " blocs en 20 s)");

        // combat : bloc « combat: », rang, niveau, Vie du jeu, barre
        String cb;
        try (InputStream in = TestMobs.class.getClassLoader().getResourceAsStream("exemples/mobs/combat.yml")) {
            cb = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        YamlConfiguration yc = YamlConfiguration.loadConfiguration(new java.io.StringReader(cb));
        pb.clear();
        DefinitionMob meche = DefinitionMob.lire("cr_meche_sourde", yc.getConfigurationSection("cr_meche_sourde"), pb);
        pb.forEach(x -> System.out.println("  problème : " + x));
        verifier(pb.isEmpty() && meche.combattant() && meche.combat().vie() == 330 && meche.combat().niveauMin() == 9
            && meche.combat().rang() == Combat.Rang.NORMAL && meche.combat().stat("resistance") == 12 && meche.combat().stat("RES_MAG") == 0
            && meche.combat().stat("esquive") == 0.02 && meche.combat().magique() && meche.combat().type().equals("Fire"), "Mèche-sourde : combat lu");
        verifier(CoupMob.deBase(meche.combat()).puissance() == 66 && CoupMob.deBase(meche.combat()).magique(), "coup de base : Puissance magique 66");
        verifier(!golem.combattant(), "mob sans bloc combat : Vie du jeu");
        verifier(Combat.Rang.lire("elite++") == Combat.Rang.ELITE_PP && Combat.Rang.lire("élite") == Combat.Rang.ELITE
            && Combat.Rang.lire("mini-boss") == Combat.Rang.MINI_BOSS && Combat.Rang.lire("boss_fin") == Combat.Rang.BOSS
            && Combat.Rang.lire("essaim") == Combat.Rang.NORMAL && Combat.Rang.lire("dragon") == null
            && Combat.Rang.BOSS.boss() && Combat.Rang.MINI_BOSS.boss() && !Combat.Rang.ELITE_PP.boss() && Combat.Rang.ELITE_PP.elite(), "rangs lus");
        YamlConfiguration yn = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.join("\n",
            "a: { type: husk, combat: { niveau: [8, 10], vie: 20100, rang: boss } }", "b: { type: husk, combat: { niveau: 3 } }",
            "c: { type: husk, combat: { vie: 10, rang: dieu, stats: { force: beaucoup } } }")));
        pb.clear();
        DefinitionMob ba = DefinitionMob.lire("a", yn.getConfigurationSection("a"), pb);
        verifier(ba.combat().niveauMin() == 8 && ba.combat().niveauMax() == 10 && ba.combat().tirerNiveau(0) == 8 && ba.combat().tirerNiveau(0.999) == 10
            && ba.combat().tirerNiveau(0.5) == 9, "niveau tiré entre 8 et 10");
        verifier(DefinitionMob.lire("b", yn.getConfigurationSection("b"), pb).combat() == null && pb.size() == 1, "combat sans Vie ignoré et signalé");
        pb.clear();
        DefinitionMob bc = DefinitionMob.lire("c", yn.getConfigurationSection("c"), pb);
        verifier(bc.combat().rang() == Combat.Rang.NORMAL && pb.size() == 2, "rang et stat mal écrits signalés");
        verifier(Combat.santeDuJeu(20100, 20100, 100) == 100 && Combat.santeDuJeu(10050, 20100, 100) == 50 && Combat.santeDuJeu(1, 20100, 100) == 0.5
            && Combat.santeDuJeu(0, 20100, 100) == 0, "santé du jeu : même proportion que la Vie (0,5 au moins tant qu'il vit)");
        String barre = Combat.barre(165, 330, 20);
        verifier(barre.startsWith("<#e8c547>") && barre.chars().filter(ch -> ch == '|').count() == 20 && barre.contains("<#3a3a3a>" + "|".repeat(10)),
            "barre à moitié : 10 traits jaunes sur 20");
        verifier(Combat.barre(1, 330, 20).startsWith("<#e04848>|<#3a3a3a>"), "presque mort : un trait rouge reste");

        // cerveau de combat : attaques, IA, butin, progression, phases
        Combat cm = meche.combat();
        Competence goutte = cm.competence("goutte_ardente"), pluie = cm.competence("pluie_de_suif");
        Competence salve = cm.competence("salve_ardente"), eclab = cm.competence("eclaboussure");
        verifier(cm.competences().size() == 4 && goutte != null && goutte.forme() == Competence.Forme.PROJECTILE && goutte.porteeMin() == 3.5
            && goutte.porteeMax() == 16 && goutte.signal() == 8 && goutte.recharge() == 40 && "g_flame_r".equals(goutte.depart(0))
            && goutte.magique() && goutte.type().equals("Fire") && goutte.couleur() == 0xFF7A1A && goutte.vitesse() == 1.1
            && goutte.anticipation() == 0.6 && goutte.total() == 14 && !goutte.lourde() && goutte.presse() == 0 && "fx_cire_goutte".equals(goutte.effets().projectile())
            && goutte.effets().flaque() == null, "Goutte ardente lue (projectile rapide, anticipation, effets)");
        verifier(salve != null && salve.salve() == 3 && salve.intervalle() == 5 && salve.total() == 27 && "g_flame_l".equals(salve.depart(1))
            && "g_flame_c".equals(salve.depart(2)) && "g_flame_r".equals(salve.depart(3)) && salve.flaque() == 40 && salve.flaqueMult() == 0.25 && salve.lourde(),
            "Salve ardente lue (3 tirs, 27 tics, un os par tir, flaques)");
        verifier(pluie != null && pluie.forme() == Competence.Forme.ZONE && pluie.rayon() == 2.6 && pluie.signal() == 18 && pluie.zones() == 2
            && pluie.dispersion() == 4 && pluie.total() == 43 && pluie.duree() == 3 && "fx_cire_pluie".equals(pluie.effets().pluie()),
            "Pluie de suif lue (2 zones, 43 tics)");
        verifier(eclab != null && eclab.forme() == Competence.Forme.CERCLE && eclab.porteeMax() == 2.6 && eclab.repousse() == 0.8
            && eclab.bond() == -4 && eclab.total() == 21 && eclab.presse() == 2 && eclab.lourde(),
            "Éclaboussure lue (cercle, repousse, bond en arrière, seulement après 2 coups reçus)");
        verifier(eclab.animationFrappe() == 7 && eclab.departAnimation() == 5 && eclab.departAnimation() + 16 == eclab.total()
            && goutte.animationFrappe() == 0 && goutte.departAnimation() == 0,
            "animation-frappe : l'Éclaboussure part 5 tics après le début (le geste du tic 7 tombe au tic 12, fin au tic 21) ; sans réglage, tout de suite");
        // rythme d'un groupe : une attaque à la fois sur un joueur, une lourde à la fois
        verifier(IACombat.coupsRecus(List.of(10L, 50L, 100L), 110, 60) == 2 && IACombat.coupsRecus(List.of(), 110, 60) == 0, "coups reçus en 3 s");
        long[] ry = IACombat.rythmeApres(null, 100, goutte, cm.ia().cadence(), cm.ia().repit());
        verifier(ry[0] == 124 && ry[1] == 0, "après une Goutte : le groupe attend 24 tics, pas de répit des attaques lourdes");
        ry = IACombat.rythmeApres(ry, 130, salve, cm.ia().cadence(), cm.ia().repit());
        verifier(ry[0] == 154 && ry[1] == 130 + 27 + 80, "après une Salve : 80 tics de répit après sa fin avant une autre attaque lourde");
        verifier(cm.ia().recul() == 0.85 && cm.ia().souffle() == 40, "recul plus lent que la marche, essoufflé après 40 tics");
        verifier(cm.coupDeBase() != null && cm.coupDeBase().forme() == Competence.Forme.CONTACT && cm.coupDeBase().signal() == 8
            && !cm.coupDeBase().magique() && cm.coupDeBase().type().equals("Impact") && cm.coupDeBase().animation() == null
            && cm.coupDeBase().effets().marque() == null && !cm.nomVisible(), "coup de base au contact, signal 8 ; pas de nom affiché");
        verifier(cm.ia().distance() && cm.ia().distMin() == 5 && cm.ia().distMax() == 11 && cm.ia().aggro() == 14 && cm.ia().laisse() == 28, "IA à distance 5-11");
        verifier(cm.butin().size() == 2 && cm.butin().get(0).objet().equals("vorace:cristal_bleu_nuit") && cm.butin().get(0).min() == 1
            && cm.butin().get(0).max() == 2 && cm.butin().get(1).chance() == 0.25 && cm.xp() == 40, "butin et XP lus");
        verifier(Math.abs(cm.stat("puissance_mag", 12) - 66 * 1.18) < 1e-9 && Math.abs(cm.vieAuNiveau(12) - 429) < 1e-9 && cm.stat("esquive", 12) == 0.02
            && Math.abs(cm.stat("resistance", 7) - 12 * 0.9) < 1e-9 && cm.stat("puissance_mag", 9) == 66 && Math.abs(cm.xpAuNiveau(11) - 48) < 1e-9,
            "niveau 12 : Vie 429, Puissance ×1,18 ; esquive inchangée");
        CoupMob cg = CoupMob.de(cm, 12, goutte, 1.5);
        verifier(Math.abs(cg.puissance() - 66 * 1.18) < 1e-9 && cg.multiplicateur() == 1.5 && "Brulure".equals(cg.alteration()) && cg.duree() == 3
            && cg.nom().equals("Goutte ardente"), "coup d'une attaque au niveau du mob");
        pb.clear();
        DefinitionMob grande = DefinitionMob.lire("cr_grande_meche", yc.getConfigurationSection("cr_grande_meche"), pb);
        pb.forEach(x -> System.out.println("  problème : " + x));
        verifier(pb.isEmpty() && grande.combat().rang() == Combat.Rang.ELITE_PP && grande.combat().enrage() == 300 && grande.combat().phases().size() == 2
            && grande.combat().phases().get(0).seuil() == 0.6 && "cr_meche_sourde".equals(grande.combat().phases().get(0).invocation())
            && grande.combat().phases().get(0).nombre() == 2 && grande.combat().phases().get(1).vitesse() == 1.25
            && grande.combat().competence("cierge_abattu").forme() == Competence.Forme.LIGNE && grande.combat().competence("cierge_abattu").phase() == 1
            && "loin".equals(grande.combat().competence("pluie_de_suif").fixation()), "Grande Mèche : élite ++, phases, fixation, ligne");
        java.util.Map<java.util.UUID, Double> dg = new java.util.HashMap<>();
        java.util.UUID ja = java.util.UUID.randomUUID(), jb = java.util.UUID.randomUUID(), jc = java.util.UUID.randomUUID();
        dg.put(ja, 120.0); dg.put(jb, 100.0); dg.put(jc, 110.0);
        verifier(ja.equals(Butin.gagnant(dg)) && Butin.gagnant(java.util.Map.of()) == null, "butin : personne à 50,01 % → celui qui a fait le plus");
        float[][] mo = Combats.morceaux(165, 198, 330);
        verifier(Math.abs(mo[0][1] - Combats.INT_L * 0.5f) < 1e-5 && Math.abs(mo[1][1] - Combats.INT_L * 0.1f) < 1e-5 && Math.abs(mo[2][1] - Combats.INT_L * 0.4f) < 1e-5
            && Math.abs(mo[0][0] + Combats.INT_L * 0.25f) < 1e-5 && Math.abs(mo[2][0] - Combats.INT_L * 0.3f) < 1e-5
            && Math.abs(mo[0][1] + mo[1][1] + mo[2][1] - Combats.INT_L) < 1e-5, "barre : Vie, traîne et vide côte à côte, sans recouvrement");
        verifier(Combats.morceaux(1, 1, 330)[0][1] > 0.02f && Combats.morceaux(0, 0, 330)[0][1] == 0, "barre : toujours un peu de rouge tant qu'il vit");
        String barreDeBoss = IACombat.barreBoss(1600, 2000, 3200, Combat.Rang.ELITE_PP);
        verifier(barreDeBoss.contains("<glyphe:vmob_boss_cadre_elite_pp_g><decalage:-1><glyphe:vmob_boss_cadre_elite_pp_d>")
            && barreDeBoss.contains("<glyphe:vmob_boss_vie_g_44><decalage:-1><glyphe:vmob_boss_vie_d_00>")
            && barreDeBoss.contains("<glyphe:vmob_boss_traine_g_44><decalage:-1><glyphe:vmob_boss_traine_d_11>")
            && barreDeBoss.split("decalage:-193", -1).length == 3, "barre de boss : cadre, traîne, Vie en deux moitiés");
        verifier(IACombat.barreBoss(1, 1, 3200, Combat.Rang.BOSS).contains("vmob_boss_vie_g_01") && IACombat.barreBoss(3200, 3200, 3200, Combat.Rang.BOSS).contains("vmob_boss_vie_d_44"),
            "barre de boss : pleine, et un palier au moins tant qu'il vit");
        verifier(IACombat.dureeMarque(9) == 8 && IACombat.dureeMarque(18) == 18 && IACombat.dureeMarque(47) == 44 && IACombat.dureeMarque(3) == 4
            && IACombat.dureeFlaque(50) == 50 && IACombat.dureeFlaque(70) == 60 && IACombat.dureeFlaque(500) == 200, "durées des animations de marque et de flaque");
        verifier(Largeur.de("Grande Mèche Nv.10") == 100, "largeur d'un nom");
        // les effets et les images installés dans Vorace existent bien dans le jar
        String inst;
        try (InputStream in = TestMobs.class.getClassLoader().getResourceAsStream("installation.txt")) {
            inst = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int absents = 0, lignes = 0;
        for (String ligne : inst.split("\n")) {
            String[] x = ligne.trim().split(" ");
            if (x.length != 2) continue;
            lignes++;
            if (TestMobs.class.getClassLoader().getResource(x[0]) == null) { absents++; System.out.println("  absent : " + x[0]); }
        }
        verifier(lignes > 190 && absents == 0 && inst.contains("modeles-animes/fx_cire_goutte.bbmodel") && inst.contains("objets/vorace-mob-barre.yml"),
            "installation.txt : " + lignes + " fichiers, tous dans le jar");

        // taille du groupe : Vie et dégâts (Passeur de l'étage 1 : 12 500 × joueurs, dégâts × 0,4 seul, × 0,7 à deux)
        YamlConfiguration yg = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.join("\n",
            "passeur: { type: husk, combat: { niveau: 5, vie: 12500, rang: boss, vie-groupe: 1.0, degats-groupe: {1: 0.4, 2: 0.7} } }",
            "garde: { type: husk, combat: { niveau: 3, vie: 900, rang: elite, vie-groupe: 0.7 } }",
            "seul: { type: husk, combat: { niveau: 1, vie: 50 } }",
            "faux: { type: husk, combat: { niveau: 1, vie: 50, degats-groupe: {zero: 2, 0: 0.5} } }")));
        pb.clear();
        Combat passeur = DefinitionMob.lire("passeur", yg.getConfigurationSection("passeur"), pb).combat();
        Combat garde = DefinitionMob.lire("garde", yg.getConfigurationSection("garde"), pb).combat();
        Combat seul = DefinitionMob.lire("seul", yg.getConfigurationSection("seul"), pb).combat();
        verifier(pb.isEmpty() && passeur.suitLeGroupe() && garde.suitLeGroupe() && !seul.suitLeGroupe(), "vie-groupe et degats-groupe lus");
        verifier(Math.abs(passeur.vieAuNiveau(5) * passeur.facteurVie(1) - 12500) < 1e-6
            && Math.abs(passeur.vieAuNiveau(5) * passeur.facteurVie(6) - 75000) < 1e-6, "Passeur : 12 500 seul, 75 000 à six");
        verifier(passeur.facteurDegats(1) == 0.4 && passeur.facteurDegats(2) == 0.7 && passeur.facteurDegats(3) == 1
            && passeur.facteurDegats(0) == 0.4 && seul.facteurDegats(1) == 1, "Passeur : dégâts × 0,4 seul, × 0,7 à deux, × 1 à trois");
        verifier(Math.abs(garde.facteurVie(3) - 2.4) < 1e-9 && garde.facteurVie(1) == 1 && seul.facteurVie(6) == 1, "élite k = 0,7 : Vie × 2,4 à trois");
        // temps pour le tuer : le même seul qu'à six (Vie × joueurs, dégâts des joueurs × joueurs)
        double dps = 70;
        verifier(Math.abs(passeur.vieAuNiveau(5) * passeur.facteurVie(1) / dps - passeur.vieAuNiveau(5) * passeur.facteurVie(6) / (6 * dps)) < 1e-6,
            "Passeur : même durée seul qu'à six (" + Math.round(12500 / dps) + " s à " + (int) dps + " dégâts/s)");
        CoupMob cp = CoupMob.deBase(passeur).fois(passeur.facteurDegats(1));
        verifier(cp.multiplicateur() == 0.4 && CoupMob.deBase(passeur).fois(1).multiplicateur() == 1, "coup de base × 0,4 seul");
        pb.clear();
        DefinitionMob.lire("faux", yg.getConfigurationSection("faux"), pb);
        verifier(pb.size() == 2, "degats-groupe mal écrit : signalé (" + pb.size() + ")");
        verifier(Butin.estItem("item:e1_fleche_os") && Butin.estItem(" ITEM:arc ") && !Butin.estItem("vorace:e1_os_aventurier")
            && !Butin.estItem("minecraft:bone"), "butin : lignes « item:<modèle> » reconnues");
        YamlConfiguration yb = YamlConfiguration.loadConfiguration(new java.io.StringReader(
            "archer: { type: skeleton, combat: { niveau: 2, vie: 160, butin: [ { objet: \"item:e1_fleche_os\", chance: 0.6, nombre: [3, 6] } ] } }"));
        pb.clear();
        Combat archer = DefinitionMob.lire("archer", yb.getConfigurationSection("archer"), pb).combat();
        verifier(pb.isEmpty() && archer.butin().size() == 1 && archer.butin().get(0).objet().equals("item:e1_fleche_os")
            && archer.butin().get(0).min() == 3 && archer.butin().get(0).max() == 6, "butin : ligne item: lue (flèches en os ×3-6)");

        // mécaniques du bestiaire : garde de face, point faible, mort qui invoque, pantins
        YamlConfiguration yMeca = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.join("\n",
            "ossuaire: { type: skeleton, combat: { niveau: 2, vie: 200, garde: { reduction: 0.8, angle: 120, brise-apres: 3, ouverture: 20, animation: garde_brisee } } }",
            "meche: { type: husk, combat: { niveau: 3, vie: 300, point-faible: { partie: meche, dos: true, multiplicateur: 2, fige: 80 } } }",
            "mosaique: { type: skeleton, combat: { niveau: 1, vie: 100, a-la-mort: { invocation: e1_crane_qui_court, nombre: 1, depart: h_head } } }",
            "pantin: { type: zombie, combat: { niveau: 1, vie: 100, jamais-mortel: true } }")));
        pb.clear();
        Mecaniques mOss = DefinitionMob.lire("ossuaire", yMeca.getConfigurationSection("ossuaire"), pb).combat().meca();
        Mecaniques mm = DefinitionMob.lire("meche", yMeca.getConfigurationSection("meche"), pb).combat().meca();
        Mecaniques ms = DefinitionMob.lire("mosaique", yMeca.getConfigurationSection("mosaique"), pb).combat().meca();
        Mecaniques mp = DefinitionMob.lire("pantin", yMeca.getConfigurationSection("pantin"), pb).combat().meca();
        verifier(pb.isEmpty() && mOss.garde() != null && mOss.garde().briseApres() == 3 && mOss.garde().reduction() == 0.8 && "garde_brisee".equals(mOss.garde().animation())
            && mm.pointFaible() != null && mm.pointFaible().fige() == 80 && ms.aLaMort() != null && ms.aLaMort().mob().equals("e1_crane_qui_court")
            && mp.jamaisMortel() && seul.meca() == Mecaniques.AUCUNE, "mécaniques lues : garde, point faible, a-la-mort, jamais-mortel");
        // le mob regarde le sud (yaw 0 : +z) : un joueur au sud est devant, au nord dans son dos
        double devant = Mecaniques.angle(0, 0, 5), dos = Mecaniques.angle(0, 0, -5), cote = Mecaniques.angle(0, 5, 0), yaw90 = Mecaniques.angle(90, -5, 0);
        verifier(devant < 1 && dos > 179 && Math.abs(cote - 90) < 1 && yaw90 < 1, "angle vers l'attaquant : devant 0°, dos 180°, côté 90° (yaw 90 : regarde vers −x)");
        verifier(mOss.devant(devant) && mOss.devant(55) && !mOss.devant(65) && !mOss.dos(dos) && mm.dos(dos) && mm.dos(125) && !mm.dos(100),
            "garde sur 120° devant (60° de chaque côté) ; point faible dans le dos (au-delà de 120°)");
        // points d'apparition : chance, groupe, XP, butin, pauses, entraide
        YamlConfiguration ya = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.join("\n",
            "rare: { mob: e1_porte_fardeau, monde: donjon, centre: [1, 40, 2], chance: 0.35, reapparition: 600, groupe: r1b, xp-multiplicateur: 1.5, butin-bonus: { objet: \"vorace:cristal_azur\", nombre: 1 }, entraide: 0 }",
            "chapelle: { mob: e1_meche_sourde, monde: donjon, centre: [1, 40, 2], cycle: 180, pause-si-salle: passeur, pause-max: 1200, pause-si-joueur: 24 }",
            "simple: { mob: e1_meche_sourde, monde: donjon, centre: [1, 40, 2] }")));
        pb.clear();
        Apparition rare = Apparition.lire("rare", ya.getConfigurationSection("rare"), pb), chap = Apparition.lire("chapelle", ya.getConfigurationSection("chapelle"), pb),
            simple = Apparition.lire("simple", ya.getConfigurationSection("simple"), pb);
        verifier(pb.isEmpty() && rare.chance() == 0.35 && "r1b".equals(rare.groupe()) && rare.xpMult() == 1.5 && rare.bonus() != null
            && rare.bonus().objet().equals("vorace:cristal_azur") && rare.bonus().min() == 1 && rare.entraide() == 0
            && simple.chance() == 1 && simple.groupe() == null && simple.xpMult() == 1 && simple.bonus() == null && simple.entraide() == -1,
            "points d'apparition : chance, groupe, xp-multiplicateur, butin-bonus, entraide (et leurs valeurs par défaut)");
        verifier(chap.enPause(true, false, 60_000) && chap.enPause(false, true, 60_000) && !chap.enPause(false, false, 60_000)
            && !chap.enPause(true, true, 1_200_000) && !simple.enPause(true, true, 0),
            "Chapelle : pas de vague pendant le combat du Passeur ni sous les yeux d'un joueur, 1 200 s au plus");

        // 1.9.0 : butin spécial, butin par membre, second coup, munitions, crâne qui court, tertre, ronde, phases
        YamlConfiguration y9 = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.join("\n",
            "mosaique: { type: husk, combat: { niveau: 1, vie: 330, a-la-mort: { invocation: e1_crane_qui_court, nombre: 1, depart: h_head, delai: 7 },",
            "  competences: { attaque_double: { forme: cone, rayon: 2.2, angle: 60, signal: 15, actif: 1, recuperation: 12, second-coup: { tic: 28, forme: cone, angle: 120, rayon: 2.2, multiplicateur: 0.5 } } },",
            "  butin-special: [ { objet: \"vorace:e1_poussiere_os\", chance: 0.5, nombre: [1, 2], chasseur: true }, { objet: \"vorace:e1_bouclier_fendu\", chance: 0.05, si: garde_brisee },",
            "    { objet: \"vorace:e1_meche_tressee\", chance: 0.1, si: par_branche_coupee }, { objet: \"vorace:cristal_azur\", chance: 1, nombre: 20, premier-kill: true } ] } }",
            "crane: { type: silverfish, combat: { niveau: 1, vie: 10, duree-de-vie: 160, fuite: { vers: tas_os, rayon: 8 }, si-arrive: { releve: e1_squelette_mosaique, vie: 0.5, delai: 60 } } }",
            "meche: { type: husk, combat: { niveau: 3, vie: 480, munitions: { nombre: 3, recharge: depot_de_cire }, competences: {",
            "  goutte: { forme: projectile, portee: [4, 16], munitions: 3 }, recharge: { forme: contact, portee: 0, signal: 160, apres-munitions: 3 } } } }",
            "gardien: { type: husk, combat: { niveau: 4, vie: 1500, rang: elite, tertre: { regen: 0.01, assis: true } } }",
            "passeur: { type: husk, combat: { niveau: 5, vie: 12500, rang: boss, phases: [ { seuil: 0.6, animations: { garde: idle_a_pied, marche-combat: marche_a_pied } },",
            "  { seuil: 0.25, animations: { garde: idle_garde } } ] } }",
            "faucheuse: { type: husk, combat: { niveau: 5, vie: 13900, ronde: { points: [[230, 41], [229, 60], [236, 73]], arret: 300 } } }")));
        pb.clear();
        Combat c9 = DefinitionMob.lire("mosaique", y9.getConfigurationSection("mosaique"), pb).combat();
        Combat cr9 = DefinitionMob.lire("crane", y9.getConfigurationSection("crane"), pb).combat();
        Combat me9 = DefinitionMob.lire("meche", y9.getConfigurationSection("meche"), pb).combat();
        Combat ga9 = DefinitionMob.lire("gardien", y9.getConfigurationSection("gardien"), pb).combat();
        Combat pa9 = DefinitionMob.lire("passeur", y9.getConfigurationSection("passeur"), pb).combat();
        Combat fa9 = DefinitionMob.lire("faucheuse", y9.getConfigurationSection("faucheuse"), pb).combat();
        verifier(pb.isEmpty(), "1.9.0 : fiches lues sans problème " + pb);
        Competence ad = c9.competences().get(0);
        verifier(ad.secondCoup() != null && ad.secondCoup().tic() == 28 && ad.secondCoup().largeur() == 120 && ad.secondCoup().multiplicateur() == 0.5
            && ad.total() == 28, "second coup lu (tic 28, cône 120°, × 0,5) ; l'attaque dure jusqu'à lui");
        verifier(c9.meca().aLaMort().depart().equals("h_head") && c9.meca().aLaMort().delai() == 7, "a-la-mort : depuis l'os h_head, 7 tics après");
        verifier(c9.butinSpecial().size() == 4 && c9.butinSpecial().get(0).chasseur() && "garde_brisee".equals(c9.butinSpecial().get(1).si())
            && c9.butinSpecial().get(3).premierKill() && c9.butinSpecial().get(3).lot().min() == 20, "butin-special lu (chasseur, si, premier-kill)");
        Combat.LotSpecial garde9 = c9.butinSpecial().get(1), branche9 = c9.butinSpecial().get(2);
        verifier(garde9.chance(0) == 0 && garde9.chance(1) == 0.05 && garde9.chance(3) == 0.05 && branche9.chance(0) == 0
            && Math.abs(branche9.chance(3) - 0.3) < 1e-9 && branche9.chance(20) == 1 && c9.butinSpecial().get(0).chance(0) == 0.5,
            "conditions : « si » seulement si notée ; « par_... » × le nombre de fois (1 au plus)");
        verifier(cr9.meca().dureeDeVie() == 160 && cr9.meca().fuite().vers().equals("tas_os") && cr9.meca().fuite().blocs().contains("bone_block")
            && cr9.meca().siArrive().mob().equals("e1_squelette_mosaique") && cr9.meca().siArrive().vie() == 0.5 && cr9.meca().siArrive().delai() == 60,
            "crâne qui court : 8 s de vie, fuit vers un tas d'os (bloc d'os), relève la Mosaïque à 50 % au bout de 3 s");
        Competence goutte9 = me9.competences().stream().filter(x -> x.id().equals("goutte")).findFirst().get();
        Competence rech = me9.competences().stream().filter(x -> x.id().equals("recharge")).findFirst().get();
        verifier(me9.meca().munitions().nombre() == 3 && me9.meca().munitions().blocs().contains("honeycomb_block") && goutte9.munitions()
            && !goutte9.rechargeMunitions() && rech.rechargeMunitions() && !rech.munitions(), "Mèche-sourde : 3 munitions, recharge au dépôt de cire");
        verifier(ga9.meca().tertre().assis() && ga9.meca().tertre().regen() == 0.01 && ga9.meca().tertre().attente() == 200, "Gardien : assis, 1 % par seconde après 10 s");
        verifier("idle_a_pied".equals(IACombat.animDePhase(pa9, 1, "garde")) && "marche_a_pied".equals(IACombat.animDePhase(pa9, 2, "marche-combat"))
            && "idle_garde".equals(IACombat.animDePhase(pa9, 2, "garde")) && IACombat.animDePhase(pa9, 0, "garde") == null,
            "Passeur : repos et marche à pied en phase 2, garde de la lanterne en phase 3");
        Mecaniques.Ronde ro = fa9.meca().ronde();
        int[] n1 = ro.suivant(1, 1), n2 = ro.suivant(2, 1), n3 = ro.suivant(0, -1);
        verifier(ro.points().size() == 3 && ro.arret() == 300 && n1[0] == 2 && n2[0] == 1 && n2[1] == -1 && n3[0] == 1 && n3[1] == 1
            && ro.bout(0) && ro.bout(2) && !ro.bout(1) && ro.plusProche(236, 70) == 2, "ronde : aller-retour, arrêt aux deux bouts");
        java.util.UUID j9a = new java.util.UUID(0, 1), j9b = new java.util.UUID(0, 2), j9c = new java.util.UUID(0, 3);
        java.util.Map<java.util.UUID, Double> dg9 = java.util.Map.of(j9a, 900.0, j9b, 95.0, j9c, 5.0);
        verifier(Butin.membres(Combat.Rang.NORMAL, dg9, java.util.Map.of()).equals(List.of(j9a))
            && Butin.membres(Combat.Rang.ELITE, dg9, java.util.Map.of()).size() == 1
            && Butin.membres(Combat.Rang.ELITE, dg9, java.util.Map.of(j9c, 120.0)).containsAll(List.of(j9a, j9c))
            && Butin.membres(Combat.Rang.BOSS, java.util.Map.of(j9a, 500.0, j9b, 500.0), java.util.Map.of()).size() == 2,
            "butin de groupe : élites et boss, chaque membre à 10 % (dégâts + soins) ; monstre normal : le gagnant seul");
        Apparition ap9 = Apparition.lire("faucheuse", YamlConfiguration.loadConfiguration(new java.io.StringReader(
            "a: { mob: e1_faucheuse_errante, centre: [230.5, 41, 40.5], apparition: paroi, ronde: { points: [[230, 41], [229, 60]], arret: 300 } }")).getConfigurationSection("a"), pb);
        verifier(ap9.paroi() && ap9.ronde() != null && ap9.ronde().points().size() == 2 && !simple.paroi() && simple.ronde() == null,
            "points d'apparition : sortie de paroi et ronde lues");

        // 1.10.0 : rang porté par les objets de butin (spectre)
        Rangs rg = new Rangs();
        pb.clear();
        rg.lire("essai", YamlConfiguration.loadConfiguration(new java.io.StringReader(String.join("\n",
            "objets:",
            "  e1_crane_fele: { base: Peu commune, spectre: { Commune: 20, Peu commune: 70, Rare: 10 } }",
            "  e1_eclat_lanterne: { base: rare, spectre: { peu_commun: 20, rare: 78, epique: 2 } }",
            "  e1_os_aventurier: { base: commun }"))), pb);
        Rangs.Spectre cf = rg.spectre("e1_crane_fele"), el = rg.spectre("E1_ECLAT_LANTERNE");
        verifier(pb.isEmpty() && rg.taille() == 3 && cf.base().equals("peu_commun") && cf.tirer(0.1).equals("commun") && cf.tirer(0.5).equals("peu_commun")
            && cf.tirer(0.95).equals("rare") && el.tirer(0.99).equals("epique") && rg.spectre("e1_os_aventurier").tirer(0.7).equals("commun"),
            "rangs : spectre lu (noms du jeu ou identifiants), tirage du plus courant au plus rare");
        verifier("peu_commun".equals(Rangs.lire("Peu commune")) && "divin".equals(Rangs.lire("Divine")) && "epique".equals(Rangs.lire("Épique"))
            && Rangs.lire("bizarre") == null, "rangs : noms affichés reconnus");

        // 1.11.0 : neutre, invocation, phase-max, soin d'allié, interrompu-par, éclair à la mort, aura, embuscade
        YamlConfiguration y11 = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.join("\n",
            "glaneur: { type: husk, neutre: true, combat: { niveau: 4, vie: 900 } }",
            "passeur: { type: husk, combat: { niveau: 5, vie: 1000, competences: { lanterne: { forme: cercle, rayon: 3, invocation: { mob: e1_ame_enragee, nombre: 3, max: 6, total: 24 } },",
            "  taille: { forme: cone, rayon: 3, phase-max: 1 } } } }",
            "mouliere: { type: husk, combat: { niveau: 3, vie: 500, famille: cire, competences: { refonte: { forme: contact, portee: 4, signal: 60,",
            "  soin-allie: { cible: cire, seuil: 0.3, soin: 0.4 }, interrompu-par: 2 } } } }",
            "phalene: { type: husk, combat: { niveau: 1, vie: 100, eclair-a-la-mort: { rayon: 6, aveuglement: 20 } } }",
            "violon: { type: husk, combat: { niveau: 2, vie: 200, aura: { rayon: 10, degats: 0.2, purification: true } } }")));
        pb.clear();
        DefinitionMob gl11 = DefinitionMob.lire("glaneur", y11.getConfigurationSection("glaneur"), pb);
        Combat pa11 = DefinitionMob.lire("passeur", y11.getConfigurationSection("passeur"), pb).combat();
        Combat mo11 = DefinitionMob.lire("mouliere", y11.getConfigurationSection("mouliere"), pb).combat();
        Combat ph11 = DefinitionMob.lire("phalene", y11.getConfigurationSection("phalene"), pb).combat();
        Combat vi11 = DefinitionMob.lire("violon", y11.getConfigurationSection("violon"), pb).combat();
        Competence lan = pa11.competences().stream().filter(x -> x.id().equals("lanterne")).findFirst().get();
        Competence tai = pa11.competences().stream().filter(x -> x.id().equals("taille")).findFirst().get();
        Competence ref = mo11.competences().get(0);
        verifier(pb.isEmpty() && gl11.neutre() && lan.invocation().nombre() == 3 && lan.invocation().max() == 6 && lan.invocation().total() == 24
            && tai.phaseMax() == 1 && lan.phaseMax() == -1 && "cire".equals(mo11.meca().famille()) && ref.soinAllie().soin() == 0.4
            && ref.interrompuPar() == 2 && ph11.meca().eclairALaMort().aveuglement() == 20 && vi11.meca().aura().degats() == 0.2,
            "neutre, invocation (max, total), phase-max, famille, soin d'allié, interrompu-par, éclair à la mort, aura");
        Apparition ap11 = Apparition.lire("cache", YamlConfiguration.loadConfiguration(new java.io.StringReader(
            "a: { mob: e1_squelette_rampant, centre: [122.5, 40, 298.5], cache: { eveil: 3 } }")).getConfigurationSection("a"), pb);
        verifier(ap11.eveil() == 3 && simple.eveil() == 0, "embuscade : la vague attend un joueur à 3 blocs");

        System.out.println(echecs == 0 ? "TOUS LES TESTS PASSENT" : echecs + " ECHEC(S)");
        System.exit(echecs == 0 ? 0 : 1);
    }
}
