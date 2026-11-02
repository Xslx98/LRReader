package com.lanraragi.reader.tankoubon

/**
 * A tank as the batch picker saw it when the user chose it. [archives] empty
 * means it had no members at pick time (a just-created tank never does), so
 * the batch add can seed its cover after the first successful add.
 */
data class PickedTank(val id: String, val name: String, val archives: List<String> = emptyList())
