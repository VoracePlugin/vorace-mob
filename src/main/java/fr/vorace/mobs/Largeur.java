package fr.vorace.mobs;

/**
 * Largeur en pixels d'un texte dans la police par défaut du jeu (lettres latines, chiffres, ponctuation courante), pour
 * centrer un nom sur la barre de boss. Un caractère inconnu compte 6 pixels.
 */
final class Largeur {

    private Largeur() {}

    /** Avance (largeur + 1 pixel d'espace) d'un caractère. */
    static int de(char c) {
        switch (c) {
            case ' ': return 4;
            case '!': case '\'': case ',': case '.': case ':': case ';': case 'i': case '|': return 2;
            case 'l': case '`': return 3;
            case '"': case '(': case ')': case '*': case 'I': case '[': case ']': case 't': case '{': case '}':
            case 'î': case 'ï': case 'ì': case 'í': case '·': case '°': return 4;
            case '<': case '>': case 'f': case 'k': return 5;
            case '@': case '~': case '«': case '»': return 7;
            case '’': return 3;
            case 'œ': case 'Œ': case 'æ': case 'Æ': return 8;
            default: return 6;
        }
    }

    static int de(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) n += de(s.charAt(i));
        return n;
    }
}
