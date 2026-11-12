package com.terraduo.companion;

import java.util.HashMap;
import java.util.Map;

/** UI strings in English and Russian. Item names come from the game itself, already localised. */
final class Strings {
    private static final Map<String, String[]> TABLE = new HashMap<>();
    static boolean russian;

    static {
        put("inventory", "Inventory", "Инвентарь");
        put("equipment", "Equipment", "Снаряжение");
        put("craft", "Craft", "Крафт");
        put("map", "Map", "Карта");
        put("hp", "Life", "Жизнь");
        put("mana", "Mana", "Мана");
        put("hide_hud", "Hide HUD", "Скрыть HUD");
        put("show_hud", "Show HUD", "Вернуть HUD");
        put("open_world", "Open a single-player world through TL Pro", "Открой одиночный мир через TL Pro");
        put("pick_target", "choose a destination slot", "выбери слот назначения");
        put("hint_drag", "Drag an item to move it", "Перетащи предмет, чтобы переместить");
        put("coins_ammo", "Coins and ammo", "Монеты и боеприпасы");
        put("armor", "Armor and accessories", "Броня и аксессуары");
        put("vanity", "Vanity", "Внешний вид");
        put("dyes", "Dyes", "Красители");
        put("pets", "Pets and mounts", "Питомцы и транспорт");
        put("pet_dyes", "Pet and mount dyes", "Красители питомцев и транспорта");
        put("locked", "Locked", "Закрыт");
        put("empty", "Empty", "Пусто");
        put("no_recipes", "No recipes available", "Нет доступных рецептов");
        put("page", "Page", "Страница");
        put("craft_one", "Craft", "Создать");
        put("map_loading", "Map is loading", "Карта загружается");
        put("to_player", "To player", "К игроку");
        put("lang_button", "RU", "EN");
        put("head", "Helmet", "Шлем");
        put("body", "Chestplate", "Нагрудник");
        put("legs", "Leggings", "Поножи");
        put("accessory", "Accessory", "Аксессуар");
        put("pet", "Pet", "Питомец");
        put("light", "Light", "Свет");
        put("minecart", "Minecart", "Вагонетка");
        put("mount", "Mount", "Ездовое");
        put("hook", "Hook", "Крюк");
        put("day", "Day", "День");
        put("night", "Night", "Ночь");
        put("dead", "You died", "Вы погибли");
        put("equip", "Equip", "Надеть");
        put("favorite", "Favorite", "Избранное");
        put("unfavorite", "Unfavorite", "Снять избранное");
        put("split_one", "Split 1", "Взять 1");
        put("split_half", "Split half", "Половину");
        put("drop", "Drop", "Выбросить");
        put("trash", "Delete", "Удалить");
        put("trash_confirm", "Tap again to delete", "Нажми ещё раз, чтобы удалить");
        put("close", "Close", "Закрыть");
        put("sort", "Sort", "Сортировка");
        put("heal", "Heal", "Лечение");
        put("mana_potion", "Mana", "Мана");
        put("buff", "Buffs", "Баффы");
        put("pick_empty", "Tap an empty slot", "Выбери пустой слот");
        put("value", "Value", "Стоимость");
        put("damage", "damage", "урон");
        put("crit", "critical chance", "шанс крита");
        put("speed", "use time", "скорость");
        put("defense", "defense", "защита");
        put("pick_power", "pickaxe power", "сила кирки");
        put("axe_power", "axe power", "сила топора");
        put("hammer_power", "hammer power", "сила молота");
        put("heals", "restores life", "лечит");
        put("restores_mana", "restores mana", "восстанавливает ману");
        put("uses_mana", "uses mana", "расход маны");
        put("knockback", "knockback", "отбрасывание");
        put("melee", "melee", "ближний бой");
        put("ranged", "ranged", "дальний бой");
        put("magic", "magic", "магия");
        put("summon", "summon", "призыв");
        put("ingredients", "Ingredients", "Материалы");
        put("requires", "Requires", "Требуется");
        put("have", "have", "есть");
        put("hold_craft", "Hold to craft more", "Удерживай, чтобы создавать ещё");
        put("water", "Water nearby", "Рядом вода");
        put("honey", "Honey nearby", "Рядом мёд");
        put("lava", "Lava nearby", "Рядом лава");
        put("snow", "Snow biome", "Снежный биом");
        put("talking", "Conversation", "Разговор");
        put("zone_use", "Use / Equip / Summon", "Применить / Надеть / Призвать");
        put("zone_drop", "Drop", "Выбросить");
        put("zone_trash", "Trash", "В мусор");
        put("mod_damage", "damage", "урона");
        put("mod_crit", "critical strike chance", "шанс крит. удара");
        put("mod_speed", "speed", "скорость");
        put("mod_mana", "mana cost", "расход маны");
        put("mod_size", "size", "размер");
        put("mod_velocity", "velocity", "скорость снаряда");
        put("mod_knockback", "knockback", "отбрасывание");
        put("zone_fav", "Favorite", "В избранное");
        put("trash_slot", "Trash", "Мусор");
        put("no_room", "No free slot", "Нет свободного слота");
        put("use", "Use", "Применить");
        put("take", "Take", "Забрать");
        put("deposit", "Put in chest", "В сундук");
        put("chest", "Chest", "Сундук");
        put("piggy", "Piggy Bank", "Копилка");
        put("safe", "Safe", "Сейф");
        put("forge", "Defender's Forge", "Кузня защитника");
        put("voidv", "Void Vault", "Хранилище бездны");
        put("c_loot", "Loot all", "Забрать всё");
        put("c_deposit", "Deposit all", "Положить всё");
        put("c_quick", "Quick stack", "Сложить сюда");
        put("c_restock", "Restock", "Пополнить");
        put("c_sort", "Sort", "Сортировать");
        put("c_close", "Close", "Закрыть");
        put("stack_of", "Stack", "Стопка");
        put("ft", "ft", "фт");
        put("east", "East", "на восток");
        put("west", "West", "на запад");
        put("below", "below surface", "ниже поверхности");
        put("above", "above surface", "над поверхностью");
        put("wind", "Wind", "Ветер");
        put("rain_on", "Rain", "Дождь");
        put("buffs", "Buffs", "Эффекты");
        put("select_recipe", "Choose a recipe", "Выбери рецепт");
    }

    private Strings() { }

    private static void put(String key, String en, String ru) {
        TABLE.put(key, new String[]{en, ru});
    }

    static String get(String key) {
        String[] value = TABLE.get(key);
        return value == null ? key : value[russian ? 1 : 0];
    }

    /** "ru-RU" -> true */
    static boolean isRussian(String culture) {
        return culture != null && culture.toLowerCase(java.util.Locale.ROOT).startsWith("ru");
    }
}
