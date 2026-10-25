import XCTest

final class SoundTests: XCTestCase {

    func testManifestListsPacksWithAllFiles() throws {
        let packs = SoundPacks.load()
        XCTAssertGreaterThanOrEqual(packs.count, 5)
        XCTAssertTrue(packs.contains { $0.id == SoundPacks.defaultPack })
        for pack in packs {
            let names = (1...pack.keys).map { "key\($0)" } + ["delete", "space", "enter"]
            for name in names {
                XCTAssertNotNil(SharedResources.url("sounds/\(pack.id)/\(name).wav"), "missing \(pack.id)/\(name).wav")
            }
        }
    }

    func testMusicalPacksGiveEachKeyAFixedNote() throws {
        let marimba = try XCTUnwrap(SoundPacks.load().first { $0.id == "marimba" })
        XCTAssertTrue(marimba.variantByKey)
        XCTAssertEqual(SoundPacks.keyVariant(marimba, label: "a") { 0 }, SoundPacks.keyVariant(marimba, label: "A") { 5 })
        let notes = Set("salom".map { SoundPacks.keyVariant(marimba, label: String($0)) { 0 } })
        XCTAssertGreaterThan(notes.count, 1)
    }

    func testRandomPacksStayInRange() throws {
        let mechanical = try XCTUnwrap(SoundPacks.load().first { $0.id == "mechanical" })
        for value in [Int.min + 1, -7, 0, 1, Int.max] {
            XCTAssertTrue((0..<mechanical.keys).contains(SoundPacks.keyVariant(mechanical, label: "a") { value }))
        }
    }

    func testPlayerHandlesEveryPackAndKey() {
        let player = KeySoundPlayer()
        XCTAssertFalse(player.play(packId: SoundPacks.system, type: .char, label: "a", volume: 0.5))
        XCTAssertFalse(player.play(packId: "no-such-pack", type: .char, label: "a", volume: 0.5))
        // Whether audio output is available depends on the machine; the calls must simply not fail.
        for id in player.packIds {
            for type in [KeyType.char, .backspace, .space, .enter, .shift] {
                _ = player.play(packId: id, type: type, label: "s", volume: 0.1)
            }
        }
        player.release()
    }
}
