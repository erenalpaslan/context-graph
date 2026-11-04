package io.contextgraph.storage.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

/**
 * The property a call's receiver reads, and the declared type of the value it reads it from.
 *
 * `receiver_type` (V4) covers a receiver that names a parameter, field or local, and
 * `receiver_call` (V5) one that is another call's result. Neither covers a receiver that is a
 * property read off another value -- `useCases.loadOrder.execute()`, where `loadOrder` is a
 * property of `useCases`' type. Grouping a class's dependencies into one holder object and
 * calling through it is a common shape, and without these every call made that way resolves by
 * name alone. Pass 1 records the property's name and its owner's declared type; pass 2 finds the
 * property on that type and reads the declared type off it.
 *
 * Nullable, like the two before: NULL means the receiver was not a property read, and resolution
 * behaves exactly as it did before these columns existed.
 *
 * A Kotlin migration rather than SQL only so it can be idempotent. SQLite has no
 * `ADD COLUMN IF NOT EXISTS`, and a database whose history is rewound to before V7 -- which is
 * how V7's repair is tested -- still has these columns; adding them again would fail.
 */
class V8__Unresolved_reference_receiver_property : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val connection = context.connection
        val existing = columnsOf(connection, "unresolved_references")
        for (column in listOf("receiver_property", "receiver_property_owner")) {
            if (column !in existing) {
                connection.createStatement().use {
                    it.executeUpdate("ALTER TABLE unresolved_references ADD COLUMN $column TEXT")
                }
            }
        }
    }

    private fun columnsOf(connection: Connection, table: String): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                buildSet { while (rows.next()) add(rows.getString("name")) }
            }
        }
}
