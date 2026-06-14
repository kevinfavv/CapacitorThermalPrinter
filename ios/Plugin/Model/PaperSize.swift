import Foundation

/// Taille de papier déduite à la connexion (best-effort). Miroir Swift de
/// `src/core/paper.ts`. Champs nullables : `nil` = inconnu.
struct PaperInfo {
    let widthMm: Int?
    let printableDots: Int?
    let dpi: Int?
    let source: String // 'model' | 'sdk' | 'profile'

    func toDict() -> [String: Any] {
        [
            "widthMm": widthMm as Any? ?? NSNull(),
            "printableDots": printableDots as Any? ?? NSNull(),
            "dpi": dpi as Any? ?? NSNull(),
            "source": source,
        ]
    }
}

/// Déduction de la taille papier depuis la marque/modèle (best-effort).
enum PaperSizeGuess {

    static func dotsForWidth(_ widthMm: Int) -> Int? {
        switch widthMm {
        case 58: return 384
        case 80: return 576
        case 112: return 832
        default: return nil
        }
    }

    /// Renvoie la taille papier déduite, ou nil si le modèle est inconnu.
    static func fromBrandModel(_ brand: String?, _ model: String?) -> PaperInfo? {
        guard let model = model, !model.isEmpty else { return nil }
        let m = ((brand ?? "") + model).uppercased().replacingOccurrences(of: "[^A-Z0-9]", with: "", options: .regularExpression)
        if m.isEmpty { return nil }

        func has(_ pattern: String) -> Bool { m.range(of: pattern, options: .regularExpression) != nil }

        let widthMm: Int
        if has("TMM10|TMP20|TMP60|MCPRINT2|MCP2|SML200|SMS2") {
            widthMm = 58
        } else if has("SMT400|TUP5") {
            widthMm = 112
        } else if has("TMM30|TMM50|TMT20|TMT70|TMT8|TMT100|TMP80|TML90|MCPRINT3|MCP3|TSP1|TSP6|TSP7|TSP8|SMT300") {
            widthMm = 80
        } else {
            return nil
        }
        return PaperInfo(widthMm: widthMm, printableDots: dotsForWidth(widthMm), dpi: 203, source: "model")
    }
}
