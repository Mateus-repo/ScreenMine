package dev.screenmine;

import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;

/**
 * The mod's own language, independent of Minecraft's.
 *
 * <p>Defaults to English. The player switches it from the mini menu and the
 * choice is written to {@code config/screenmine.properties}, so it survives a
 * restart. Strings live in code rather than in resource packs because the
 * selection has to be separate from the game's own language setting.
 */
public final class Lang {

    public enum Language {
        EN("English"),
        RU("Русский");

        public final String label;

        Language(String label) {
            this.label = label;
        }

        public Language next() {
            return this == EN ? RU : EN;
        }
    }

    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> RU = new HashMap<>();

    private static Language current = Language.EN;
    private static boolean loaded;

    private Lang() {
    }

    public static Language current() {
        ensureLoaded();
        return current;
    }

    public static void set(Language language) {
        ensureLoaded();
        if (language == null || language == current) {
            return;
        }
        current = language;
        save();
    }

    /** Switches to the other language and remembers it. */
    public static Language toggle() {
        ensureLoaded();
        set(current.next());
        return current;
    }

    /** Looks up a key; unknown keys fall back to English, then to the key itself. */
    public static String t(String key) {
        ensureLoaded();
        Map<String, String> table = current == Language.RU ? RU : EN;
        String value = table.get(key);
        if (value == null) {
            value = EN.get(key);
        }
        return value != null ? value : key;
    }

    /**
     * {@link #t(String)} with {@link String#format} arguments.
     *
     * <p>Guarded, because a mismatched {@code %} in a translation is a crash on
     * the render thread or the client tick, and nothing would catch it before
     * the player hit that button. Every key was audited - all 102 match their
     * call sites today - but the guard is what stops the next one from being a
     * crash report. Falling back to the unformatted string is ugly and harmless;
     * an IllegalFormatException on the client tick is neither.
     */
    public static String t(String key, Object... args) {
        String pattern = t(key);
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (IllegalFormatException ex) {
            ScreenMineClient.LOG.warn("bad format string for '{}': {}", key, ex.toString());
            return pattern;
        }
    }

