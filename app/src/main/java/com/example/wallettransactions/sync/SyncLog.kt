package com.example.wallettransactions.sync

import com.example.wallettransactions.R
import com.example.wallettransactions.Texts

/**
 * Человекочитаемый журнал одного прохода синка — что было отправлено на
 * сервер (push) и что применено локально (pull). Существует только на время
 * одного вызова [SyncRunner.runOnce] — не сохраняется между запусками,
 * нужен исключительно для показа в диалоге по кнопке «Синхронизировать
 * сейчас» (экран «Настройки» → «Синхронизация»), см. её javadoc.
 *
 * Считает по (направление, тип, действие) — сколько строк, а не перечисляет
 * каждую отдельно: на реальной базе с сотнями операций построчный журнал
 * был нечитаем. Тип задаёт вызывающий (`SyncEngine`, например "Счёт"/
 * "Операция"/"Курс") — то же слово, что раньше шло началом описания строки.
 *
 * Для push действие всегда общее — "Отправлено"/"Отправлено (удаление)":
 * клиент не может дёшево отличить "сервер это создал" от "сервер это
 * обновил", он лишь знает, что строка была `dirty`. Для pull действие
 * точное — "Добавлено"/"Изменено"/"Удалено": `SyncEngine` уже знает,
 * была ли строка с этим uuid локально ДО применения (см. `upsertByUuid`,
 * возвращает это как раз для этого).
 */
class SyncLog {

    private enum class Direction { PUSH, PULL }

    private data class Key(val direction: Direction, val type: String, val action: String)

    // LinkedHashMap — порядок первого появления (тип, действие) в проходе синка,
    // предсказуемый при чтении (не вперемешку по алфавиту).
    private val counts = LinkedHashMap<Key, Int>()

    // Отдельно от counts — см. healed() ниже: это не "строка отправлена/
    // применена", а "порция не прошла, но SyncEngine сам починил причину и
    // попробует её снова следующим проходом", порядок появления важен так же.
    private val healedNotes = ArrayList<String>()

    // В отличие от healedNotes — НЕ само себя лечит в этом проходе: строка
    // осталась dirty и уйдёт только следующим синком (например, у своей иконки
    // счёта нет заместителя с сервера, см. SyncEngine.PushIconRefs). Непустой
    // список — повод для SyncRunner вернуть ERROR, а не OK.
    private val problemNotes = ArrayList<String>()

    // Для уведомления о новых операциях (NewOperationsNotifier): uuid операций,
    // которых до этого pull локально не было. Свои операции телефон знает до pull,
    // так что сюда попадают только чужие — от бота, веба, другого устройства.
    private val newOperationUuids = ArrayList<String>()

    // Поток тянули с нуля (первый синк, смена кошелька, сброс курсора) — «новыми»
    // тогда оказывается вся история, уведомлять о ней нельзя.
    private var fullReload = false

    // push()/pull() — internal: заполняет только SyncEngine (тот же
    // пакет). Наружу (IntegrationsActivity, другой пакет) — только готовый
    // текст, см. toDisplayText() ниже.
    internal fun push(type: String, action: String) {
        bump(Direction.PUSH, type, action)
    }

    internal fun pull(type: String, action: String) {
        bump(Direction.PULL, type, action)
    }

    /**
     * Порция push отклонена, но `SyncEngine.pushDirty` сам разрулил
     * причину (конфликт имени счёта / протухшая ссылка, см. её javadoc,
     * "Множественные протухшие ссылки") и продолжил с остальных порций —
     * без этой записи такой случай раньше уходил только в logcat
     * (`Log.w`), а диалог «Синхронизировать сейчас» показывал пустой
     * журнал "Изменений нет", будто вообще ничего не произошло, хотя на
     * самом деле часть данных застряла и до сих пор не на сервере (реальный
     * инцидент 2026-09-14 — dev-стенд с пересозданной БД, десятки строк
     * считались уже отправленными, хотя сервер их никогда не видел).
     */
    internal fun healed(detail: String) {
        healedNotes.add(detail)
    }

    /** См. [problemNotes]. */
    internal fun problem(detail: String) {
        problemNotes.add(detail)
    }

    fun getProblems(): List<String> = problemNotes

    internal fun newOperation(uuid: String) {
        newOperationUuids.add(uuid)
    }

    internal fun fullReload() {
        fullReload = true
    }

    fun getNewOperationUuids(): List<String> = newOperationUuids

    fun isFullReload(): Boolean = fullReload

    private fun bump(direction: Direction, type: String, action: String) {
        val key = Key(direction, type, action)
        counts[key] = (counts[key] ?: 0) + 1
    }

    fun isEmpty(): Boolean = counts.isEmpty() && healedNotes.isEmpty() && problemNotes.isEmpty()

    /** Готовый многострочный текст для показа в диалоге — по строке на (тип, действие), с числом строк. */
    fun toDisplayText(): String {
        if (isEmpty()) {
            return Texts.get(R.string.sync_no_changes)
        }
        val sb = StringBuilder()
        for ((k, v) in counts) {
            sb.append(if (k.direction == Direction.PUSH) "↑ " else "↓ ") // ↑ / ↓
                .append(k.action).append(": ").append(k.type)
                .append(" × ").append(v).append('\n')
        }
        // Починки — после обычных записей: они не про "что уехало", а про
        // "что застряло, но само себя лечит" — отдельная категория внимания.
        for (note in healedNotes) {
            sb.append("⚠ ").append(note).append('\n')
        }
        for (note in problemNotes) {
            sb.append("✖ ").append(note).append('\n')
        }
        return sb.toString()
    }
}
