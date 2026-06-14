/**
 * Déduction best-effort de la taille de papier d'une imprimante à partir de sa
 * marque/modèle (remontés par l'imprimante ou son SDK à la connexion).
 *
 * L'imprimante NE renvoie PAS sa largeur de papier de façon standard : on la déduit
 * du modèle via une table de correspondance (extensible). Renvoie `null` si le modèle
 * est inconnu (cas fréquent des ESC/POS génériques) — à afficher tel quel à l'utilisateur.
 *
 * Implémentation de référence (testée), mirrorée en Kotlin (`PaperSize.kt`) et
 * Swift (`PaperSize.swift`) pour le calcul natif au moment du `connectPrinter`.
 */

export interface PaperInfo {
  /** Largeur papier en mm (58 / 80 / 112…), ou null si inconnue. */
  widthMm: number | null;
  /** Largeur imprimable en points @203 dpi (384 / 576 / 832…), ou null. */
  printableDots: number | null;
  /** Résolution en dpi (203 par défaut quand la largeur est connue), ou null. */
  dpi: number | null;
  /** Origine de l'info : 'model' (déduit du modèle), 'sdk', 'profile'. */
  source: 'model' | 'sdk' | 'profile';
}

/** Largeur imprimable standard @203 dpi pour une largeur papier donnée. */
export function paperDotsForWidth(widthMm: number): number | null {
  switch (widthMm) {
    case 58:
      return 384;
    case 80:
      return 576;
    case 112:
      return 832;
    default:
      return null;
  }
}

/**
 * Devine la taille de papier depuis la marque/modèle. Best-effort, `null` si inconnu.
 * La table couvre les modèles courants Epson/Star ; à enrichir au besoin.
 */
export function guessPaperInfo(brand?: string | null, model?: string | null): PaperInfo | null {
  if (!model) return null;
  // On combine marque + modèle : selon la source, le nom du modèle se retrouve
  // parfois dans l'un ou l'autre champ.
  const m = `${brand ?? ''}${model}`.toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (!m) return null;

  let widthMm: number | null = null;
  if (/TMM10|TMP20|TMP60|MCPRINT2|MCP2|SML200|SMS2/.test(m)) {
    widthMm = 58; // Epson TM-m10/P20/P60, Star mC-Print2/SM-L200/SM-S2x
  } else if (/SMT400|TUP5/.test(m)) {
    widthMm = 112; // Star SM-T400 / TUP500
  } else if (/TMM30|TMM50|TMT20|TMT70|TMT8|TMT100|TMP80|TML90|MCPRINT3|MCP3|TSP1|TSP6|TSP7|TSP8|SMT300/.test(m)) {
    widthMm = 80; // Epson TM-m30/T20/T88/…, Star mC-Print3/TSP1xx-8xx/SM-T300
  }

  if (widthMm == null) return null;
  return { widthMm, printableDots: paperDotsForWidth(widthMm), dpi: 203, source: 'model' };
}
