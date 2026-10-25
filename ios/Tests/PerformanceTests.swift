import Darwin
import XCTest

/// Guards the budgets that matter inside a keyboard extension: iOS terminates extensions that exceed
/// their memory limit (roughly 48–70 MB depending on the device), and every keystroke runs a suggestion query.
final class PerformanceTests: XCTestCase {

    /// Physical footprint of this process — the figure the system's extension memory limit is applied to.
    private func footprint() -> Int {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? Int(info.phys_footprint) : 0
    }

    private func loadDictionary(_ id: String) throws -> WordDictionary {
        let url = try XCTUnwrap(SharedResources.url("dictionaries/\(id).txt"), "missing dictionary \(id)")
        return WordDictionary.load(url: url, normalizer: KeyNormalizer.forDictionary(id))
    }

    private func megabytes(_ bytes: Int) -> String { String(format: "%.1f MB", Double(bytes) / 1_048_576) }

    func testDictionaryMemoryFitsExtensionBudget() throws {
        var total = 0
        for dictionaryId in LayoutIds.languages {
            let before = footprint()
            let dictionary = try loadDictionary(dictionaryId)
            let after = footprint()
            let cost = max(0, after - before)
            total += cost
            print("dictionary \(dictionaryId): \(dictionary.count) words, footprint +\(megabytes(cost))")
            XCTAssertGreaterThan(dictionary.count, 900, "\(dictionaryId) is suspiciously small")
            XCTAssertLessThan(cost, 16 * 1_048_576, "\(dictionaryId) uses \(megabytes(cost))")
            withExtendedLifetime(dictionary) {}
        }
        print("all dictionaries loaded one at a time: \(megabytes(total)) in total")
    }

    func testSuggestionLatency() throws {
        for id in [LayoutIds.uzLatn2027, LayoutIds.uzLatn, LayoutIds.uzCyrl, LayoutIds.ru, LayoutIds.en] {
            let engine = SuggestionEngine(dictionary: try loadDictionary(id), user: UserDictionary(normalizer: KeyNormalizer.forDictionary(id)),
                                          normalizer: KeyNormalizer.forDictionary(id))
            let inputs: [String]
            switch id {
            case LayoutIds.uzCyrl: inputs = ["с", "са", "сал", "салом", "китоб", "китпб", "ўзбекистонн"]
            case LayoutIds.ru: inputs = ["п", "пр", "при", "привет", "превет", "спосибо"]
            case LayoutIds.en: inputs = ["t", "th", "the", "helo", "becuase", "keyboard"]
            case LayoutIds.uzLatn2027: inputs = ["s", "sa", "sal", "salom", "kiotb", "özbekistonn", "yaxsh"]
            default: inputs = ["s", "sa", "sal", "salom", "kiotb", "oʻzbekistonn", "yaxsh"]
            }
            _ = engine.suggest(inputs[0], previousWord: nil)
            let rounds = 5
            let start = DispatchTime.now().uptimeNanoseconds
            for _ in 0..<rounds {
                for input in inputs { _ = engine.suggest(input, previousWord: "bu") }
            }
            let perQuery = Double(DispatchTime.now().uptimeNanoseconds - start) / Double(rounds * inputs.count) / 1_000_000
            print(String(format: "suggest latency %@: %.2f ms per keystroke", id, perQuery))
            // Simulator hardware is several times faster than an older iPhone; 12 ms leaves room for a 60 Hz frame there.
            XCTAssertLessThan(perQuery, 12, "\(id) suggestions take \(perQuery) ms")
        }
    }

    func testDictionaryLoadTime() throws {
        for id in LayoutIds.languages {
            let start = DispatchTime.now().uptimeNanoseconds
            let dictionary = try loadDictionary(id)
            let milliseconds = Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
            print(String(format: "load %@: %d words in %.0f ms", id, dictionary.count, milliseconds))
            XCTAssertLessThan(milliseconds, 1_500, "\(id) takes \(milliseconds) ms to load")
        }
    }
}
