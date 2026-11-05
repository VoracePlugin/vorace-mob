// Faux joueur de l'essai sur la map d'Orario : se connecte et reste connecté (durée en secondes), affiche les messages.
import mineflayer from 'mineflayer';
const duree = Number(process.argv[2] || 120) * 1000;
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'Testeur', auth: 'offline' });
const h = () => new Date().toISOString().slice(11, 19);
bot.once('spawn', () => {
  console.log(`BOT CONNECTE ${bot.version} (${h()})`);
  setTimeout(() => { bot.quit(); setTimeout(() => process.exit(0), 500); }, duree);
});
bot.on('message', (m) => console.log(`BOT MESSAGE ${m.toString()} (${h()})`));
bot.on('kicked', (r) => console.log('BOT EXPULSE ' + JSON.stringify(r)));
bot.on('error', (e) => console.log('BOT ERREUR ' + e));
