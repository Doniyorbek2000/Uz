import UIKit

protocol EmojiPanelDelegate: AnyObject {
    func emojiSelected(_ emoji: String)
    func emojiBackspace()
    func emojiSpace()
    func emojiClose()
    func emojiFeedback()
}

private final class EmojiCell: UICollectionViewCell {
    static let reuseId = "EmojiCell"
    let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        label.font = .systemFont(ofSize: 30)
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            label.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            label.topAnchor.constraint(equalTo: contentView.topAnchor),
            label.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
        ])
        selectedBackgroundView = UIView()
        selectedBackgroundView?.layer.cornerRadius = 8
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }
}

/// Scrollable emoji grid with category tabs and a bottom bar (ABC, space, backspace).
final class EmojiPanelView: UIView, UICollectionViewDataSource, UICollectionViewDelegate, UICollectionViewDelegateFlowLayout {

    weak var delegate: EmojiPanelDelegate?

    private var categories: [EmojiCategory] = []
    private let tabStack = UIStackView()
    private var tabButtons: [UIButton] = []
    private let collectionView: UICollectionView
    private let abcButton = UIButton(type: .custom)
    private let spaceButton = UIButton(type: .custom)
    private let backspaceButton = UIButton(type: .custom)
    private var repeatTimer: Timer?
    private var activeSection = 0

    var theme: KeyboardTheme = .light {
        didSet { applyTheme() }
    }

    override init(frame: CGRect) {
        let layout = UICollectionViewFlowLayout()
        layout.itemSize = CGSize(width: 44, height: 44)
        layout.minimumInteritemSpacing = 0
        layout.minimumLineSpacing = 2
        layout.sectionInset = UIEdgeInsets(top: 4, left: 4, bottom: 8, right: 4)
        collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        super.init(frame: frame)
        setUp()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    private func setUp() {
        tabStack.axis = .horizontal
        tabStack.distribution = .fillEqually
        tabStack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(tabStack)

        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.backgroundColor = .clear
        collectionView.showsVerticalScrollIndicator = false
        collectionView.register(EmojiCell.self, forCellWithReuseIdentifier: EmojiCell.reuseId)
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(collectionView)

        let bar = UIStackView(arrangedSubviews: [abcButton, spaceButton, backspaceButton])
        bar.axis = .horizontal
        bar.spacing = 8
        bar.translatesAutoresizingMaskIntoConstraints = false
        addSubview(bar)

        abcButton.setTitle("ABC", for: .normal)
        abcButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .medium)
        abcButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        spaceButton.addTarget(self, action: #selector(spaceTapped), for: .touchUpInside)
        backspaceButton.setTitle("⌫", for: .normal)
        backspaceButton.titleLabel?.font = .systemFont(ofSize: 20)
        backspaceButton.addTarget(self, action: #selector(backspaceDown), for: .touchDown)
        backspaceButton.addTarget(self, action: #selector(backspaceUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        [abcButton, spaceButton, backspaceButton].forEach { $0.layer.cornerRadius = 6 }

        NSLayoutConstraint.activate([
            tabStack.topAnchor.constraint(equalTo: topAnchor),
            tabStack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 4),
            tabStack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -4),
            tabStack.heightAnchor.constraint(equalToConstant: 40),
            collectionView.topAnchor.constraint(equalTo: tabStack.bottomAnchor),
            collectionView.leadingAnchor.constraint(equalTo: leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: trailingAnchor),
            collectionView.bottomAnchor.constraint(equalTo: bar.topAnchor, constant: -4),
            bar.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 6),
            bar.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -6),
            bar.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -6),
            bar.heightAnchor.constraint(equalToConstant: 42),
            abcButton.widthAnchor.constraint(equalTo: bar.widthAnchor, multiplier: 0.18),
            backspaceButton.widthAnchor.constraint(equalTo: bar.widthAnchor, multiplier: 0.18),
        ])
        applyTheme()
    }

    func setCategories(recent: [String], all: [EmojiCategory]) {
        var list: [EmojiCategory] = []
        if !recent.isEmpty { list.append(EmojiCategory(id: EmojiData.recentId, icon: EmojiData.recentIcon, emojis: recent)) }
        list.append(contentsOf: all)
        categories = list
        tabButtons.forEach { $0.removeFromSuperview() }
        tabButtons = categories.enumerated().map { index, category in
            let button = UIButton(type: .custom)
            button.setTitle(category.icon, for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 20)
            button.tag = index
            button.addTarget(self, action: #selector(tabTapped(_:)), for: .touchUpInside)
            tabStack.addArrangedSubview(button)
            return button
        }
        collectionView.reloadData()
        collectionView.setContentOffset(.zero, animated: false)
        activeSection = 0
        updateTabs()
    }

    private func applyTheme() {
        backgroundColor = theme.background
        abcButton.backgroundColor = theme.special
        abcButton.setTitleColor(theme.text, for: .normal)
        spaceButton.backgroundColor = theme.key
        backspaceButton.backgroundColor = theme.special
        backspaceButton.setTitleColor(theme.text, for: .normal)
        updateTabs()
        collectionView.reloadData()
    }

    private func updateTabs() {
        for (index, button) in tabButtons.enumerated() {
            button.alpha = index == activeSection ? 1 : 0.45
            button.backgroundColor = index == activeSection ? theme.keyPressed.withAlphaComponent(0.6) : .clear
            button.layer.cornerRadius = 8
        }
    }

    @objc private func tabTapped(_ sender: UIButton) {
        delegate?.emojiFeedback()
        let section = sender.tag
        guard section < categories.count, !categories[section].emojis.isEmpty else { return }
        collectionView.scrollToItem(at: IndexPath(item: 0, section: section), at: .top, animated: false)
        activeSection = section
        updateTabs()
    }

    @objc private func closeTapped() {
        delegate?.emojiFeedback()
        delegate?.emojiClose()
    }

    @objc private func spaceTapped() {
        delegate?.emojiFeedback()
        delegate?.emojiSpace()
    }

    @objc private func backspaceDown() {
        delegate?.emojiFeedback()
        delegate?.emojiBackspace()
        repeatTimer?.invalidate()
        repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in
            self?.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.06, repeats: true) { [weak self] _ in
                self?.delegate?.emojiBackspace()
            }
        }
    }

    @objc private func backspaceUp() {
        repeatTimer?.invalidate()
        repeatTimer = nil
    }

    // MARK: Collection view

    func numberOfSections(in collectionView: UICollectionView) -> Int { categories.count }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        categories[section].emojis.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: EmojiCell.reuseId, for: indexPath)
        if let emojiCell = cell as? EmojiCell {
            emojiCell.label.text = categories[indexPath.section].emojis[indexPath.item]
            emojiCell.selectedBackgroundView?.backgroundColor = theme.keyPressed
        }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        let available = collectionView.bounds.width - 8
        let columns = max(6, floor(available / 44))
        return CGSize(width: floor(available / columns), height: 44)
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        collectionView.deselectItem(at: indexPath, animated: true)
        delegate?.emojiFeedback()
        delegate?.emojiSelected(categories[indexPath.section].emojis[indexPath.item])
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        let probe = CGPoint(x: 10, y: scrollView.contentOffset.y + 30)
        if let indexPath = collectionView.indexPathForItem(at: probe), indexPath.section != activeSection {
            activeSection = indexPath.section
            updateTabs()
        }
    }

    override func removeFromSuperview() {
        repeatTimer?.invalidate()
        super.removeFromSuperview()
    }
}
