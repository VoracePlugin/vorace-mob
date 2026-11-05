// Faux joueur pour l'essai automatique des modèles animés (CI) : il se connecte au serveur de test et compte
// ce que le jeu reçoit vraiment pour les entités d'affichage (apparitions et changements de transformation).
import mineflayer from 'mineflayer';

const duree = Number(process.argv[2] || 40) * 1000;
const version = process.argv[3] || false; // version du faux joueur (plus récente que le serveur = passage par ViaVersion)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'Testeur', auth: 'offline', version });
const affichages = new Map(); // id d'entité -> { paquets, valeurs: Set }
let typeItemDisplay = null;
let clesUtiles = null; // index des données translation, échelle, rotations (ils changent selon la version)
let debut = 0;
const chronologie = [];

bot._client.on('spawn_entity', (p) => {
  if (typeItemDisplay === null && bot.registry) {
    const e = bot.registry.entitiesByName.item_display;
    typeItemDisplay = e?.id ?? -1;
    clesUtiles = ['translation', 'scale', 'left_rotation', 'right_rotation'].map((n) => e?.metadataKeys?.indexOf(n) ?? -1);
  }
  if (p.type === typeItemDisplay) affichages.set(p.entityId, { paquets: 0, valeurs: new Set() });
});
bot._client.on('entity_destroy', (p) => {
  for (const id of p.entityIds || []) affichages.delete(id);
});
bot._client.on('entity_metadata', (p) => {
  const a = affichages.get(p.entityId);
  if (!a) return;
  a.paquets++;
  const utiles = (p.metadata || []).filter((m) => clesUtiles.includes(m.key));
  if (utiles.length) {
    a.valeurs.add(JSON.stringify(utiles.map((m) => [m.key, m.value])));
    chronologie.push(((Date.now() - debut) / 1000).toFixed(1));
  }
});
bot.once('spawn', () => {
  debut = Date.now();
  console.log('BOT CONNECTE ' + bot.version + ' en ' + bot.entity.position);
  setTimeout(() => {
    let paquets = 0, valeurs = 0;
    for (const a of affichages.values()) { paquets += a.paquets; valeurs += a.valeurs.size; }
    console.log(`BOT RESULTAT affichages=${affichages.size} paquets=${paquets} transformations-distinctes=${valeurs}`);
    const parSeconde = {};
    for (const t of chronologie) { const s = Math.floor(Number(t)); parSeconde[s] = (parSeconde[s] || 0) + 1; }
    console.log('BOT CHRONOLOGIE ' + JSON.stringify(parSeconde));
    console.log('BOT HEURES ' + [...heures].join(' '));
    bot.quit();
    setTimeout(() => process.exit(0), 500);
  }, duree);
});
const heures = new Set();
bot.on('time', () => heures.add(bot.time.timeOfDay));
bot.on('message', (m) => console.log('BOT MESSAGE ' + m.toString() + ' (' + new Date().toISOString().slice(11, 19) + ')'));
bot.on('kicked', (r) => console.log('BOT EXPULSE ' + JSON.stringify(r)));
bot.on('error', (e) => console.log('BOT ERREUR ' + e));
