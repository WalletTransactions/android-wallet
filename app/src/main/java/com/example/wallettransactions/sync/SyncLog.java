package com.example.wallettransactions.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Человекочитаемый журнал одного прохода синка — что было отправлено на
 * сервер (push) и что применено локально (pull). Существует только на время
 * одного вызова {@link SyncRunner#runOnce} — не сохраняется между запусками,
 * нужен исключительно для показа в диалоге по кнопке «Синхронизировать
 * сейчас» (экран «Настройки» → «Синхронизация»), см. её javadoc.
 * <p>
 * Считает по (направление, тип, действие) — сколько строк, а не перечисляет
 * каждую отдельно: на реальной базе с сотнями операций построчный журнал
 * был нечитаем. Тип задаёт вызывающий ({@code SyncEngine}, например "Счёт"/
 * "Операция"/"Курс") — то же слово, что раньше шло началом описания строки.
 * <p>
 * Для push действие всегда общее — "Отправлено"/"Отправлено (удаление)":
 * клиент не может дёшево отличить "сервер это создал" от "сервер это
 * обновил", он лишь знает, что строка была {@code dirty}. Для pull действие
 * точное — "Добавлено"/"Изменено"/"Удалено": {@code SyncEngine} уже знает,
 * была ли строка с этим uuid локально ДО применения (см. {@code upsertByUuid},
 * возвращает это как раз для этого).
 */
public final class SyncLog {

    private enum Direction { PUSH, PULL }

    private static final class Key {
        final Direction direction;
        final String type;
        final String action;

        Key(Direction direction, String type, String action) {
            this.direction = direction;
            this.type = type;
            this.action = action;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return direction == k.direction && type.equals(k.type) && action.equals(k.action);
        }

        @Override
        public int hashCode() {
            return (direction.hashCode() * 31 + type.hashCode()) * 31 + action.hashCode();
        }
    }

    // LinkedHashMap — порядок первого появления (тип, действие) в проходе синка,
    // предсказуемый при чтении (не вперемешку по алфавиту).
    private final Map<Key, Integer> counts = new LinkedHashMap<>();

    // Отдельно от counts — см. healed() ниже: это не "строка отправлена/
    // применена", а "порция не прошла, но SyncEngine сам починил причину и
    // попробует её снова следующим проходом", порядок появления важен так же.
    private final List<String> healedNotes = new ArrayList<>();

    // В отличие от healedNotes — НЕ само себя лечит в этом проходе: строка
    // осталась dirty и уйдёт только следующим синком (например, у своей иконки
    // счёта нет заместителя с сервера, см. SyncEngine.PushIconRefs). Непустой
    // список — повод для SyncRunner вернуть ERROR, а не OK.
    private final List<String> problemNotes = new ArrayList<>();

    // Для уведомления о новых операциях (NewOperationsNotifier): uuid операций,
    // которых до этого pull локально не было. Свои операции телефон знает до pull,
    // так что сюда попадают только чужие — от бота, веба, другого устройства.
    private final List<String> newOperationUuids = new ArrayList<>();
    // Поток тянули с нуля (первый синк, смена кошелька, сброс курсора) — «новыми»
    // тогда оказывается вся история, уведомлять о ней нельзя.
    private boolean fullReload;

    // push()/pull() — package-private: заполняет только SyncEngine (тот же
    // пакет). Наружу (IntegrationsActivity, другой пакет) — только готовый
    // текст, см. toDisplayText() ниже.
    void push(String type, String action) {
        bump(Direction.PUSH, type, action);
    }

    void pull(String type, String action) {
        bump(Direction.PULL, type, action);
    }

    /**
     * Порция push отклонена, но {@code SyncEngine.pushDirty} сам разрулил
     * причину (конфликт имени счёта / протухшая ссылка, см. её javadoc,
     * "Множественные протухшие ссылки") и продолжил с остальных порций —
     * без этой записи такой случай раньше уходил только в logcat
     * ({@code Log.w}), а диалог «Синхронизировать сейчас» показывал пустой
     * журнал "Изменений нет", будто вообще ничего не произошло, хотя на
     * самом деле часть данных застряла и до сих пор не на сервере (реальный
     * инцидент 2026-09-14 — dev-стенд с пересозданной БД, десятки строк
     * считались уже отправленными, хотя сервер их никогда не видел).
     */
    void healed(String detail) {
        healedNotes.add(detail);
    }

    /** См. {@link #problemNotes}. */
    void problem(String detail) {
        problemNotes.add(detail);
    }

    public List<String> getProblems() {
        return problemNotes;
    }

    void newOperation(String uuid) {
        newOperationUuids.add(uuid);
    }

    void fullReload() {
        fullReload = true;
    }

    public List<String> getNewOperationUuids() {
        return newOperationUuids;
    }

    public boolean isFullReload() {
        return fullReload;
    }

    private void bump(Direction direction, String type, String action) {
        Key key = new Key(direction, type, action);
        counts.merge(key, 1, Integer::sum);
    }

    public boolean isEmpty() {
        return counts.isEmpty() && healedNotes.isEmpty() && problemNotes.isEmpty();
    }

    /** Готовый многострочный текст для показа в диалоге — по строке на (тип, действие), с числом строк. */
    public String toDisplayText() {
        if (isEmpty()) {
            return "Изменений нет — локальные данные уже совпадают с сервером.";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Key, Integer> e : counts.entrySet()) {
            Key k = e.getKey();
            sb.append(k.direction == Direction.PUSH ? "↑ " : "↓ ") // ↑ / ↓
              .append(k.action).append(": ").append(k.type)
              .append(" × ").append(e.getValue()).append('\n');
        }
        // Починки — после обычных записей: они не про "что уехало", а про
        // "что застряло, но само себя лечит" — отдельная категория внимания.
        for (String note : healedNotes) {
            sb.append("⚠ ").append(note).append('\n');
        }
        for (String note : problemNotes) {
            sb.append("✖ ").append(note).append('\n');
        }
        return sb.toString();
    }
}
