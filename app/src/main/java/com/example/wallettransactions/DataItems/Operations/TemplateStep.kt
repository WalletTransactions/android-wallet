package com.example.wallettransactions.DataItems.Operations

import java.math.BigDecimal
import java.util.UUID

/**
 * Шаг шаблона операций — одна проводка «откуда → куда» со своим режимом суммы
 * (openspec operation-templates, «Режимы суммы шага»). Строка `operation_template_step`.
 *
 * Счета — только id, как и у шаблона в целом: генератор работает без справочника
 * счетов в памяти.
 */
class TemplateStep {

    /** Значения хранятся в `operation_template_step.amount_mode` — код существующего менять нельзя. */
    enum class AmountMode(val value: Int) {
        /** Число больше нуля, как есть. */
        FIXED(0),
        /** Доля от общей суммы запуска, (0; 100]. */
        PERCENT(1),
        /** Общая сумма минус все остальные шаги; ровно один на шаблон. */
        REST(2);

        companion object {
            @JvmStatic
            fun fromValue(value: Int): AmountMode {
                for (m in values()) if (m.value == value) return m
                return REST
            }
        }
    }

    @JvmField
    var id: Int = 0

    @JvmField
    var uuid: String = UUID.randomUUID().toString()

    /** Порядок шага, с 1; на него ссылается [commissionOfNum] других шагов. */
    @JvmField
    var num: Int = 1

    @JvmField
    var accFromId: Int = 0

    @JvmField
    var accToId: Int = 0

    @JvmField
    var amountMode: AmountMode = AmountMode.REST

    /** Число для [AmountMode.FIXED] и [AmountMode.PERCENT]; у остатка — null. */
    @JvmField
    var amountValue: BigDecimal? = null

    /** Комментарий проводки; пустой — берётся комментарий шаблона. */
    @JvmField
    var comment: String = ""

    /** Номер основного шага, если этот шаг — комиссия к нему (`operation_link.type = 1`). */
    @JvmField
    var commissionOfNum: Int? = null

    val isCommission: Boolean get() = commissionOfNum != null

    fun copy(): TemplateStep {
        val s = TemplateStep()
        s.id = id
        s.uuid = uuid
        s.num = num
        s.accFromId = accFromId
        s.accToId = accToId
        s.amountMode = amountMode
        s.amountValue = amountValue
        s.comment = comment
        s.commissionOfNum = commissionOfNum
        return s
    }
}
