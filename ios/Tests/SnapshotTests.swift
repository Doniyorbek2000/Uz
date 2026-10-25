import UIKit
import XCTest

/// Renders the keyboard in several layouts and themes. With TEST_RUNNER_SNAPSHOT_DIR set,
/// the PNGs are written there (CI publishes them to docs/screenshots).
final class SnapshotTests: XCTestCase {

    private func layout(_ id: String) throws -> LayoutSpec {
        try LayoutParser.parse(try XCTUnwrap(SharedResources.data("layouts/\(id).json")))
    }

    private func render(_ id: String, theme: KeyboardTheme, letters: Bool = true, shift: ShiftState = .off,
                        suggestions: Suggestions = .none, name: String) throws {
        let view = KeyboardView(frame: .zero)
        let spec = try layout(letters ? id : LayoutIds.symbols)
        let options = BuildOptions(numberRow: false, numberHints: true, fieldKind: .text, showLanguageKey: true, showGlobeKey: true)
        view.theme = theme
        view.isLetterLayer = letters
        view.spaceLabel = try layout(id).spaceLabel
        view.setKeys(KeyboardBuilder.build(spec, isLetterLayer: letters, options: options))
        view.shiftState = shift
        view.frame = CGRect(x: 0, y: 0, width: 390, height: view.preferredHeight)
        view.layoutIfNeeded()
        view.setSuggestions(suggestions, convertAvailable: id.hasPrefix("uz"))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        let image = UIGraphicsImageRenderer(bounds: view.bounds, format: format).image { _ in view.draw(view.bounds) }
        XCTAssertGreaterThan(image.size.height, 200)
        guard let directory = ProcessInfo.processInfo.environment["SNAPSHOT_DIR"], let data = image.pngData() else { return }
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
}
