package com.terraduo.companion;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Companion UI strings. The game supplies its own localized item names and tooltips. */
final class Strings {
    private static final Map<String, String[]> TABLE = new HashMap<>();
    private static Language language = Language.ENGLISH;

    // Older Android build tools cannot read the unnamed enum parameters emitted by newer javac versions.
    static final class Language {
        private static final Language ENGLISH = new Language(0, "en", "English");
        private static final Language RUSSIAN = new Language(1, "ru", "Русский");
        private static final Language SPANISH = new Language(2, "es", "Español");
        private static final Language POLISH = new Language(3, "pl", "Polski");
        private static final Language[] SUPPORTED = {ENGLISH, RUSSIAN, SPANISH, POLISH};

        private final int column;
        final String code;
        final String label;

        private Language(int column, String code, String label) {
            this.column = column;
            this.code = code;
            this.label = label;
        }

        static Language[] values() {
            return SUPPORTED.clone();
        }

        static Language fromCulture(String culture) {
            if (culture == null) return ENGLISH;
            switch (Locale.forLanguageTag(culture.replace('_', '-')).getLanguage()) {
                case "ru": return RUSSIAN;
                case "es": return SPANISH;
                case "pl": return POLISH;
                default: return ENGLISH;
            }
        }
    }

    static {
        put("inventory", "Inventory", "Инвентарь", "Inventario", "Ekwipunek");
        put("equipment", "Equipment", "Снаряжение", "Equipo", "Wyposażenie");
        put("craft", "Craft", "Крафт", "Fabricación", "Wytwarzanie");
        put("map", "Map", "Карта", "Mapa", "Mapa");
        put("people", "People", "Люди", "Gente", "Ludzie");
        put("chat_title", "Chat", "Чат", "Chat", "Czat");
        put("emotes", "Emotes", "Эмоции", "Emotes", "Emotki");
        put("chat_send", "Send", "Отправить", "Enviar", "Wyślij");
        put("chat_empty", "No messages yet", "Сообщений пока нет", "Aún no hay mensajes", "Brak wiadomości");
        put("chat_hint", "Type a message or a /command", "Введи сообщение или /команду", "Escribe un mensaje o un /comando", "Wpisz wiadomość lub /komendę");
        put("nearby", "Nearby", "Рядом", "Cerca", "W pobliżu");
        put("talk", "Talk", "Поговорить", "Hablar", "Rozmawiaj");
        put("trades_here", "trades", "торгует", "comercia", "handluje");
        put("tiles", "tiles", "кл.", "casillas", "pól");
        put("quick_stack", "Stack to chests", "В сундуки рядом", "Apilar en cofres", "Do skrzyń obok");
        put("filter_0", "All", "Все", "Todo", "Wszystko");
        put("filter_1", "Weapons", "Оружие", "Armas", "Broń");
        put("filter_2", "Tools", "Инструменты", "Herramientas", "Narzędzia");
        put("filter_3", "Armor", "Броня", "Armadura", "Zbroja");
        put("filter_4", "Potions", "Зелья", "Pociones", "Mikstury");
        put("filter_5", "Blocks", "Блоки", "Bloques", "Bloki");
        put("ctab_0", "Recipes", "Рецепты", "Recetas", "Przepisy");
        put("ctab_1", "Banners", "Баннеры", "Estandartes", "Sztandary");
        put("ctab_2", "Guide", "Гид", "Guía", "Przewodnik");
        put("banners_empty", "No banners to claim yet. Every 50 kills of an enemy earn its banner.",
            "Пока нет баннеров. За каждые 50 убийств врага даётся его баннер.",
            "Aún no hay estandartes. Cada 50 muertes de un enemigo dan su estandarte.",
            "Brak sztandarów. Każde 50 zabójstw wroga daje jego sztandar.");
        put("banner_all", "Claim all", "Забрать все", "Reclamar todo", "Odbierz wszystkie");
        put("banner_claimed", "claimed", "получен", "reclamado", "odebrano");
        put("guide_hint", "Tap an item to see every recipe that makes it or uses it.",
            "Нажми предмет, чтобы увидеть все рецепты, где он создаётся или используется.",
            "Toca un objeto para ver todas las recetas que lo crean o lo usan.",
            "Dotknij przedmiotu, aby zobaczyć wszystkie przepisy, w których występuje.");
        put("guide_talking", "The Guide is listening: pick an item.", "Гид слушает: выбери предмет.",
            "La Guía te escucha: elige un objeto.", "Przewodnik słucha: wybierz przedmiot.");
        put("rv_makes", "Makes it", "Создаёт", "Lo crea", "Tworzy");
        put("rv_uses", "Uses it", "Использует", "Lo usa", "Używa");
        put("rv_none", "No recipes", "Рецептов нет", "Sin recetas", "Brak przepisów");
        put("recipes_btn", "Recipes", "Рецепты", "Recetas", "Przepisy");
        put("house_check", "Check room", "Проверить", "Revisar", "Sprawdź");
        put("house_move", "Move in", "Заселить", "Mudar", "Wprowadź");
        put("house_not_enclosed", "Not a closed room here", "Здесь нет замкнутой комнаты", "Aquí no hay habitación cerrada", "Tu nie ma zamkniętego pokoju");
        put("house_missing", "Room is closed but lacks", "Комната замкнута, но нет", "La habitación está cerrada pero falta", "Pokój jest zamknięty, ale brak");
        put("house_unsuitable", "Room does not meet the needs", "Комната не подходит", "La habitación no cumple los requisitos", "Pokój nie spełnia wymagań");
        put("house_ok", "Room is suitable", "Комната подходит", "La habitación es adecuada", "Pokój nadaje się");
        put("house_light", "a light", "света", "luz", "światła");
        put("house_door", "a door", "двери", "puerta", "drzwi");
        put("house_chair", "a chair", "стула", "silla", "krzesła");
        put("house_table", "a table", "стола", "mesa", "stołu");
        put("house_moved", "moved in", "заселён", "se mudó", "wprowadzony");
        put("house_failed", "cannot move in here", "сюда нельзя заселить", "no puede mudarse aquí", "nie może się tu wprowadzić");
        put("team", "Team", "Команда", "Equipo", "Drużyna");
        put("team_0", "None", "Нет", "Ninguno", "Brak");
        put("team_1", "Red", "Красная", "Rojo", "Czerwona");
        put("team_2", "Green", "Зелёная", "Verde", "Zielona");
        put("team_3", "Blue", "Синяя", "Azul", "Niebieska");
        put("team_4", "Yellow", "Жёлтая", "Amarillo", "Żółta");
        put("team_5", "Pink", "Розовая", "Rosa", "Różowa");
        put("on", "on", "вкл", "sí", "wł.");
        put("off", "off", "выкл", "no", "wył.");
        put("npcs_town", "Town NPCs", "Жители", "Habitantes", "Mieszkańcy");
        put("homeless", "Homeless", "Без дома", "Sin hogar", "Bezdomny");
        put("has_home", "Has a home", "Есть дом", "Tiene hogar", "Ma dom");
        put("players_list", "Players", "Игроки", "Jugadores", "Gracze");
        put("no_players", "No other players", "Других игроков нет", "No hay otros jugadores", "Brak innych graczy");
        put("armor_total", "Defense", "Броня", "Defensa", "Obrona");
        put("loadout", "Armor set", "Набор брони", "Conjunto", "Zestaw");
        put("hp", "Life", "Жизнь", "Vida", "Życie");
        put("mana", "Mana", "Мана", "Maná", "Mana");
        put("hide_hud", "Hide HUD", "Скрыть HUD", "Ocultar HUD", "Ukryj HUD");
        put("show_hud", "Show HUD", "Вернуть HUD", "Mostrar HUD", "Pokaż HUD");
        put("open_world", "Open a single-player world through TL Pro", "Открой одиночный мир через TL Pro", "Abre un mundo de un jugador desde TL Pro", "Otwórz świat dla jednego gracza przez TL Pro");
        put("pick_target", "choose a destination slot", "выбери слот назначения", "elige una ranura de destino", "wybierz pole docelowe");
        put("buff_removed", "Removed", "Снято", "Quitado", "Usunięto");
        put("buff_debuff", "A debuff can't be removed", "Дебафф нельзя снять", "Un perjuicio no se puede quitar", "Debuffa nie można zdjąć");
        put("hint_drag", "Drag an item to move it", "Перетащи предмет, чтобы переместить", "Arrastra un objeto para moverlo", "Przeciągnij przedmiot, aby go przenieść");
        put("coins_ammo", "Coins and ammo", "Монеты и боеприпасы", "Monedas y munición", "Monety i amunicja");
        put("armor", "Armor and accessories", "Броня и аксессуары", "Armadura y accesorios", "Pancerz i akcesoria");
        put("vanity", "Vanity", "Внешний вид", "Apariencia", "Wygląd");
        put("dyes", "Dyes", "Красители", "Tintes", "Barwniki");
        put("pets", "Pets and mounts", "Питомцы и транспорт", "Mascotas y monturas", "Zwierzaki i wierzchowce");
        put("pet_dyes", "Pet and mount dyes", "Красители питомцев и транспорта", "Tintes de mascotas y monturas", "Barwniki zwierzaków i wierzchowców");
        put("locked", "Locked", "Закрыт", "Bloqueado", "Zablokowane");
        put("empty", "Empty", "Пусто", "Vacío", "Puste");
        put("no_recipes", "No recipes available", "Нет доступных рецептов", "No hay recetas disponibles", "Brak dostępnych receptur");
        put("page", "Page", "Страница", "Página", "Strona");
        put("craft_one", "Craft", "Создать", "Fabricar", "Wytwórz");
        put("map_loading", "Map is loading", "Карта загружается", "Cargando mapa", "Wczytywanie mapy");
        put("to_player", "To player", "К игроку", "Al jugador", "Do gracza");
        put("head", "Helmet", "Шлем", "Casco", "Hełm");
        put("body", "Chestplate", "Нагрудник", "Peto", "Napierśnik");
        put("legs", "Leggings", "Поножи", "Grebas", "Nagolenniki");
        put("accessory", "Accessory", "Аксессуар", "Accesorio", "Akcesorium");
        put("pet", "Pet", "Питомец", "Mascota", "Zwierzak");
        put("light", "Light", "Свет", "Luz", "Światło");
        put("minecart", "Minecart", "Вагонетка", "Vagoneta", "Wagonik");
        put("mount", "Mount", "Ездовое", "Montura", "Wierzchowiec");
        put("hook", "Hook", "Крюк", "Gancho", "Hak");
        put("day", "Day", "День", "Día", "Dzień");
        put("night", "Night", "Ночь", "Noche", "Noc");
        put("dead", "You died", "Вы погибли", "Has muerto", "Nie żyjesz");
        put("equip", "Equip", "Надеть", "Equipar", "Załóż");
        put("favorite", "Favorite", "Избранное", "Favorito", "Do ulubionych");
        put("unfavorite", "Unfavorite", "Снять избранное", "Quitar favorito", "Usuń z ulubionych");
        put("split_one", "Split 1", "Взять 1", "Tomar 1", "Weź 1");
        put("split_half", "Split half", "Половину", "Tomar la mitad", "Weź połowę");
        put("drop", "Drop", "Выбросить", "Tirar", "Wyrzuć");
        put("trash", "Delete", "Удалить", "Eliminar", "Usuń");
        put("trash_confirm", "Tap again to delete", "Нажми ещё раз, чтобы удалить", "Toca otra vez para eliminar", "Dotknij ponownie, aby usunąć");
        put("close", "Close", "Закрыть", "Cerrar", "Zamknij");
        put("sort", "Sort", "Сортировка", "Ordenar", "Sortuj");
        put("heal", "Heal", "Лечение", "Curar", "Leczenie");
        put("mana_potion", "Mana", "Мана", "Maná", "Mana");
        put("buff", "Buffs", "Баффы", "Efectos", "Wzmocnienia");
        put("pick_empty", "Tap an empty slot", "Выбери пустой слот", "Toca una ranura vacía", "Dotknij pustego pola");
        put("value", "Value", "Стоимость", "Valor", "Wartość");
        put("damage", "damage", "урон", "de daño", "pkt. obrażeń");
        put("crit", "critical chance", "шанс крита", "prob. de crítico", "szansy na trafienie krytyczne");
        put("speed", "use time", "скорость", "tiempo de uso", "czas użycia");
        put("defense", "defense", "защита", "de defensa", "pkt. obrony");
        put("pick_power", "pickaxe power", "сила кирки", "potencia de pico", "mocy kilofa");
        put("axe_power", "axe power", "сила топора", "potencia de hacha", "mocy siekiery");
        put("hammer_power", "hammer power", "сила молота", "potencia de martillo", "mocy młota");
        put("heals", "restores life", "лечит", "restaura vida", "przywraca życie");
        put("restores_mana", "restores mana", "восстанавливает ману", "restaura maná", "przywraca manę");
        put("uses_mana", "uses mana", "расход маны", "consume maná", "zużywa manę");
        put("knockback", "knockback", "отбрасывание", "retroceso", "odrzut");
        put("melee", "melee", "ближний бой", "cuerpo a cuerpo", "w zwarciu");
        put("ranged", "ranged", "дальний бой", "a distancia", "dystansowe");
        put("magic", "magic", "магия", "mágico", "magia");
        put("summon", "summon", "призыв", "de invocación", "przywołanie");
        put("ingredients", "Ingredients", "Материалы", "Ingredientes", "Składniki");
        put("requires", "Requires", "Требуется", "Requiere", "Wymaga");
        put("have", "have", "есть", "tienes", "masz");
        put("hold_craft", "Hold to craft more", "Удерживай, чтобы создавать ещё", "Mantén pulsado para fabricar más", "Przytrzymaj, aby wytworzyć więcej");
        put("water", "Water nearby", "Рядом вода", "Agua cerca", "Woda w pobliżu");
        put("honey", "Honey nearby", "Рядом мёд", "Miel cerca", "Miód w pobliżu");
        put("lava", "Lava nearby", "Рядом лава", "Lava cerca", "Lawa w pobliżu");
        put("snow", "Snow biome", "Снежный биом", "Bioma nevado", "Śnieżny biom");
        put("talking", "Conversation", "Разговор", "Conversación", "Rozmowa");
        put("zone_use", "Use / Equip / Summon", "Применить / Надеть / Призвать", "Usar / Equipar / Invocar", "Użyj / Załóż / Przyzwij");
        put("zone_drop", "Drop", "Выбросить", "Tirar", "Wyrzuć");
        put("zone_trash", "Trash", "В мусор", "A la basura", "Do kosza");
        put("mod_damage", "damage", "урона", "de daño", "obrażeń");
        put("mod_crit", "critical strike chance", "шанс крит. удара", "prob. de golpe crítico", "szansy na trafienie krytyczne");
        put("mod_speed", "speed", "скорость", "velocidad", "szybkości");
        put("mod_mana", "mana cost", "расход маны", "coste de maná", "kosztu many");
        put("mod_size", "size", "размер", "tamaño", "rozmiaru");
        put("mod_velocity", "velocity", "скорость снаряда", "velocidad del proyectil", "prędkości pocisku");
        put("mod_knockback", "knockback", "отбрасывание", "retroceso", "odrzutu");
        put("zone_fav", "Favorite", "В избранное", "A favoritos", "Do ulubionych");
        put("trash_slot", "Trash", "Мусор", "Basura", "Kosz");
        put("no_room", "No free slot", "Нет свободного слота", "No hay espacio libre", "Brak wolnego miejsca");
        put("use", "Use", "Применить", "Usar", "Użyj");
        put("take", "Take", "Забрать", "Tomar", "Zabierz");
        put("deposit", "Put in chest", "В сундук", "Guardar en el cofre", "Włóż do skrzyni");
        put("chest", "Chest", "Сундук", "Cofre", "Skrzynia");
        put("piggy", "Piggy Bank", "Копилка", "Hucha", "Skarbonka");
        put("safe", "Safe", "Сейф", "Caja fuerte", "Sejf");
        put("forge", "Defender's Forge", "Кузня защитника", "Forja del defensor", "Kuźnia obrońcy");
        put("voidv", "Void Vault", "Хранилище бездны", "Bóveda del vacío", "Skarbiec pustki");
        put("c_loot", "Loot all", "Забрать всё", "Tomar todo", "Zabierz wszystko");
        put("c_deposit", "Deposit all", "Положить всё", "Guardar todo", "Włóż wszystko");
        put("c_quick", "Quick stack", "Сложить сюда", "Apilar rápido", "Dołóż do stosów");
        put("c_restock", "Restock", "Пополнить", "Reponer", "Uzupełnij");
        put("c_sort", "Sort", "Сортировать", "Ordenar", "Sortuj");
        put("c_close", "Close", "Закрыть", "Cerrar", "Zamknij");
        put("stack_of", "Stack", "Стопка", "Cantidad", "Liczba sztuk");
        put("ft", "ft", "фт", "pies", "st.");
        put("east", "East", "на восток", "al este", "na wschód");
        put("west", "West", "на запад", "al oeste", "na zachód");
        put("below", "below surface", "ниже поверхности", "bajo la superficie", "pod powierzchnią");
        put("above", "above surface", "над поверхностью", "sobre la superficie", "nad powierzchnią");
        put("wind", "Wind", "Ветер", "Viento", "Wiatr");
        put("rain_on", "Rain", "Дождь", "Lluvia", "Deszcz");
        put("buffs", "Buffs", "Эффекты", "Efectos", "Efekty");
        put("select_recipe", "Choose a recipe", "Выбери рецепт", "Elige una receta", "Wybierz recepturę");
        put("shop", "Shop", "Магазин", "Tienda", "Sklep");
        put("buy", "Buy", "Купить", "Comprar", "Kup");
        put("sell", "Sell", "Продать", "Vender", "Sprzedaj");
        put("open_shop", "Open shop", "Открыть магазин", "Abrir tienda", "Otwórz sklep");
        put("close_shop", "Close shop", "Закрыть магазин", "Cerrar tienda", "Zamknij sklep");
        put("merchant", "Merchant", "Торговец", "Comerciante", "Kupiec");
        put("Snow", "Snow", "Снег", "Nieve", "Śnieg");
        put("Jungle", "Jungle", "Джунгли", "Jungla", "Dżungla");
        put("Corrupt", "Corruption", "Порча", "Corrupción", "Skażenie");
        put("Crimson", "Crimson", "Багрянец", "Carmesí", "Szkarłat");
        put("Hallow", "Hallow", "Освящение", "Bendición", "Święta Kraina");
        put("Desert", "Desert", "Пустыня", "Desierto", "Pustynia");
        put("UndergroundDesert", "Underground Desert", "Подземная пустыня", "Desierto subterráneo", "Podziemna pustynia");
        put("Glowshroom", "Glowing Mushrooms", "Светящиеся грибы", "Setas luminosas", "Świecące grzyby");
        put("Dungeon", "Dungeon", "Данж", "Mazmorra", "Loch");
        put("Meteor", "Meteorite", "Метеорит", "Meteorito", "Meteoryt");
        put("Graveyard", "Graveyard", "Кладбище", "Cementerio", "Cmentarz");
        put("Beach", "Beach", "Пляж", "Playa", "Plaża");
        put("Hive", "Hive", "Улей", "Colmena", "Ul");
        put("LihzhardTemple", "Jungle Temple", "Храм джунглей", "Templo de la jungla", "Świątynia dżungli");
        put("Granite", "Granite", "Гранит", "Granito", "Granit");
        put("Marble", "Marble", "Мрамор", "Mármol", "Marmur");
        put("Sandstorm", "Sandstorm", "Песчаная буря", "Tormenta de arena", "Burza piaskowa");
        put("surface", "Surface", "Поверхность", "Superficie", "Powierzchnia");
        put("underground", "Underground", "Подземелье", "Subsuelo", "Podziemia");
        put("caverns", "Caverns", "Пещеры", "Cavernas", "Jaskinie");
        put("space", "Space", "Космос", "Espacio", "Kosmos");
        put("underworld", "Underworld", "Преисподняя", "Inframundo", "Piekło");
        put("language", "Language", "Язык", "Idioma", "Język");
        put("language_auto", "Automatic", "Автоматически", "Automático", "Automatycznie");
        put("medals", "Defender Medals", "Медали защитника", "Medallas del defensor", "Medale obrońcy");
    }

    private Strings() { }

    private static void put(String key, String en, String ru, String es, String pl) {
        TABLE.put(key, new String[]{en, ru, es, pl});
    }

    static Language language() {
        return language;
    }

    static void setLanguage(Language value) {
        language = value;
    }

    static String get(String key) {
        String[] value = TABLE.get(key);
        return value == null ? key : value[language.column];
    }

    static String zone(String key) {
        switch (key) {
            case "SkyHeight": return get("space");
            case "OverworldHeight": return get("surface");
            case "DirtLayerHeight": return get("underground");
            case "RockLayerHeight": return get("caverns");
            case "UnderworldHeight": return get("underworld");
            default: return get(key);
        }
    }
}
