import UIKit

/// Recently copied texts, kept in the extension's own storage. Unpinned items expire after a day.
final class ClipboardHistory {
    struct Item: Codable, Equatable {
        let text: String
        let time: Double
        var pinned: Bool
    }

    private static let key = "clipboard_items"
    private static let maxItems = 25
    private static let maxLength = 5_000
    private static let maxAge: Double = 24 * 60 * 60

    private let defaults: UserDefaults
    private var storage: [Item]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.key), let decoded = try? JSONDecoder().decode([Item].self, from: data) {
            storage = decoded
        } else {
            storage = []
        }
    }

    var items: [Item] {
        let now = Date().timeIntervalSince1970
        let before = storage.count
        storage.removeAll { !$0.pinned && now - $0.time > Self.maxAge }
        if storage.count != before { save() }
        return storage.sorted { a, b in a.pinned != b.pinned ? a.pinned : a.time > b.time }
    }

    func add(_ text: String) {
        let trimmed = String(text.prefix(Self.maxLength))
        guard !trimmed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let pinned = storage.first { $0.text == trimmed }?.pinned ?? false
        storage.removeAll { $0.text == trimmed }
        storage.append(Item(text: trimmed, time: Date().timeIntervalSince1970, pinned: pinned))
        let unpinned = storage.filter { !$0.pinned }.sorted { $0.time < $1.time }
        if unpinned.count > Self.maxItems {
            let drop = Set(unpinned.prefix(unpinned.count - Self.maxItems).map(\.text))
            storage.removeAll { drop.contains($0.text) }
        }
        save()
    }

    func remove(_ text: String) {
        storage.removeAll { $0.text == text }
        save()
    }

    func togglePin(_ text: String) {
        guard let index = storage.firstIndex(where: { $0.text == text }) else { return }
        storage[index].pinned.toggle()
        save()
    }

    func clearUnpinned() {
        storage.removeAll { !$0.pinned }
        save()
    }

    func clearAll() {
        storage.removeAll()
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(storage) { defaults.set(data, forKey: Self.key) }
    }
}

protocol ClipboardPanelDelegate: AnyObject {
    func clipPaste(_ text: String)
    func clipRemove(_ text: String)
    func clipTogglePin(_ text: String)
    func clipClear()
    func clipClose()
    func clipFeedback()
}

final class ClipboardPanelView: UIView {

    weak var delegate: ClipboardPanelDelegate?

    var theme: KeyboardTheme = .light { didSet { applyTheme() } }

    private let titleLabel = UILabel()
    private let clearButton = UIButton(type: .custom)
    private let closeButton = UIButton(type: .custom)
    private let scrollView = UIScrollView()
    private let stack = UIStackView()
    private let emptyLabel = UILabel()
    private var items: [ClipboardHistory.Item] = []
    private var message: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        titleLabel.text = KeyboardStrings.clipboard
        titleLabel.font = .systemFont(ofSize: 16, weight: .semibold)
        clearButton.setTitle(KeyboardStrings.clear, for: .normal)
        closeButton.setTitle("ABC", for: .normal)
        for button in [clearButton, closeButton] {
            button.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
            button.layer.cornerRadius = 8
            button.contentEdgeInsets = UIEdgeInsets(top: 6, left: 12, bottom: 6, right: 12)
        }
        clearButton.addTarget(self, action: #selector(clearTapped), for: .touchUpInside)
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)

        let header = UIStackView(arrangedSubviews: [titleLabel, UIView(), clearButton, closeButton])
        header.axis = .horizontal
        header.spacing = 8
        header.alignment = .center
        header.translatesAutoresizingMaskIntoConstraints = false
        addSubview(header)

        stack.axis = .vertical
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(stack)
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scrollView)

        emptyLabel.numberOfLines = 0
        emptyLabel.textAlignment = .center
        emptyLabel.font = .systemFont(ofSize: 14)
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(emptyLabel)

        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: topAnchor, constant: 6),
            header.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            header.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -8),
            header.heightAnchor.constraint(equalToConstant: 36),
            scrollView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 6),
            scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            stack.leadingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.leadingAnchor, constant: 8),
            stack.trailingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.trailingAnchor, constant: -8),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -8),
            emptyLabel.centerYAnchor.constraint(equalTo: scrollView.centerYAnchor),
            emptyLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 24),
            emptyLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -24),
        ])
        applyTheme()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    func setItems(_ newItems: [ClipboardHistory.Item], message: String? = nil) {
        items = newItems
        self.message = message
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for item in items { stack.addArrangedSubview(row(for: item)) }
        emptyLabel.text = message ?? KeyboardStrings.clipboardEmpty
        emptyLabel.isHidden = !items.isEmpty
        scrollView.setContentOffset(.zero, animated: false)
    }

    private func row(for item: ClipboardHistory.Item) -> UIView {
        let container = UIView()
        container.backgroundColor = theme.key
        container.layer.cornerRadius = 10

        let label = UILabel()
        label.text = (item.pinned ? "📌 " : "") + item.text.replacingOccurrences(of: "\n", with: " ")
        label.numberOfLines = 2
        label.font = .systemFont(ofSize: 15)
        label.textColor = theme.text
        label.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(label)

        let remove = UIButton(type: .custom)
        remove.setTitle("✕", for: .normal)
        remove.setTitleColor(theme.subText, for: .normal)
        remove.accessibilityLabel = KeyboardStrings.delete
        remove.translatesAutoresizingMaskIntoConstraints = false
        remove.addAction(UIAction { [weak self] _ in
            self?.delegate?.clipFeedback()
            self?.delegate?.clipRemove(item.text)
        }, for: .touchUpInside)
        container.addSubview(remove)

        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 12),
            label.topAnchor.constraint(equalTo: container.topAnchor, constant: 10),
            label.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -10),
            remove.leadingAnchor.constraint(equalTo: label.trailingAnchor, constant: 4),
            remove.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -4),
            remove.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            remove.widthAnchor.constraint(equalToConstant: 40),
            remove.heightAnchor.constraint(equalToConstant: 40),
        ])

        let tap = UITapGestureRecognizer(target: self, action: #selector(rowTapped(_:)))
        let hold = UILongPressGestureRecognizer(target: self, action: #selector(rowHeld(_:)))
        container.addGestureRecognizer(tap)
        container.addGestureRecognizer(hold)
        container.accessibilityLabel = item.text
        container.isAccessibilityElement = true
        container.accessibilityTraits = .button
        container.tag = items.firstIndex(of: item) ?? 0
        return container
    }

    @objc private func rowTapped(_ recognizer: UITapGestureRecognizer) {
        guard let index = recognizer.view?.tag, index < items.count else { return }
        delegate?.clipFeedback()
        delegate?.clipPaste(items[index].text)
    }

    @objc private func rowHeld(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began, let index = recognizer.view?.tag, index < items.count else { return }
        delegate?.clipFeedback()
        delegate?.clipTogglePin(items[index].text)
    }

    @objc private func clearTapped() {
        delegate?.clipFeedback()
        delegate?.clipClear()
    }

    @objc private func closeTapped() {
        delegate?.clipFeedback()
        delegate?.clipClose()
    }

    private func applyTheme() {
        backgroundColor = theme.background
        titleLabel.textColor = theme.text
        emptyLabel.textColor = theme.subText
        for button in [clearButton, closeButton] {
            button.backgroundColor = theme.special
            button.setTitleColor(theme.text, for: .normal)
        }
        setItems(items, message: message)
    }
}
