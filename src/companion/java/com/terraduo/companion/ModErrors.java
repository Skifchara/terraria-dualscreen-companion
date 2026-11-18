package com.terraduo.companion;

import java.util.HashMap;
import java.util.Map;

/**
 * The mod reports problems as short Russian sentences (they double as message ids); this table shows them in the app's language.
 * Anything not listed (for example a raw script error) is shown as received.
 */
final class ModErrors {
    private static final Map<String, String[]> TABLE = new HashMap<>();

    private static void add(String russian, String en, String es, String pl) {
        TABLE.put(russian, new String[]{en, es, pl});
    }

    static {
        add("Эта кнопка сейчас недоступна", "That button is unavailable right now", "Ese botón no está disponible ahora", "Ten przycisk jest teraz niedostępny");
        add("Ассортимент изменился", "The stock has changed", "El surtido ha cambiado", "Asortyment się zmienił");
        add("В корзину можно класть только из сумки или сундука", "Only items from the bag or a chest can go to the trash", "Solo se pueden tirar objetos de la bolsa o de un cofre", "Do kosza można wrzucać tylko z plecaka lub skrzyni");
        add("Выбери пустой слот", "Pick an empty slot", "Elige una casilla vacía", "Wybierz pusty slot");
        add("Выбросить можно только из сумки и хотбара", "Only bag and hotbar items can be dropped", "Solo se pueden soltar objetos de la bolsa y la barra", "Wyrzucić można tylko z plecaka i paska");
        add("Дебафф нельзя снять", "A debuff can't be removed", "Un perjuicio no se puede quitar", "Debuffa nie można zdjąć");
        add("Для применения нужен пустой слот", "An empty slot is needed to use this", "Hace falta una casilla vacía para usarlo", "Do użycia potrzebny jest pusty slot");
        add("Игра не взяла предмет", "The game did not take the item", "El juego no tomó el objeto", "Gra nie przyjęła przedmiotu");
        add("Игра не завершила перенос", "The game did not finish the move", "El juego no terminó el movimiento", "Gra nie dokończyła przeniesienia");
        add("Игра оставила слоты без изменений", "The game left the slots unchanged", "El juego dejó las casillas sin cambios", "Gra pozostawiła sloty bez zmian");
        add("Игра отклонила продажу", "The game refused the sale", "El juego rechazó la venta", "Gra odrzuciła sprzedaż");
        add("Избранный предмет не выбрасывается", "A favourite item can't be dropped", "Un objeto favorito no se puede soltar", "Ulubionego przedmiotu nie można wyrzucić");
        add("Избранный предмет не удаляется", "A favourite item can't be deleted", "Un objeto favorito no se puede eliminar", "Ulubionego przedmiotu nie można usunąć");
        add("Инвентарь недоступен", "The inventory is unavailable", "El inventario no está disponible", "Ekwipunek jest niedostępny");
        add("Карта ещё загружается", "The map is still loading", "El mapa aún se está cargando", "Mapa wciąż się ładuje");
        add("Команда слишком длинная", "The command is too long", "El comando es demasiado largo", "Polecenie jest za długie");
        add("Контейнер закрыт", "The container is closed", "El contenedor está cerrado", "Pojemnik jest zamknięty");
        add("Крафт недоступен", "Crafting is unavailable", "La fabricación no está disponible", "Wytwarzanie jest niedostępne");
        add("Крафт сейчас недоступен", "Crafting is unavailable right now", "La fabricación no está disponible ahora", "Wytwarzanie jest teraz niedostępne");
        add("Магазин закрылся", "The shop has closed", "La tienda se ha cerrado", "Sklep został zamknięty");
        add("Неверная точка карты", "Invalid map point", "Punto del mapa no válido", "Nieprawidłowy punkt mapy");
        add("Неверное имя текстуры", "Invalid texture name", "Nombre de textura no válido", "Nieprawidłowa nazwa tekstury");
        add("Неверный NPC", "Invalid NPC", "PNJ no válido", "Nieprawidłowy NPC");
        add("Неверный баннер", "Invalid banner", "Estandarte no válido", "Nieprawidłowy sztandar");
        add("Неверный предмет", "Invalid item", "Objeto no válido", "Nieprawidłowy przedmiot");
        add("Неверный слот", "Invalid slot", "Casilla no válida", "Nieprawidłowy slot");
        add("Неверный спрайт", "Invalid sprite", "Sprite no válido", "Nieprawidłowy sprite");
        add("Недоступно, пока персонаж мёртв", "Unavailable while the character is dead", "No disponible mientras el personaje está muerto", "Niedostępne, gdy postać nie żyje");
        add("Неизвестная кнопка сундука", "Unknown chest button", "Botón de cofre desconocido", "Nieznany przycisk skrzyni");
        add("Неизвестная команда Terraduo", "Unknown Terraduo command", "Comando de Terraduo desconocido", "Nieznane polecenie Terraduo");
        add("Неизвестная команда жилья", "Unknown housing command", "Comando de vivienda desconocido", "Nieznane polecenie mieszkania");
        add("Неизвестная команда", "Unknown command", "Comando desconocido", "Nieznane polecenie");
        add("Неизвестная эмоция", "Unknown emote", "Emoticono desconocido", "Nieznana emotka");
        add("Неизвестное действие магазина", "Unknown shop action", "Acción de tienda desconocida", "Nieznana akcja sklepu");
        add("Неизвестное действие", "Unknown action", "Acción desconocida", "Nieznana akcja");
        add("Неизвестный NPC", "Unknown NPC", "PNJ desconocido", "Nieznany NPC");
        add("Неизвестный набор", "Unknown set", "Conjunto desconocido", "Nieznany zestaw");
        add("Неизвестный предмет", "Unknown item", "Objeto desconocido", "Nieznany przedmiot");
        add("Неизвестный спрайт", "Unknown sprite", "Sprite desconocido", "Nieznany sprite");
        add("Неизвестный шрифт", "Unknown font", "Fuente desconocida", "Nieznana czcionka");
        add("Некорректный слот корзины", "Invalid trash slot", "Casilla de basura no válida", "Nieprawidłowy slot kosza");
        add("Некорректный слот хотбара", "Invalid hotbar slot", "Casilla de la barra no válida", "Nieprawidłowy slot paska");
        add("Некорректный слот", "Invalid slot", "Casilla no válida", "Nieprawidłowy slot");
        add("Нет записи в бестиарии", "No bestiary entry", "No hay entrada en el bestiario", "Brak wpisu w bestiariuszu");
        add("Нет места для покупки", "No room for the purchase", "No hay sitio para la compra", "Brak miejsca na zakup");
        add("Нечего разделять", "Nothing to split", "Nada que dividir", "Nie ma czego dzielić");
        add("Открыт другой контейнер", "Another container is open", "Hay otro contenedor abierto", "Otwarty jest inny pojemnik");
        add("Подожди, пока освободится активный слот", "Wait until the active slot is free", "Espera a que se libere la casilla activa", "Poczekaj, aż aktywny slot się zwolni");
        add("Покупка недоступна или не хватает денег", "Can't buy this or not enough money", "No se puede comprar o falta dinero", "Zakup niemożliwy lub brak pieniędzy");
        add("Положить можно из сумки в открытый контейнер", "Items can be moved from the bag into the open container", "Se pueden pasar objetos de la bolsa al contenedor abierto", "Można przenosić z plecaka do otwartego pojemnika");
        add("Предмет из корзины сейчас недоступен", "The trashed item is unavailable right now", "El objeto de la basura no está disponible ahora", "Przedmiot z kosza jest teraz niedostępny");
        add("Предмет не подходит в этот слот", "The item doesn't fit this slot", "El objeto no cabe en esta casilla", "Przedmiot nie pasuje do tego slotu");
        add("Предмет нельзя надеть", "This item can't be equipped", "Este objeto no se puede equipar", "Tego przedmiotu nie można założyć");
        add("Пустое сообщение", "Empty message", "Mensaje vacío", "Pusta wiadomość");
        add("Разделять можно только внутри сумки", "Stacks can only be split inside the bag", "Solo se puede dividir dentro de la bolsa", "Dzielić można tylko w plecaku");
        add("Рецепт больше недоступен", "The recipe is no longer available", "La receta ya no está disponible", "Przepis nie jest już dostępny");
        add("Слот изменился. Открой предмет снова", "The slot changed. Open the item again", "La casilla cambió. Abre el objeto otra vez", "Slot się zmienił. Otwórz przedmiot ponownie");
        add("Слот красителя закрыт", "The dye slot is locked", "La casilla de tinte está bloqueada", "Slot barwnika jest zablokowany");
        add("Слот пуст", "The slot is empty", "La casilla está vacía", "Slot jest pusty");
        add("Слот сейчас занят действием игры", "The slot is busy with a game action", "La casilla está ocupada por una acción del juego", "Slot jest zajęty przez akcję gry");
        add("Слот снаряжения закрыт", "The equipment slot is locked", "La casilla de equipo está bloqueada", "Slot wyposażenia jest zablokowany");
        add("Слоты изменились. Выбери предмет снова", "The slots changed. Pick the item again", "Las casillas cambiaron. Elige el objeto otra vez", "Sloty się zmieniły. Wybierz przedmiot ponownie");
        add("Содержимое корзины изменилось", "The trash contents changed", "El contenido de la basura cambió", "Zawartość kosza się zmieniła");
        add("Спрайт вне диапазона", "Sprite out of range", "Sprite fuera de rango", "Sprite poza zakresem");
        add("Текстура ещё не загружена", "The texture is not loaded yet", "La textura aún no está cargada", "Tekstura nie jest jeszcze załadowana");
        add("Торговля сейчас недоступна", "Trading is unavailable right now", "El comercio no está disponible ahora", "Handel jest teraz niedostępny");
        add("У этого NPC нет магазина", "This NPC has no shop", "Este PNJ no tiene tienda", "Ten NPC nie ma sklepu");
        add("Хотбар недоступен", "The hotbar is unavailable", "La barra de acceso no está disponible", "Pasek jest niedostępny");
        add("Цена изменилась", "The price has changed", "El precio ha cambiado", "Cena się zmieniła");
        add("Это не контейнер", "This is not a container", "Esto no es un contenedor", "To nie jest pojemnik");
        add("Этого баффа уже нет", "That buff is already gone", "Ese beneficio ya no está", "Tego buffa już nie ma");
        add("Этого жителя больше нет", "This resident is gone", "Este aldeano ya no está", "Tego mieszkańca już nie ma");
        add("Этого жителя нет рядом", "This resident is not nearby", "Este aldeano no está cerca", "Tego mieszkańca nie ma w pobliżu");
        add("Этот лечебный предмет сейчас недоступен", "This healing item is unavailable right now", "Este objeto de curación no está disponible ahora", "Ten przedmiot leczący jest teraz niedostępny");
        add("Этот предмет для маны сейчас недоступен", "This mana item is unavailable right now", "Este objeto de maná no está disponible ahora", "Ten przedmiot many jest teraz niedostępny");
        add("Этот предмет нельзя применить отсюда", "This item can't be used from here", "Este objeto no se puede usar desde aquí", "Tego przedmiotu nie można użyć stąd");
        add("Этот предмет нельзя продать", "This item can't be sold", "Este objeto no se puede vender", "Tego przedmiotu nie można sprzedać");
        add("Этот эффект сейчас недоступен", "This effect is unavailable right now", "Este efecto no está disponible ahora", "Ten efekt jest teraz niedostępny");
        add("Игра пока удерживает текущий слот", "The game is still holding the current slot", "El juego aún mantiene la casilla actual", "Gra wciąż trzyma obecny slot");
    }

    private ModErrors() { }

    /** The message in the app's language, or the original text when it is not a known mod message. */
    static String translate(String text) {
        if (text == null) return "";
        String[] row = TABLE.get(text);
        if (row == null) return text;
        switch (Strings.language().code) {
            case "en": return row[0];
            case "es": return row[1];
            case "pl": return row[2];
            default: return text;
        }
    }
}
