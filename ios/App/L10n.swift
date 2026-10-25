import Foundation

/// Interface strings in Uzbek (2027 alphabet), Russian and English, chosen from the device language.
enum L10n {
    enum Language { case uz, ru, en }

    static let language: Language = {
        let code = Locale.preferredLanguages.first?.lowercased() ?? "en"
        if code.hasPrefix("uz") { return .uz }
        if code.hasPrefix("ru") { return .ru }
        return .en
    }()

    private static func pick(_ uz: String, _ ru: String, _ en: String) -> String {
        switch language {
        case .uz: return uz
        case .ru: return ru
        case .en: return en
        }
    }

    static var tagline: String { pick("Özbek, rus va ingliz tillari uçun klaviatura", "Клавиатура для узбекского, русского и английского", "Keyboard for Uzbek, Russian and English") }
    static var setup: String { pick("Sozlaş", "Настройка", "Setup") }
    static var step1Title: String { pick("1. Klaviaturani qöşing", "1. Добавьте клавиатуру", "1. Add the keyboard") }
    static var step1Text: String { pick("Sozlamalar → UzKey → Klaviaturalar → UzKey ni yoqing. Tebraniş va sozlamalar uçun «Töliq ruxsat» ni ham yoqing.", "Настройки → UzKey → Клавиатуры → включите UzKey. Для вибрации и настроек включите «Полный доступ».", "Settings → UzKey → Keyboards → turn on UzKey. Turn on “Allow Full Access” for haptics and settings sync.") }
    static var openSettings: String { pick("Sozlamalarni ochiş", "Открыть настройки", "Open Settings") }
    static var step2Title: String { pick("2. UzKey ni tanlang", "2. Выберите UzKey", "2. Switch to UzKey") }
    static var step2Text: String { pick("Istalgan matn maydonida 🌐 ni bosib turing va UzKey ni tanlang.", "В любом поле ввода удерживайте 🌐 и выберите UzKey.", "In any text field, hold 🌐 and pick UzKey.") }
    static var enabled: String { pick("UzKey yoqilgan", "UzKey включена", "UzKey is enabled") }
    static var tryHint: String { pick("Şu yerda sinab köring", "Попробуйте написать здесь", "Try typing here") }
    static var languages: String { pick("Tillar", "Языки", "Languages") }
    static var languagesFooter: String { pick("🌐 tugmasi tanlangan tillar orasida almaştiradi.", "Клавиша 🌐 переключает выбранные языки.", "The 🌐 key switches between the selected languages.") }
    static var theme: String { pick("Mavzu", "Тема", "Theme") }
    static func themeName(_ choice: ThemeChoice) -> String {
        switch choice {
        case .system: return pick("Tizim", "Системная", "System")
        case .light: return pick("Yoruğ", "Светлая", "Light")
        case .dark: return pick("Qoronği", "Тёмная", "Dark")
        case .black: return pick("Qora", "Чёрная", "Black")
        case .blue: return pick("Moviy", "Синяя", "Blue")
        }
    }
    static var typing: String { pick("Yoziş", "Ввод", "Typing") }
    static var suggestions: String { pick("Söz takliflari", "Подсказки слов", "Word suggestions") }
    static var autocorrect: String { pick("Avtomatik tuzatiş", "Автоисправление", "Auto-correct") }
    static var learn: String { pick("Yangi sözlarni örganiş", "Запоминать новые слова", "Learn new words") }
    static var autocap: String { pick("Avtomatik boş harf", "Заглавная в начале предложения", "Auto-capitalization") }
    static var doubleSpace: String { pick("Ikki böş joy — nuqta", "Точка двойным пробелом", "Double-space period") }
    static var apostrophe: String { pick("Apostrofni töğrilaş (o' → ö / oʻ)", "Исправлять апострофы (o' → ö / oʻ)", "Fix apostrophes (o' → ö / oʻ)") }
    static var gesture: String { pick("Surib yoziş", "Непрерывный ввод", "Swipe typing") }
    static var clipboard: String { pick("Bufer tarixi («Töliq ruxsat» kerak)", "История буфера (нужен «Полный доступ»)", "Clipboard history (needs Full Access)") }
    static var keys: String { pick("Tugmalar", "Клавиши", "Keys") }
    static var numberRow: String { pick("Raqamlar qatori", "Ряд цифр", "Number row") }
    static var numberHints: String { pick("Raqam belgilari", "Подсказки цифр", "Number hints") }
    static var popup: String { pick("Bosilgan tugmani kattalaştiriş", "Увеличивать нажатую клавишу", "Magnify key on press") }
    static var vibration: String { pick("Tebraniş", "Вибрация", "Haptics") }
    static var sound: String { pick("Tovuş", "Звук", "Sound") }
    static var keyHeight: String { pick("Tugma balandligi", "Высота клавиш", "Key height") }
    static func keyHeightName(_ height: KeyHeight) -> String {
        switch height {
        case .small: return pick("Kiçik", "Низкие", "Small")
        case .normal: return pick("Örta", "Средние", "Medium")
        case .large: return pick("Katta", "Высокие", "Large")
        }
    }
    static var dictionary: String { pick("Luğat", "Словарь", "Dictionary") }
    static var clearLearned: String { pick("Örganilgan sözlarni öçiriş", "Удалить выученные слова", "Delete learned words") }
    static var clearedLearned: String { pick("Klaviatura keyingi oçilişda örganilgan sözlarni öçiradi.", "Клавиатура удалит выученные слова при следующем открытии.", "The keyboard will delete learned words the next time it opens.") }
    static var privacy: String { pick("Maxfiylik", "Конфиденциальность", "Privacy") }
    static var privacyText: String { pick("UzKey hech narsani internetga yubormaydi. «Töliq ruxsat» faqat tebraniş va ilova sozlamalarini klaviatura bilan bölişiş uçun kerak.", "UzKey ничего не отправляет в интернет. «Полный доступ» нужен только для вибрации и синхронизации настроек с приложением.", "UzKey never sends anything to the internet. Full Access is only used for haptics and sharing these settings with the keyboard.") }
    static var tips: String { pick("Maslahatlar", "Советы", "Tips") }
    static var tipList: [String] {
        switch language {
        case .uz: return [
            "Barmoqni kötarmasdan harflar ustida surib yozing",
            "Kursorni siljitiş uçun böş joy tugmasini suring",
            "Qöşimça harflar uçun harfni bosib turing (o → ö, oʻ)",
            "Caps Lock uçun Shift ni ikki marta bosing",
            "⇄ tugmasi matnni joriy özbek alifbosiga ögiradi",
        ]
        case .ru: return [
            "Вводите слова, не отрывая палец от букв",
            "Проведите по пробелу, чтобы двигать курсор",
            "Удерживайте букву для дополнительных символов (o → ö, oʻ)",
            "Дважды нажмите Shift для Caps Lock",
            "Кнопка ⇄ переводит текст в выбранный узбекский алфавит",
        ]
        case .en: return [
            "Swipe across letters to type without lifting your finger",
            "Slide on the space bar to move the cursor",
            "Hold a letter for extra characters (o → ö, oʻ)",
            "Double-tap Shift for Caps Lock",
            "The ⇄ button converts text to the active Uzbek alphabet",
        ]
        }
    }
    static var version: String { pick("Versiya", "Версия", "Version") }
    static var keepOneLanguage: String { pick("Kamida bitta til qolsin", "Оставьте хотя бы один язык", "Keep at least one language") }
}
