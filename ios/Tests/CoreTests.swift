import XCTest

final class CoreTests: XCTestCase {

    func testCurrentAndPreviousWord() {
        XCTAssertEqual(TextRules.currentWord("Men salom"), "salom")
        XCTAssertEqual(TextRules.currentWord("Men salom "), "")
        XCTAssertEqual(TextRules.currentWord("biz oʻzbek"), "oʻzbek")
        XCTAssertEqual(TextRules.previousWord("salom, do"), "salom")
        XCTAssertNil(TextRules.previousWord("Tamom. Yan"))
    }

    func testSentenceStart() {
        XCTAssertTrue(TextRules.isSentenceStart(""))
        XCTAssertTrue(TextRules.isSentenceStart("Salom. "))
        XCTAssertFalse(TextRules.isSentenceStart("Salom "))
        XCTAssertFalse(TextRules.isSentenceStart("Salom."))
    }

    func testApostropheReplacement() {
        XCTAssertEqual(TextRules.apostropheReplacement(layoutId: LayoutIds.uzLatn2027, previous: "o", typed: "'"), "ö")
        XCTAssertEqual(TextRules.apostropheReplacement(layoutId: LayoutIds.uzLatn, previous: "g", typed: "'"), "gʻ")
        XCTAssertNil(TextRules.apostropheReplacement(layoutId: LayoutIds.uzLatn2027, previous: "o", typed: "ʼ"))
    }

    func testTransliteration() {
        XCTAssertEqual(UzbekTransliterator.convert("Oʻzbekiston chiroyli shahar", to: .latin2027), "Özbekiston çiroyli şahar")
        XCTAssertEqual(UzbekTransliterator.convert("Özbekiston çiroyli şahar", to: .latin), "Oʻzbekiston chiroyli shahar")
        XCTAssertEqual(UzbekTransliterator.convert("Ўзбекистон", to: .latin2027), "Özbekiston")
        XCTAssertEqual(UzbekTransliterator.convert("маъно", to: .latin), "maʼno")
        XCTAssertEqual(UzbekTransliterator.convert("ШАҲАР", to: .latin2027), "ŞAHAR")
        XCTAssertEqual(UzbekTransliterator.convert("yo'l", to: .cyrillic), "йўл")
        XCTAssertEqual(UzbekTransliterator.convert("ertaga", to: .cyrillic), "эртага")
        XCTAssertEqual(UzbekTransliterator.convert("revolyutsiya", to: .cyrillic), "революция")
        XCTAssertEqual(UzbekTransliterator.convert("informatsion", to: .cyrillic), "информацион")
        XCTAssertEqual(UzbekTransliterator.convert("pensiya", to: .cyrillic), "пенсия")
        XCTAssertEqual(UzbekTransliterator.convert("Salom, dunyo! 2027-yil: Hello.", to: .cyrillic), "Салом, дунё! 2027-йил: Ҳелло.")
    }

    func testLayoutsParseAndBuild() throws {
        for id in LayoutIds.languages + [LayoutIds.symbols, LayoutIds.symbolsMore, LayoutIds.numpad] {
            let data = try XCTUnwrap(SharedResources.data("layouts/\(id).json"), "missing \(id)")
            let layout = try LayoutParser.parse(data)
            XCTAssertEqual(layout.id, id)
            XCTAssertFalse(layout.rows.isEmpty)
        }
        let en = try LayoutParser.parse(try XCTUnwrap(SharedResources.data("layouts/en.json")))
        let options = BuildOptions(numberRow: false, numberHints: true, fieldKind: .text, showLanguageKey: true, showGlobeKey: true)
        let rows = KeyboardBuilder.build(en, isLetterLayer: true, options: options)
        XCTAssertEqual(rows.count, 4)
        XCTAssertEqual(rows[0][0].hint, "1")
        XCTAssertTrue(rows.last!.contains { $0.type == .globe })
        XCTAssertTrue(rows.last!.contains { $0.type == .space })
    }

    func testSuggestions() {
        let dictionary = WordDictionary.load(text: "salom\t220\nsalomatlik\t180\nkitob\t185\nyaxshi\t210\noʻzbek\t200\n")
        let engine = SuggestionEngine(dictionary: dictionary, user: UserDictionary())
        XCTAssertEqual(engine.suggest("Sal", previousWord: nil).candidates.first, "Salom")
        XCTAssertEqual(engine.suggest("o'zb", previousWord: nil).candidates.first, "oʻzbek")
        XCTAssertTrue(engine.suggest("kiotb", previousWord: nil).candidates.contains("kitob"))
        XCTAssertEqual(engine.autocorrection("yaxsho", previousWord: nil), "yaxshi")
        XCTAssertNil(engine.autocorrection("salo", previousWord: nil))
    }

