package app.phoneledger.android.model

data class CsvPhoneRow(val label: String, val number: String, val region: String, val favorite: Boolean, val notes: String)

object CsvCodec {
    fun parsePhones(csv: String): List<CsvPhoneRow> {
        val rows = parse(csv)
        require(rows.isNotEmpty()) { "CSV is empty" }
        val headers = rows.first().map { it.trim().lowercase() }
        val numberIndex = headers.indexOf("number")
        require(numberIndex >= 0) { "CSV must contain a number column" }
        fun index(name: String) = headers.indexOf(name)
        fun value(row: List<String>, at: Int) = if (at >= 0) row.getOrElse(at) { "" }.trim() else ""
        return rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.map { row ->
            CsvPhoneRow(
                label = value(row, index("label")).ifBlank { value(row, numberIndex) },
                number = value(row, numberIndex),
                region = value(row, index("region")),
                favorite = value(row, index("favorite")).equals("true", true) || value(row, index("favorite")) == "1",
                notes = value(row, index("notes")),
            )
        }
    }

    fun exportPhones(phones: List<PhoneNumberRecord>): String = buildString {
        append("label,number,region,favorite,notes,status\n")
        phones.filter { it.deletedAt == null }.forEach {
            append(listOf(it.label, it.e164, it.region, it.favorite.toString(), it.notes, it.status.name).joinToString(",", transform = ::quote))
            append('\n')
        }
    }

    fun exportEvents(state: LedgerState): String = buildString {
        append("event_id,phone_label,phone_number,target_kind,target,method,occurred_at,detail_uri,note,origin_trust,client_package,client_signer_sha256\n")
        val phones = state.phones.associateBy { it.id }
        val targets = state.targets.associateBy { it.id }
        state.events.filter { it.deletedAt == null }.sortedByDescending { it.occurredAt }.forEach { event ->
            val phone = phones[event.phoneId]
            val target = targets[event.targetId]
            append(
                listOf(
                    event.id, phone?.label.orEmpty(), phone?.e164.orEmpty(), target?.kind?.name.orEmpty(),
                    target?.displayName.orEmpty(), event.method.name, event.occurredAt.toString(),
                    event.detailUri.orEmpty(), event.note, event.originTrust.name,
                    event.clientPackage.orEmpty(), event.clientSignerSha256.orEmpty(),
                ).joinToString(",", transform = ::quote),
            )
            append('\n')
        }
    }

    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"'); index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { row.add(field.toString()); field.clear() }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