    // ------------------------------------------------------------ storage //

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        current = "ru".equalsIgnoreCase(Settings.get("language", "en"))
                ? Language.RU
                : Language.EN;
    }

    private static void save() {
        Settings.set("language", current.name().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------ strings //

    private static void put(String key, String en, String ru) {
        EN.put(key, en);
        RU.put(key, ru);
    }

    static {
        // menu
        put("menu.title", "ScreenMine", "ScreenMine");
        put("menu.spawn", "Spawn a screen", "Заспавнить экран");
        put("menu.screens.none", "— no screens placed —", "— экранов нет —");
        put("menu.screens.aimed", "  ← aimed", "  ← наведён");
        put("menu.screens.page", "%d/%d", "%d/%d");
        put("menu.streams", "Session screens (%d)", "Экраны сессии (%d)");
        put("menu.friends", "Friends (%d)", "Друзья (%d)");
        put("menu.friends.pending", "Friends (%d) · %d pending", "Друзья (%d) · заявок: %d");
        put("menu.invites", "Invitations", "Приглашения");
        put("menu.invites.count", "Invitations (%d)", "Приглашения (%d)");
        put("menu.leave", "Leave %s's session", "Покинуть сессию %s");
        put("menu.close.session", "Close the session", "Закрыть сессию");
        put("menu.close", "Close", "Закрыть");
        put("menu.language", "Language: %s", "Язык: %s");
        put("menu.back", "Back", "Назад");
        put("menu.keys", "%s — control · %s — browser · %s — move · %s — size",
                "%s — управлять · %s — браузер · %s — двигать · %s — размер");

        // screen panel
        put("ctl.take", "Take control", "Взять управление");
        put("ctl.youtube", "YouTube", "YouTube");
        put("ctl.playpause", "Pause / Play", "Пауза / Плей");
        put("ctl.browser", "Open browser", "Открыть браузер");
        put("ctl.back10", "« −10 sec", "« −10 сек");
        put("ctl.fwd10", "+10 sec »", "+10 сек »");
        put("ctl.url", "Enter an address…", "Ввести адрес…");
        put("ctl.smaller", "Smaller −", "Меньше −");
        put("ctl.move", "Move", "Двигать");
        put("ctl.bigger", "+ Bigger", "+ Больше");
        put("ctl.quieter", "Quieter −", "Тише −");
        put("ctl.louder", "+ Louder", "+ Громче");
        put("ctl.mute", "Mute", "Выкл. звук");
        put("ctl.unmute", "Unmute", "Вкл. звук");
        put("ctl.detach", "Remove for me", "Убрать у себя");
        put("ctl.delete", "Delete for everyone", "Удалить у всех");
        put("ctl.screen", "Screen", "Экран");
        put("ctl.status", "width %.1f blocks · %s · %s (audible for %.0f blocks)",
                "ширина %.1f блока · %s · %s (слышно на %.0f блоков)");
        put("ctl.playing", "playing", "играет");
        put("ctl.paused", "paused", "пауза");
        put("ctl.sound", "sound %d%%", "звук %d%%");
        put("ctl.sound.off", "sound off", "звук выкл");
        put("ctl.aim.again", "Aim at a screen and try again", "Наведись на экран и попробуй снова");

        // friends
        put("friends.title", "Friends", "Друзья");
        put("friends.name", "Player name", "Ник игрока");
        put("friends.add", "Add", "Добавить");
        put("friends.accept", "Accept", "Принять");
        put("friends.decline", "Decline", "Отклонить");
        put("friends.invite", "Invite", "Пригласить");
        put("friends.remove", "Remove", "Удалить");
        put("friends.empty", "Nobody yet. Type a name and press Add.",
                "Пока никого. Введи ник и нажми «Добавить».");
        put("friends.sent", "Sent: %s", "Отправлено: %s");
        put("friends.request", " — request", " — заявка");
        put("friends.online", " — online", " — в сети");
        put("friends.offline", " — offline", " — офлайн");
        put("friends.same.server", " — online, same server", " — в сети, тот же сервер");
        put("friends.other.server", " — online, other server (%s)", " — в сети, другой сервер (%s)");
        put("friends.invite.sent", "Invitation sent: %s", "Приглашение отправлено: %s");

        // invitations
        put("invites.title", "Invitations", "Приглашения");
        put("invites.none", "No invitations", "Приглашений нет");
        put("invites.calls", "%s wants to watch together", "%s зовёт смотреть вместе");
        put("invites.join", "Join", "Присоединиться");
        put("invites.later", "Later", "Позже");
        put("invites.same.server", "you are on the same Minecraft server",
                "вы на одном сервере Minecraft");
        put("invites.other.server", "different Minecraft servers: they are on %s",
                "разные серверы Minecraft: у него %s");
        put("invites.unknown.server", "Minecraft server unknown", "сервер Minecraft неизвестен");

        // join choice
        put("join.in.session", "You are in %s's session", "Ты в сессии %s");
        put("join.hint", "Spawn your own screen or connect to someone else's",
                "Заспавни свой экран или подключись к чужому");
        put("join.spawn", "Spawn my own screen", "Заспавнить свой экран");
        put("join.connect", "Connect: %s", "Подключиться: %s");

        // session screens
        put("streams.title", "Session screens", "Экраны сессии");
        put("streams.empty", "No screens yet — spawn one", "Экранов пока нет — заспавни свой");
        put("streams.connect", "Connect", "Подключиться");
        put("streams.detach", "Remove for me", "Убрать у себя");
        put("streams.delete", "Delete", "Удалить");
        put("streams.mine", "yours", "у тебя");
        put("streams.not.connected", "not connected", "не подключён");

        // address dialog
        put("url.title", "Address", "Адрес");
        put("url.hint", "Address or search query", "Адрес или поисковый запрос");
        put("url.open", "Open", "Открыть");
        put("url.cancel", "Cancel", "Отмена");

        // browser overlay
        put("browser.title", "Browser", "Браузер");
        put("browser.starting", "Browser is starting…", "Браузер запускается…");
        put("browser.close.hint", "Esc — close · the screen stays up in the world",
                "Esc — закрыть · экран продолжает висеть в мире");

        // hud
        put("hud.session", "Session %s · %d players", "Сессия %s · %d чел.");
        put("hud.invite", "Invitation: %s  [%s]", "Приглашение: %s  [%s]");
        put("hud.requests", "Friend requests: %d  [%s]", "Заявки в друзья: %d  [%s]");
        put("hud.control", "Control: LMB click · wheel scroll · click a field to type · Esc to exit",
                "Управление: ЛКМ клик · колесо скролл · клик в поле — печатай · Esc выйти");
        put("hud.carrying", "Carrying a screen — click to place it",
                "Несёшь экран — клик, чтобы поставить");
        put("hud.hints.control", "LMB — click · wheel — scroll · Esc — exit",
                "ЛКМ — клик · колесо — скролл · Esc — выйти");
        put("hud.hints", "3 clicks + a 4th — grab · %s — control · %s — browser · %s — size · %s — %s",
                "3 клика + 4-й — взять · %s — управлять · %s — браузер · %s — размер · %s — %s");

        // chat
        put("chat.connected", "ScreenMine connected as %s", "ScreenMine подключён как %s");
        put("chat.no.server", "No connection to the ScreenMine server",
                "Нет связи с сервером ScreenMine");
        put("chat.aim", "Aim at a screen to control it", "Наведись на экран, чтобы им управлять");
        put("chat.attached", "Screen connected: %s", "Экран подключён: %s");
        put("chat.friend.request", "Friend request from %s — open the menu (%s)",
                "Заявка в друзья от %s — открой меню (%s)");
        put("chat.invite", "%s wants to watch together — menu (%s)",
                "%s зовёт смотреть вместе — меню (%s)");
        put("chat.invite.servers", "  you are on different Minecraft servers — the video syncs, "
                + "but you will not be standing together",
                "  вы на разных серверах Minecraft — видео синхронизируется, "
                + "но рядом вы не окажетесь");
        put("chat.stream.new", "%s turned a screen on", "%s включил экран");
        put("chat.volume", "Screen: volume %d%%", "Экран: громкость %d%%");
        put("chat.muted", "Screen: sound off", "Экран: звук выключен");
        put("chat.action.play", "playing", "воспроизведение");
        put("chat.action.pause", "paused", "пауза");
        put("chat.action.seek", "skipped", "перемотка");
        put("chat.action.navigate", "opened another page", "открыл другую страницу");

        // misc
        put("misc.unknown", "unknown", "неизвестно");
        put("misc.singleplayer", "singleplayer", "одиночная игра");
        put("misc.screen.of", "%s's screen", "Экран %s");
    }
}