    func testNewAlphabetAcceptsOldSpellings() {
        let dictionary = WordDictionary.load(text: "yaxşi\t210\nşahar\t190\n", normalizer: .latin2027)
        let engine = SuggestionEngine(dictionary: dictionary, user: UserDictionary(normalizer: .latin2027), normalizer: .latin2027)
        XCTAssertEqual(engine.suggest("yaxsh", previousWord: nil).candidates.first, "yaxşi")
    }

    func testLearningAndPersistence() throws {
        let user = UserDictionary()
        let dictionary = WordDictionary.load(text: "salom\t220\n")
        let engine = SuggestionEngine(dictionary: dictionary, user: user)
        user.learn(previous: "salom", word: "Doniyor")
        user.learn(previous: "salom", word: "Doniyor")
        XCTAssertTrue(engine.suggest("Don", previousWord: nil).candidates.contains("Doniyor"))
        XCTAssertEqual(engine.predictNext(after: "salom"), ["Doniyor"])
        let restored = UserDictionary()
        try restored.restore(try user.serialize())
        XCTAssertEqual(restored.count("doniyor"), 2)
    }

    func testDistances() {
        XCTAssertEqual(SuggestionEngine.boundedDistance("kiotb", "kitob", maxDistance: 2), 1)
        XCTAssertEqual(SuggestionEngine.distanceUpToOne(Array("helo".utf16), Array("hello".utf16), 5), 1)
        XCTAssertEqual(SuggestionEngine.distanceUpToOne(Array("abcd".utf16), Array("badc".utf16), 4), 2)
    }

    func testGestureDecoding() {
        let keyWidth: CGFloat = 33
        var centers: [String: CGPoint] = [:]
        for (row, letters) in ["qwertyuiop", "asdfghjkl", "zxcvbnm"].enumerated() {
            let offset = CGFloat(11 - letters.count) * keyWidth / 2
            for (i, c) in letters.enumerated() {
                centers[String(c)] = CGPoint(x: offset + CGFloat(i) * keyWidth + keyWidth / 2, y: CGFloat(row) * 54 + 27)
            }
        }
        let dictionary = WordDictionary.load(text: "hello\t200\nhelp\t190\nthe\t220\ntest\t180\ntext\t170\n")
        let decoder = GestureDecoder(dictionary: dictionary, normalizer: .standard, keyCenters: centers, keyWidth: keyWidth)
        func path(_ word: String) -> [CGPoint] {
            let stops = word.compactMap { centers[String($0)] }
            var points: [CGPoint] = []
            for i in 1..<stops.count {
                for step in 0..<10 {
                    let t = CGFloat(step) / 10
                    let wobble = 6 * sin(t * 6.283 + CGFloat(i))
                    points.append(CGPoint(x: stops[i - 1].x + (stops[i].x - stops[i - 1].x) * t + wobble, y: stops[i - 1].y + (stops[i].y - stops[i - 1].y) * t + wobble))
                }
            }
            points.append(stops[stops.count - 1])
            return points
        }
        XCTAssertEqual(decoder.decode(path("helo")).first?.word, "hello")
        XCTAssertEqual(decoder.decode(path("test")).first?.word, "test")
        XCTAssertEqual(decoder.decode(path("the")).first?.word, "the")
        XCTAssertEqual(decoder.keySequence("hello"), ["h", "e", "l", "o"])
    }

    func testClipboardHistory() {
        let defaults = UserDefaults(suiteName: "uzkey-tests-\(UUID().uuidString)")!
        let history = ClipboardHistory(defaults: defaults)
        history.add("first")
        history.add("second")
        history.togglePin("first")
        XCTAssertEqual(history.items.map(\.text), ["first", "second"])
        history.clearUnpinned()
        XCTAssertEqual(history.items.map(\.text), ["first"])
    }

    func testBundledDictionariesLoad() throws {
        for id in ["uz_latn_2027", "uz_latn", "uz_cyrl", "ru", "en"] {
            let url = try XCTUnwrap(SharedResources.url("dictionaries/\(id).txt"))
            let dictionary = WordDictionary.load(url: url, normalizer: .forDictionary(id))
            XCTAssertGreaterThan(dictionary.count, 1000, id)
        }
    }
}
