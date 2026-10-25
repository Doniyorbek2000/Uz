import UIKit

enum ShiftState {
    case off, once, locked
}

enum ToolbarAction {
    case emoji, clipboard, convert, settings, hide
}

protocol KeyboardViewDelegate: AnyObject {
    func keyDown(_ key: KeySpec)
    func keyUp(_ key: KeySpec)
    func character(_ key: KeySpec, text: String)
    func special(_ key: KeySpec)
    func backspaceRepeat()
    func switchKeyboardRequested()
    func cursorMove(_ steps: Int)
    func suggestionPicked(_ text: String)
    func toolbarAction(_ action: ToolbarAction)
    func gesture(_ path: [CGPoint])
}

/// Draws the suggestion strip and the key grid and turns touches into keyboard events.
final class KeyboardView: UIView {

    private final class PlacedKey {
        let spec: KeySpec
        let touch: CGRect
        let visual: CGRect

        init(spec: KeySpec, touch: CGRect, visual: CGRect) {
            self.spec = spec
            self.touch = touch
            self.visual = visual
        }
    }

    private struct AlternatesPopup {
        let options: [String]
        let bounds: CGRect
        let columns: Int
        let optionWidth: CGFloat
        let optionHeight: CGFloat
        var selected: Int
    }

    private final class Pointer {
        var key: PlacedKey?
        let downPoint: CGPoint
        var consumed = false
        var longPressed = false
        var popup: AlternatesPopup?
        var cursorMode = false
        var cursorAnchorX: CGFloat
        var stripTarget = -1
        var longPressWork: DispatchWorkItem?
        var repeatTimer: Timer?
        var repeatCount = 0
        var gesture = false
        var path: [CGPoint] = []

        init(key: PlacedKey?, downPoint: CGPoint) {
            self.key = key
            self.downPoint = downPoint
            self.cursorAnchorX = downPoint.x
        }

        func cancelTimers() {
            longPressWork?.cancel()
            longPressWork = nil
            repeatTimer?.invalidate()
            repeatTimer = nil
        }
    }

    weak var delegate: KeyboardViewDelegate?

    var theme: KeyboardTheme = .light { didSet { setNeedsDisplay() } }
    var shiftState: ShiftState = .off { didSet { if oldValue != shiftState { setNeedsDisplay() } } }
    var isLetterLayer = true
    var spaceLabel = ""
    var languageLabel = ""
    var enterIcon: Icon = .returnKey
    var showKeyPopup = true
    var longPressDelay: TimeInterval = 0.32
    var heightScale: CGFloat = 1 { didSet { invalidateIntrinsicContentSize() } }

    private var rows: [[KeySpec]] = []
    private var placed: [PlacedKey] = []
    private var pointers: [ObjectIdentifier: Pointer] = [:]
    private var suggestions: Suggestions = .none
    private var showConvert = false
    private var stripSlots: [String?] = [nil, nil, nil]
    private var typedSlot = -1
    private var toolbarButtons: [(ToolbarAction, CGRect)] = []

    /// Frame of the globe key so the controller can place the system input-mode button over it.
    private(set) var globeKeyFrame: CGRect?
    var onLayout: (() -> Void)?

    /// Strip and row heights before the user's size preference; set per orientation and device.
    var stripHeight: CGFloat = 44
    var baseRowHeight: CGFloat = 54 { didSet { invalidateIntrinsicContentSize() } }
    var gestureTyping = false
    private let topPadding: CGFloat = 2
    private let bottomPadding: CGFloat = 4
    private let sidePadding: CGFloat = 3
    private let keyInsetX: CGFloat = 3
    private let keyInsetY: CGFloat = 5
    private let cornerRadius: CGFloat = 6
    private var rowHeight: CGFloat { baseRowHeight * heightScale }

