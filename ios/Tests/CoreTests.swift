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

    func testBundledDictionariesLoad() throws {
        for id in ["uz_latn_2027", "uz_latn", "uz_cyrl", "ru", "en"] {
            let url = try XCTUnwrap(SharedResources.url("dictionaries/\(id).txt"))
            let dictionary = WordDictionary.load(url: url, normalizer: .forDictionary(id))
            XCTAssertGreaterThan(dictionary.count, 1000, id)
        }
    }
}
