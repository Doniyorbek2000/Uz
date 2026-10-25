import Foundation

struct EmojiCategory: Equatable {
    let id: String
    let icon: String
    let emojis: [String]
}

enum EmojiData {
    static let recentId = "recent"
    static let recentIcon = "🕘"

    static func load() -> [EmojiCategory] {
        guard let data = SharedResources.data("emoji.json"),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let categories = root["categories"] as? [[String: Any]] else { return [] }
        return categories.compactMap { item in
            guard let id = item["id"] as? String, let icon = item["icon"] as? String, let emojis = item["emojis"] as? [String] else { return nil }
            return EmojiCategory(id: id, icon: icon, emojis: emojis)
        }
    }
}