    var preferredHeight: CGFloat {
        stripHeight + topPadding + CGFloat(rows.count) * rowHeight + bottomPadding
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        isOpaque = true
        contentMode = .redraw
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: preferredHeight)
    }

    func setKeys(_ newRows: [[KeySpec]]) {
        cancelAllPointers()
        let heightChanged = newRows.count != rows.count
        rows = newRows
        if heightChanged { invalidateIntrinsicContentSize() }
        relayout()
        setNeedsDisplay()
        accessibilityElementsCache = nil
        UIAccessibility.post(notification: .layoutChanged, argument: nil)
    }

    func setSuggestions(_ value: Suggestions, convertAvailable: Bool) {
        suggestions = value
        showConvert = convertAvailable
        arrangeStrip()
        setNeedsDisplay()
        accessibilityElementsCache = nil
    }

    func cancelAllPointers() {
        pointers.values.forEach { $0.cancelTimers() }
        pointers.removeAll()
        setNeedsDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        relayout()
        arrangeStrip()
        setNeedsDisplay()
    }

    // MARK: Layout

    private func relayout() {
        placed.removeAll()
        globeKeyFrame = nil
        let viewWidth = bounds.width
        guard viewWidth > 0, !rows.isEmpty else { return }
        let contentWidth = viewWidth - 2 * sidePadding
        let maxUnits = rows.map { row in row.reduce(0.0) { $0 + ($1.flex ? 1 : $1.weight) } }.max() ?? 1
        let unit = contentWidth / CGFloat(maxUnits)
        var top = stripHeight + topPadding
        for (rowIndex, row) in rows.enumerated() {
            let fixedWidth = CGFloat(row.filter { !$0.flex }.reduce(0.0) { $0 + $1.weight }) * unit
            let flexCount = row.filter { $0.flex }.count
            let flexWidth = flexCount > 0 ? max(unit, (contentWidth - fixedWidth) / CGFloat(flexCount)) : 0
            let usedWidth = fixedWidth + flexWidth * CGFloat(flexCount)
            var x = sidePadding + max(0, (contentWidth - usedWidth) / 2)
            let isLastRow = rowIndex == rows.count - 1
            for (index, spec) in row.enumerated() {
                let keyWidth = spec.flex ? flexWidth : CGFloat(spec.weight) * unit
                let visual = CGRect(x: x + keyInsetX, y: top + keyInsetY, width: keyWidth - 2 * keyInsetX, height: rowHeight - 2 * keyInsetY)
                let left = index == 0 ? 0 : x
                let right = index == row.count - 1 ? viewWidth : x + keyWidth
                let bottom = isLastRow ? max(bounds.height, top + rowHeight) : top + rowHeight
                let touch = CGRect(x: left, y: top, width: right - left, height: bottom - top)
                placed.append(PlacedKey(spec: spec, touch: touch, visual: visual))
                if spec.type == .globe { globeKeyFrame = touch }
                x += keyWidth
            }
            top += rowHeight
        }
        onLayout?()
    }

    private func arrangeStrip() {
        stripSlots = [nil, nil, nil]
        typedSlot = -1
        toolbarButtons.removeAll()
        let candidates = suggestions.candidates
        let typed = suggestions.typed
        if candidates.isEmpty && typed.isEmpty {
            let size: CGFloat = 44
            let top = (stripHeight - size) / 2
            var left: CGFloat = 4
            toolbarButtons.append((.emoji, CGRect(x: left, y: top, width: size, height: size)))
            left += size
            toolbarButtons.append((.clipboard, CGRect(x: left, y: top, width: size, height: size)))
            left += size
            if showConvert { toolbarButtons.append((.convert, CGRect(x: left, y: top, width: size, height: size))) }
            let right = bounds.width - 4
            toolbarButtons.append((.hide, CGRect(x: right - size, y: top, width: size, height: size)))
            toolbarButtons.append((.settings, CGRect(x: right - 2 * size, y: top, width: size, height: size)))
            return
        }
        let showTyped = !typed.isEmpty && !suggestions.typedIsKnown && !candidates.contains(typed)
        func candidate(_ index: Int) -> String? { index < candidates.count ? candidates[index] : nil }
        if showTyped {
            stripSlots = [typed, candidate(0), candidate(1)]
            typedSlot = 0
        } else {
            stripSlots = [candidate(1), candidate(0), candidate(2)]
        }
    }

    // MARK: Drawing

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        context.setFillColor(theme.background.cgColor)
        context.fill(bounds)
        drawStrip(context)
        var pressed = Set<ObjectIdentifier>()
        for pointer in pointers.values where !pointer.consumed && !pointer.gesture && pointer.stripTarget < 0 {
            if let key = pointer.key { pressed.insert(ObjectIdentifier(key)) }
        }
        for key in placed { drawKey(context, key, pressed: pressed.contains(ObjectIdentifier(key))) }
        for pointer in pointers.values where pointer.gesture { drawGestureTrail(context, pointer.path) }
        for pointer in pointers.values {
            if let popup = pointer.popup {
                drawAlternates(context, popup)
            } else if showKeyPopup, !pointer.consumed, !pointer.cursorMode, !pointer.gesture, let key = pointer.key, key.spec.type == .char {
                drawPreview(context, key)
            }
        }
    }

    private func attributes(size: CGFloat, color: UIColor, weight: UIFont.Weight = .regular) -> [NSAttributedString.Key: Any] {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        paragraph.lineBreakMode = .byTruncatingTail
        return [.font: UIFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color, .paragraphStyle: paragraph]
    }

    private func drawText(_ text: String, in rect: CGRect, size: CGFloat, color: UIColor, weight: UIFont.Weight = .regular, minSize: CGFloat = 10) {
        var fontSize = size
        var attrs = attributes(size: fontSize, color: color, weight: weight)
        var textSize = (text as NSString).size(withAttributes: attrs)
        while textSize.width > rect.width - 4 && fontSize > minSize {
            fontSize -= 1
            attrs = attributes(size: fontSize, color: color, weight: weight)
            textSize = (text as NSString).size(withAttributes: attrs)
        }
        let drawRect = CGRect(x: rect.minX, y: rect.midY - textSize.height / 2, width: rect.width, height: textSize.height)
        (text as NSString).draw(in: drawRect, withAttributes: attrs)
    }

    private func drawStrip(_ context: CGContext) {
        if !toolbarButtons.isEmpty {
            let pressedIndex = activeStripTarget()
            for (index, (action, rect)) in toolbarButtons.enumerated() {
                if pressedIndex == index {
                    context.setFillColor(theme.keyPressed.cgColor)
                    context.addPath(UIBezierPath(roundedRect: rect.insetBy(dx: 2, dy: 2), cornerRadius: rect.height / 2).cgPath)
                    context.fillPath()
                }
                let icon: Icon
                switch action {
                case .emoji: icon = .emoji
                case .convert: icon = .convert
                case .settings: icon = .settings
                case .hide: icon = .hide
                case .clipboard: icon = .clipboard
                }
                Icons.draw(icon, in: context, center: CGPoint(x: rect.midX, y: rect.midY), size: 22, color: theme.subText)
            }
            return
        }
        let slotWidth = bounds.width / 3
        let pressedSlot = activeStripTarget()
        for slot in 0..<3 {
            guard let text = stripSlots[slot] else { continue }
            let slotRect = CGRect(x: CGFloat(slot) * slotWidth, y: 0, width: slotWidth, height: stripHeight)
            if pressedSlot == slot {
                context.setFillColor(theme.keyPressed.cgColor)
                context.addPath(UIBezierPath(roundedRect: slotRect.insetBy(dx: 4, dy: 5), cornerRadius: cornerRadius).cgPath)
                context.fillPath()
            }
            let isTyped = slot == typedSlot
            let label = isTyped ? "\u{201C}\(text)\u{201D}" : text
            drawText(label, in: slotRect.insetBy(dx: 6, dy: 0), size: 17, color: isTyped ? theme.subText : theme.text,
                     weight: slot == 1 && !isTyped ? .semibold : .regular, minSize: 12)
        }
        context.setFillColor(theme.divider.cgColor)
        for slot in 1..<3 where stripSlots[slot - 1] != nil && stripSlots[slot] != nil {
            context.fill(CGRect(x: CGFloat(slot) * slotWidth - 0.5, y: 12, width: 1, height: stripHeight - 24))
        }
    }

    private func activeStripTarget() -> Int {
        pointers.values.first(where: { $0.stripTarget >= 0 })?.stripTarget ?? -1
    }

    private func background(for spec: KeySpec, pressed: Bool) -> UIColor {
        switch spec.type {
        case .char, .space:
            return pressed ? theme.keyPressed : theme.key
        case .enter:
            return pressed ? theme.accentPressed : theme.accent
        case .shift:
            if pressed { return theme.specialPressed }
            return shiftState == .off ? theme.special : theme.key
        default:
            return pressed ? theme.specialPressed : theme.special
        }
    }

    private func displayText(_ text: String) -> String {
        isLetterLayer && shiftState != .off ? TextRules.uppercaseKeyText(text) : text
    }

    private func drawKey(_ context: CGContext, _ key: PlacedKey, pressed: Bool) {
        let rect = key.visual
        let spec = key.spec
        let path = UIBezierPath(roundedRect: rect, cornerRadius: cornerRadius)
        if theme.shadow != .clear {
            context.saveGState()
            context.translateBy(x: 0, y: 1)
            context.setFillColor(theme.shadow.cgColor)
            context.addPath(path.cgPath)
            context.fillPath()
            context.restoreGState()
        }
        context.setFillColor(background(for: spec, pressed: pressed).cgColor)
        context.addPath(path.cgPath)
        context.fillPath()

        let center = CGPoint(x: rect.midX, y: rect.midY)
        let iconSize = min(rect.height * 0.5, 24)
        switch spec.type {
        case .char:
            let text = displayText(spec.label)
            let size: CGFloat = text.count == 1 && isLetterLayer ? 23 * min(1, 0.75 + heightScale * 0.25) : (text.count <= 2 ? 20 : 16)
            drawText(text, in: rect, size: size, color: theme.text)
            if let hint = spec.hint {
                drawText(hint, in: CGRect(x: rect.maxX - 14, y: rect.minY + 2, width: 12, height: 12), size: 10, color: theme.subText)
            }
        case .space:
            if !spaceLabel.isEmpty {
                drawText(spaceLabel, in: rect.insetBy(dx: 8, dy: 0), size: 13, color: theme.subText, minSize: 9)
            }
        case .shift:
            let icon: Icon = shiftState == .off ? .shift : (shiftState == .once ? .shiftOn : .shiftLocked)
            Icons.draw(icon, in: context, center: center, size: iconSize, color: shiftState == .off ? theme.text : theme.accent)
        case .backspace:
            Icons.draw(.backspace, in: context, center: center, size: iconSize, color: theme.text)
        case .enter:
            Icons.draw(enterIcon, in: context, center: center, size: iconSize, color: theme.onAccent)
        case .language:
            if languageLabel.isEmpty {
                Icons.draw(.globe, in: context, center: center, size: iconSize * 0.92, color: theme.text)
            } else {
                drawText(languageLabel, in: rect, size: 15, color: theme.text, weight: .semibold)
            }
        case .globe:
            Icons.draw(.globe, in: context, center: center, size: iconSize * 0.92, color: theme.subText)
        case .emoji:
            Icons.draw(.emoji, in: context, center: center, size: iconSize * 0.92, color: theme.text)
        case .symbols, .symbolsMore, .letters:
            let text = spec.label.isEmpty ? (spec.type == .letters ? "ABC" : "123") : spec.label
            drawText(text, in: rect, size: 15, color: theme.text, weight: .medium)
        }
    }

    private func drawPopupBackground(_ context: CGContext, _ rect: CGRect) {
        let path = UIBezierPath(roundedRect: rect, cornerRadius: cornerRadius * 1.4)
        context.saveGState()
        context.setShadow(offset: CGSize(width: 0, height: 2), blur: 6, color: UIColor.black.withAlphaComponent(0.3).cgColor)
        context.setFillColor(theme.popup.cgColor)
        context.addPath(path.cgPath)
        context.fillPath()
        context.restoreGState()
    }

    private func drawPreview(_ context: CGContext, _ key: PlacedKey) {
        let rect = key.visual
        let width = max(rect.width + 14, 46)
        let height = rect.height * 1.18
        let left = min(max(2, rect.midX - width / 2), bounds.width - width - 2)
        let top = max(0, rect.minY - height - 6)
        let previewRect = CGRect(x: left, y: top, width: width, height: height)
        drawPopupBackground(context, previewRect)
        drawText(displayText(key.spec.label), in: previewRect, size: 30, color: theme.text, minSize: 12)
    }

    private func drawAlternates(_ context: CGContext, _ popup: AlternatesPopup) {
        drawPopupBackground(context, popup.bounds)
        let padding: CGFloat = 4
        for (index, option) in popup.options.enumerated() {
            let column = index % popup.columns
            let row = index / popup.columns
            let optionRect = CGRect(x: popup.bounds.minX + padding + CGFloat(column) * popup.optionWidth,
                                    y: popup.bounds.minY + padding + CGFloat(row) * popup.optionHeight,
                                    width: popup.optionWidth, height: popup.optionHeight)
            if index == popup.selected {
                context.setFillColor(theme.accent.cgColor)
                context.addPath(UIBezierPath(roundedRect: optionRect, cornerRadius: cornerRadius).cgPath)
                context.fillPath()
            }
            let text = displayText(option)
            drawText(text, in: optionRect, size: text.count <= 2 ? 22 : 16, color: index == popup.selected ? theme.onAccent : theme.text)
        }
    }

    // MARK: Touch

    private func stripTarget(at point: CGPoint) -> Int {
        guard point.y <= stripHeight else { return -1 }
        if !toolbarButtons.isEmpty {
            return toolbarButtons.firstIndex { point.x >= $0.1.minX - 4 && point.x <= $0.1.maxX + 4 } ?? -1
        }
        let slot = min(2, max(0, Int(point.x / (bounds.width / 3))))
        return stripSlots[slot] != nil ? slot : -1
    }

    private func key(at point: CGPoint) -> PlacedKey? {
        guard point.y >= stripHeight else { return nil }
        if let hit = placed.first(where: { $0.touch.contains(point) }) { return hit }
        return placed.min { a, b in
            hypot(point.x - a.touch.midX, point.y - a.touch.midY) < hypot(point.x - b.touch.midX, point.y - b.touch.midY)
        }
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { pointerDown(touch, at: touch.location(in: self)) }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            // Coalesced samples keep the corners of fast swipes that the display-rate events skip.
            let samples = event?.coalescedTouches(for: touch) ?? [touch]
            for sample in samples { pointerMove(touch, at: sample.location(in: self)) }
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { pointerUp(touch, at: touch.location(in: self)) }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            pointers.removeValue(forKey: ObjectIdentifier(touch))?.cancelTimers()
        }
        setNeedsDisplay()
    }

    private func pointerDown(_ touch: UITouch, at point: CGPoint) {
        // Rolling input: a new finger commits characters still held by other fingers.
        for other in pointers.values {
            guard let otherKey = other.key, !other.consumed, !other.gesture, other.popup == nil, !other.cursorMode, otherKey.spec.type == .char else { continue }
            other.longPressWork?.cancel()
            other.consumed = true
            delegate?.character(otherKey.spec, text: otherKey.spec.output)
        }
        let id = ObjectIdentifier(touch)
        if point.y < stripHeight {
            let pointer = Pointer(key: nil, downPoint: point)
            pointer.stripTarget = stripTarget(at: point)
            pointers[id] = pointer
            setNeedsDisplay()
            return
        }
        guard let key = self.key(at: point) else { return }
        let pointer = Pointer(key: key, downPoint: point)
        pointer.path.append(point)
        pointers[id] = pointer
        delegate?.keyDown(key.spec)
        switch key.spec.type {
        case .backspace:
            pointer.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.38, repeats: false) { [weak self, weak pointer] _ in
                guard let pointer else { return }
                self?.startRepeating(pointer)
            }
        case .char where !key.spec.alternates.isEmpty:
            scheduleLongPress(pointer, delay: longPressDelay)
        case .space, .language:
            scheduleLongPress(pointer, delay: 0.55)
        default:
            break
        }
        setNeedsDisplay()
    }

    private func startRepeating(_ pointer: Pointer) {
        pointer.repeatTimer?.invalidate()
        pointer.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.055, repeats: true) { [weak self, weak pointer] timer in
            guard let self, let pointer else {
                timer.invalidate()
                return
            }
            pointer.repeatCount += 1
            self.delegate?.backspaceRepeat()
            if pointer.repeatCount == 25 {
                timer.invalidate()
                pointer.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.028, repeats: true) { [weak self] _ in
                    self?.delegate?.backspaceRepeat()
                }
            }
        }
    }

    private func scheduleLongPress(_ pointer: Pointer, delay: TimeInterval) {
        pointer.longPressWork?.cancel()
        let work = DispatchWorkItem { [weak self, weak pointer] in
            guard let self, let pointer else { return }
            self.longPress(pointer)
        }
        pointer.longPressWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func pointerMove(_ touch: UITouch, at point: CGPoint) {
        guard let pointer = pointers[ObjectIdentifier(touch)] else { return }
        if pointer.stripTarget >= 0 {
            if stripTarget(at: point) != pointer.stripTarget {
                pointer.stripTarget = -1
                setNeedsDisplay()
            }
            return
        }
        guard let key = pointer.key else { return }
        if var popup = pointer.popup {
            let selected = alternateIndex(popup, at: point)
            if selected != popup.selected {
                popup.selected = selected
                pointer.popup = popup
                setNeedsDisplay()
            }
            return
        }
        if pointer.consumed { return }
        switch key.spec.type {
        case .space:
            let step: CGFloat = 10
            if !pointer.cursorMode && abs(point.x - pointer.downPoint.x) > 14 {
                pointer.cursorMode = true
                pointer.cursorAnchorX = point.x
                pointer.longPressWork?.cancel()
                setNeedsDisplay()
            }
            if pointer.cursorMode {
                let steps = Int((point.x - pointer.cursorAnchorX) / step)
                if steps != 0 {
                    pointer.cursorAnchorX += CGFloat(steps) * step
                    delegate?.cursorMove(steps)
                }
            }
        case .char:
            if gestureTyping && isLetterLayer && pointers.count == 1 {
                pointer.path.append(point)
                if !pointer.gesture && hypot(point.x - pointer.downPoint.x, point.y - pointer.downPoint.y) > key.visual.width * 0.9 && self.key(at: point) !== key {
                    pointer.gesture = true
                    pointer.longPressWork?.cancel()
                }
                if pointer.gesture {
                    setNeedsDisplay()
                    return
                }
            }
            if !key.touch.contains(point), let next = self.key(at: point), next !== key, next.spec.type == .char {
                pointer.longPressWork?.cancel()
                pointer.key = next
                if !next.spec.alternates.isEmpty { scheduleLongPress(pointer, delay: longPressDelay) }
                setNeedsDisplay()
            }
        default:
            break
        }
    }

    private func pointerUp(_ touch: UITouch, at point: CGPoint) {
        guard let pointer = pointers.removeValue(forKey: ObjectIdentifier(touch)) else { return }
        pointer.cancelTimers()
        setNeedsDisplay()
        guard let key = pointer.key else {
            let target = pointer.stripTarget
            if target >= 0 && stripTarget(at: point) == target {
                if !toolbarButtons.isEmpty {
                    delegate?.toolbarAction(toolbarButtons[target].0)
                } else if let text = stripSlots[target] {
                    delegate?.suggestionPicked(text)
                }
            }
            return
        }
        delegate?.keyUp(key.spec)
        if pointer.gesture {
            pointer.path.append(point)
            delegate?.gesture(pointer.path)
            return
        }
        if pointer.consumed { return }
        if let popup = pointer.popup {
            if popup.selected >= 0 && popup.selected < popup.options.count {
                delegate?.character(key.spec, text: popup.options[popup.selected])
            }
            return
        }
        if pointer.longPressed || pointer.cursorMode { return }
        switch key.spec.type {
        case .char: delegate?.character(key.spec, text: key.spec.output)
        case .shift, .backspace, .globe: break
        default: delegate?.special(key.spec)
        }
    }

    private func longPress(_ pointer: Pointer) {
        guard pointers.values.contains(where: { $0 === pointer }), !pointer.consumed, let key = pointer.key else { return }
        switch key.spec.type {
        case .char:
            pointer.longPressed = true
            pointer.popup = buildPopup(key, touchX: pointer.downPoint.x)
            setNeedsDisplay()
        case .space, .language:
            if pointer.cursorMode { return }
            pointer.longPressed = true
            delegate?.switchKeyboardRequested()
        default:
            break
        }
    }

    private func buildPopup(_ key: PlacedKey, touchX: CGFloat) -> AlternatesPopup {
        let options = key.spec.alternates
        let optionWidth = max(key.visual.width + 2, 36)
        let optionHeight = key.visual.height * 1.05
        let padding: CGFloat = 4
        let maxColumns = max(1, Int((bounds.width - 2 * padding - 8) / optionWidth))
        let columns = min(options.count, min(maxColumns, 8))
        let rowCount = (options.count + columns - 1) / columns
        let width = CGFloat(columns) * optionWidth + 2 * padding
        let height = CGFloat(rowCount) * optionHeight + 2 * padding
        let left = min(max(2, key.visual.midX - width / 2), bounds.width - width - 2)
        let top = max(0, key.visual.minY - height - 2)
        var popup = AlternatesPopup(options: options, bounds: CGRect(x: left, y: top, width: width, height: height),
                                    columns: columns, optionWidth: optionWidth, optionHeight: optionHeight, selected: 0)
        popup.selected = alternateIndex(popup, at: CGPoint(x: touchX, y: popup.bounds.maxY - padding - optionHeight / 2))
        return popup
    }

    private func alternateIndex(_ popup: AlternatesPopup, at point: CGPoint) -> Int {
        let padding: CGFloat = 4
        let column = min(popup.columns - 1, max(0, Int((point.x - popup.bounds.minX - padding) / popup.optionWidth)))
        let rowCount = (popup.options.count + popup.columns - 1) / popup.columns
        let row = min(rowCount - 1, max(0, Int((point.y - popup.bounds.minY - padding) / popup.optionHeight)))
        return min(row * popup.columns + column, popup.options.count - 1)
    }

    // MARK: Gesture typing

    private func drawGestureTrail(_ context: CGContext, _ points: [CGPoint]) {
        guard points.count > 1 else { return }
        let path = UIBezierPath()
        path.move(to: points[0])
        points.dropFirst().forEach { path.addLine(to: $0) }
        context.saveGState()
        context.setStrokeColor(theme.accent.withAlphaComponent(0.75).cgColor)
        context.setLineWidth(4.5)
        context.setLineCap(.round)
        context.setLineJoin(.round)
        context.addPath(path.cgPath)
        context.strokePath()
        context.restoreGState()
    }

    /// Centres of the letter keys (lower-case output → centre), used to decode swipes.
    func gestureKeyCenters() -> [String: CGPoint] {
        var centers: [String: CGPoint] = [:]
        guard let lastRowTop = placed.last?.visual.minY else { return centers }
        for key in placed where key.spec.type == .char && key.visual.minY < lastRowTop {
            let output = key.spec.output.lowercased()
            guard output.contains(where: { $0.isLetter }), centers[output] == nil else { continue }
            centers[output] = CGPoint(x: key.visual.midX, y: key.visual.midY)
        }
        return centers
    }

    func gestureKeyWidth() -> CGFloat {
        let widths = placed.filter { $0.spec.type == .char }.map { $0.touch.width }.sorted()
        return widths.isEmpty ? 34 : widths[widths.count / 2]
    }

    // MARK: Accessibility (VoiceOver touch typing)

    private var accessibilityElementsCache: [UIAccessibilityElement]?

    private final class KeyElement: UIAccessibilityElement {
        var activate: (() -> Void)?

        override func accessibilityActivate() -> Bool {
            activate?()
            return true
        }
    }

    private func accessibilityLabel(for spec: KeySpec) -> String {
        switch spec.type {
        case .char: return displayText(spec.label)
        case .shift:
            switch shiftState {
            case .off: return "Shift"
            case .once: return "Shift ✓"
            case .locked: return "Caps Lock"
            }
        case .backspace: return KeyboardStrings.delete
        case .space: return KeyboardStrings.space + (spaceLabel.isEmpty ? "" : ", \(spaceLabel)")
        case .enter: return KeyboardStrings.enter
        case .symbols, .symbolsMore: return KeyboardStrings.symbols
        case .letters: return KeyboardStrings.letters
        case .language: return KeyboardStrings.switchLanguage
        case .globe: return KeyboardStrings.nextKeyboard
        case .emoji: return "Emoji"
        }
    }

    override var isAccessibilityElement: Bool {
        get { false }
        set { super.isAccessibilityElement = newValue }
    }

    override var accessibilityElements: [Any]? {
        get {
            if let cached = accessibilityElementsCache { return cached }
            var elements: [UIAccessibilityElement] = []
            if toolbarButtons.isEmpty {
                let slotWidth = bounds.width / 3
                for slot in 0..<3 {
                    guard let text = stripSlots[slot] else { continue }
                    let element = KeyElement(accessibilityContainer: self)
                    element.accessibilityLabel = text
                    element.accessibilityTraits = .button
                    element.accessibilityFrameInContainerSpace = CGRect(x: CGFloat(slot) * slotWidth, y: 0, width: slotWidth, height: stripHeight)
                    element.activate = { [weak self] in self?.delegate?.suggestionPicked(text) }
                    elements.append(element)
                }
            } else {
                for (action, rect) in toolbarButtons {
                    let element = KeyElement(accessibilityContainer: self)
                    element.accessibilityLabel = KeyboardStrings.toolbar(action)
                    element.accessibilityTraits = .button
                    element.accessibilityFrameInContainerSpace = rect
                    element.activate = { [weak self] in self?.delegate?.toolbarAction(action) }
                    elements.append(element)
                }
            }
            for key in placed where key.spec.type != .globe {
                let element = KeyElement(accessibilityContainer: self)
                element.accessibilityLabel = accessibilityLabel(for: key.spec)
                element.accessibilityTraits = .keyboardKey
                element.accessibilityFrameInContainerSpace = key.touch
                let spec = key.spec
                element.activate = { [weak self] in
                    guard let delegate = self?.delegate else { return }
                    delegate.keyDown(spec)
                    switch spec.type {
                    case .char: delegate.character(spec, text: spec.output)
                    case .shift, .backspace, .globe: break
                    default: delegate.special(spec)
                    }
                    delegate.keyUp(spec)
                }
                elements.append(element)
            }
            accessibilityElementsCache = elements
            return elements
        }
        set { accessibilityElementsCache = newValue as? [UIAccessibilityElement] }
    }
}
