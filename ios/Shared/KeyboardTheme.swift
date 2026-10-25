import UIKit

struct KeyboardTheme {
    let background: UIColor
    let key: UIColor
    let keyPressed: UIColor
    let special: UIColor
    let specialPressed: UIColor
    let text: UIColor
    let subText: UIColor
    let accent: UIColor
    let accentPressed: UIColor
    let onAccent: UIColor
    let shadow: UIColor
    let popup: UIColor
    let divider: UIColor
    let isDark: Bool

    private static func rgb(_ hex: UInt32, alpha: CGFloat = 1) -> UIColor {
        UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }

    static let light = KeyboardTheme(
        background: rgb(0xD3D6DD), key: rgb(0xFFFFFF), keyPressed: rgb(0xC9CED6),
        special: rgb(0xACB1BB), specialPressed: rgb(0x979DA8), text: rgb(0x1A1D22),
        subText: rgb(0x5E6570), accent: rgb(0x0B8BC4), accentPressed: rgb(0x0975A6),
        onAccent: .white, shadow: rgb(0x303A48, alpha: 0.32), popup: .white,
        divider: rgb(0xB5BAC3), isDark: false
    )
    static let dark = KeyboardTheme(
        background: rgb(0x16181C), key: rgb(0x46494F), keyPressed: rgb(0x5C6067),
        special: rgb(0x2E3136), specialPressed: rgb(0x44474D), text: rgb(0xF2F4F7),
        subText: rgb(0xA0A7B1), accent: rgb(0x34A7DB), accentPressed: rgb(0x2B8DBA),
        onAccent: rgb(0x06131A), shadow: rgb(0x000000, alpha: 0.4), popup: rgb(0x55585F),
        divider: rgb(0x3A3D43), isDark: true
    )
    static let black = KeyboardTheme(
        background: rgb(0x000000), key: rgb(0x1C1C1E), keyPressed: rgb(0x343437),
        special: rgb(0x0F0F10), specialPressed: rgb(0x2A2A2D), text: .white,
        subText: rgb(0x8E8E93), accent: rgb(0x34A7DB), accentPressed: rgb(0x2B8DBA),
        onAccent: .black, shadow: .clear, popup: rgb(0x2C2C2F),
        divider: rgb(0x222224), isDark: true
    )
    static let blue = KeyboardTheme(
        background: rgb(0x0D2742), key: rgb(0x1A3B5E), keyPressed: rgb(0x2A5380),
        special: rgb(0x13304F), specialPressed: rgb(0x234870), text: .white,
        subText: rgb(0xA6C0DD), accent: rgb(0x1EB53A), accentPressed: rgb(0x189A31),
        onAccent: .white, shadow: rgb(0x000000, alpha: 0.33), popup: rgb(0x24496F),
        divider: rgb(0x1D3A5A), isDark: true
    )

    static func resolve(_ choice: ThemeChoice, traits: UITraitCollection) -> KeyboardTheme {
        switch choice {
        case .light: return light
        case .dark: return dark
        case .black: return black
        case .blue: return blue
        case .system: return traits.userInterfaceStyle == .dark ? dark : light
        }
    }
}
