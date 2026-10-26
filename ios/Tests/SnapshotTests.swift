import UIKit
import XCTest

/// Renders the keyboard in several layouts and themes. With TEST_RUNNER_SNAPSHOT_DIR set,
/// the PNGs are written there (CI publishes them to docs/screenshots).
final class SnapshotTests: XCTestCase {

    private func layout(_ id: String) throws -> LayoutSpec {
        try LayoutParser.parse(try XCTUnwrap(SharedResources.data("layouts/\(id).json")))
    }

    private func render(_ id: String, theme: KeyboardTheme, letters: Bool = true, shift: ShiftState = .off,
                        suggestions: Suggestions = .none, width: CGFloat = 390, scale: CGFloat = 2,
                        directoryKey: String = "SNAPSHOT_DIR", name: String) throws {
        let view = KeyboardView(frame: .zero)
        let spec = try layout(letters ? id : LayoutIds.symbols)
        let options = BuildOptions(numberRow: false, numberHints: true, fieldKind: .text, showLanguageKey: true, showGlobeKey: true)
        view.theme = theme
        view.isLetterLayer = letters
        view.spaceLabel = try layout(id).spaceLabel
        view.languageLabel = try layout(id).shortLabel
        view.setKeys(KeyboardBuilder.build(spec, isLetterLayer: letters, options: options))
        view.shiftState = shift
        view.frame = CGRect(x: 0, y: 0, width: width, height: view.preferredHeight)
        view.layoutIfNeeded()
        view.setSuggestions(suggestions, convertAvailable: id.hasPrefix("uz"))
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        let image = UIGraphicsImageRenderer(bounds: view.bounds, format: format).image { _ in view.draw(view.bounds) }
        XCTAssertGreaterThan(image.size.height, 200)
        guard let directory = ProcessInfo.processInfo.environment[directoryKey], let data = image.pngData() else { return }
        let url = URL(fileURLWithPath: directory).appendingPathComponent("ios-\(name).png")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url)
    }

    func testRenderKeyboards() throws {
        try render(LayoutIds.uzLatn2027, theme: .light, shift: .once,
                   suggestions: Suggestions(typed: "Özb", candidates: ["Özbekiston", "özbek", "özbekça"], typedIsKnown: false),
                   name: "uz-2027-light")
        try render(LayoutIds.uzLatn, theme: .dark, name: "uz-latn-dark")
        try render(LayoutIds.uzCyrl, theme: .blue, name: "uz-cyrl-blue")
        try render(LayoutIds.ru, theme: .light,
                   suggestions: Suggestions(typed: "прив", candidates: ["привет", "приветствую", "привычка"], typedIsKnown: false),
                   name: "ru-light")
        try render(LayoutIds.en, theme: .black, name: "en-black")
        try render(LayoutIds.en, theme: .light, letters: false, name: "symbols-light")
    }

    /// Keyboards at App Store screenshot widths: iPhone 6.9" (440 pt @3x) and iPad 13" (1032 pt @2x).
    /// Written only when STORE_SNAPSHOT_DIR is set (the iOS store screenshots workflow composes them onto app screens).
    func testRenderStoreKeyboards() throws {
        guard ProcessInfo.processInfo.environment["STORE_SNAPSHOT_DIR"] != nil else { throw XCTSkip("STORE_SNAPSHOT_DIR not set") }
        for (device, width, scale) in [("iphone", CGFloat(440), CGFloat(3)), ("ipad", CGFloat(1032), CGFloat(2))] {
            try render(LayoutIds.uzLatn2027, theme: .light, shift: .once,
                       suggestions: Suggestions(typed: "Özb", candidates: ["Özbekiston", "özbek", "özbekça"], typedIsKnown: false),
                       width: width, scale: scale, directoryKey: "STORE_SNAPSHOT_DIR", name: "\(device)-1-uz-2027")
            try render(LayoutIds.uzCyrl, theme: .light, width: width, scale: scale,
                       directoryKey: "STORE_SNAPSHOT_DIR", name: "\(device)-2-uz-cyrl")
            try render(LayoutIds.ru, theme: .light,
                       suggestions: Suggestions(typed: "прив", candidates: ["привет", "приветствую", "привычка"], typedIsKnown: false),
                       width: width, scale: scale, directoryKey: "STORE_SNAPSHOT_DIR", name: "\(device)-3-ru")
            try render(LayoutIds.uzLatn, theme: .dark, width: width, scale: scale,
                       directoryKey: "STORE_SNAPSHOT_DIR", name: "\(device)-4-uz-latn-dark")
        }
    }
}
