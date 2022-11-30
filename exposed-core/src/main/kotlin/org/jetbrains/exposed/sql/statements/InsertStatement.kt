package org.jetbrains.exposed.sql.statements

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.jetbrains.exposed.sql.vendors.inProperCase
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.properties.Delegates

open class InsertStatement<Key : Any>(val table: Table, val isIgnore: Boolean = false) : UpdateBuilder<Int>(StatementType.INSERT, listOf(table)) {

    var insertedCount: Int by Delegates.notNull()

    var resultedValues: List<ResultRow>? = null
        private set

    infix operator fun <T> get(column: Column<T>): T {
        val row = resultedValues?.firstOrNull() ?: error("No key generated")
        return row[column]
    }

    infix operator fun <T> get(column: CompositeColumn<T>): T {
        val row = resultedValues?.firstOrNull() ?: error("No key generated")
        return row[column]
    }

    fun <T> getOrNull(column: Column<T>): T? = resultedValues?.firstOrNull()?.getOrNull(column)

    @Suppress("NestedBlockDepth")
    private fun processResults(rs: ResultSet?, inserted: Int): List<ResultRow> {
        val autoGeneratedKeys = arrayListOf<MutableMap<Column<*>, Any?>>()

        if (inserted > 0) {
            val returnedColumns = (if (currentDialect.supportsOnlyIdentifiersInGeneratedKeys) autoIncColumns else table.columns).mapNotNull { col ->
                @Suppress("SwallowedException")
                try {
                    rs?.findColumn(col.name)?.let { col to it }
                } catch (e: SQLException) {
                    null
                }
            }

            val firstAutoIncColumn = autoIncColumns.firstOrNull { it.autoIncColumnType != null } ?: autoIncColumns.firstOrNull()
            if (firstAutoIncColumn != null || returnedColumns.isNotEmpty()) {
                while (rs?.next() == true) {
                    val returnedValues = returnedColumns.associateTo(mutableMapOf()) { it.first to rs.getObject(it.second) }
                    if (returnedValues.isEmpty() && firstAutoIncColumn != null) returnedValues[firstAutoIncColumn] = rs.getObject(1)
                    autoGeneratedKeys.add(returnedValues)
                }

                if (inserted > 1 && firstAutoIncColumn != null && autoGeneratedKeys.isNotEmpty() && !currentDialect.supportsMultipleGeneratedKeys) {
                    // H2/SQLite only returns one last generated key...
                    (autoGeneratedKeys[0][firstAutoIncColumn] as? Number)?.toLong()?.let {
                        var id = it

                        while (autoGeneratedKeys.size < inserted) {
                            id -= 1
                            autoGeneratedKeys.add(0, mutableMapOf(firstAutoIncColumn to id))
                        }
                    }
                }

                /** TODO: https://github.com/JetBrains/Exposed/issues/129
                 *  doesn't work with MySQL `INSERT ... ON DUPLICATE UPDATE`
                 */
//            assert(isIgnore || autoGeneratedKeys.isEmpty() || autoGeneratedKeys.size == inserted) {
//                "Number of autoincs (${autoGeneratedKeys.size}) doesn't match number of batch entries ($inserted)"
//            }
            }
        }

        arguments!!.forEachIndexed { itemIndx, pairs ->
            val map = autoGeneratedKeys.getOrNull(itemIndx) ?: hashMapOf<Column<*>, Any?>().apply {
                autoGeneratedKeys.add(itemIndx, this)
            }
            pairs.forEach { (col, value) ->
                if (value != DefaultValueMarker) {
                    if (col.columnType.isAutoInc || value is NextVal<*>) map.getOrPut(col) { value }
                    else map[col] = value
                }
            }

//            pairs.filter{ it.second != DefaultValueMarker }.forEach { (col, value) ->
//                map.getOrPut(col){ value }
//            }
        }
        @Suppress("UNCHECKED_CAST")
        return autoGeneratedKeys.map { ResultRow.createAndFillValues(it as Map<Expression<*>, Any?>) }
    }

    protected open fun valuesAndDefaults(values: Map<Column<*>, Any?> = this.values): Map<Column<*>, Any?> {
        val result = values.toMutableMap()
        targets.forEach { table ->
            table.columns.forEach { column ->
                if ((column.dbDefaultValue != null || column.defaultValueFun != null) && column !in values.keys) {
                    val value = when {
                        column.defaultValueFun != null -> column.defaultValueFun!!()
                        else -> DefaultValueMarker
                    }
                    result[column] = value
                }
            }
        }
        return result
    }

    override fun prepareSQL(transaction: Transaction): String {
        val builder = QueryBuilder(true)
        val values = arguments!!.first()
        val sql = if (values.isEmpty()) ""
        else with(builder) {
            values.appendTo(prefix = "VALUES (", postfix = ")") { (col, value) ->
                registerArgument(col, value)
            }
            toString()
        }
        return transaction.db.dialect.functionProvider.insert(isIgnore, table, values.map { it.first }, sql, transaction)
    }

    protected open fun PreparedStatementApi.execInsertFunction(): Pair<Int, ResultSet?> {
        val inserted = if (arguments().count() > 1 || isAlwaysBatch) executeBatch().sum() else executeUpdate()
        val rs = if (autoIncColumns.isNotEmpty()) {
            resultSet
        } else null
        return inserted to rs
    }

    override fun PreparedStatementApi.executeInternal(transaction: Transaction): Int {
        val (inserted, rs) = execInsertFunction()
        return inserted.apply {
            insertedCount = this
            resultedValues = processResults(rs, this)
        }
    }

    protected val autoIncColumns: List<Column<*>>
        get() {
            val nextValExpressionColumns = values.filterValues { it is NextVal<*> }.keys
            return targets.flatMap { it.columns }.filter { column ->
                when {
                    column.autoIncColumnType?.nextValExpression != null -> currentDialect.supportsSequenceAsGeneratedKeys
                    column.columnType.isAutoInc -> true
                    column in nextValExpressionColumns -> currentDialect.supportsSequenceAsGeneratedKeys
                    column.columnType is EntityIDColumnType<*> -> !currentDialect.supportsOnlyIdentifiersInGeneratedKeys
                    else -> false
                }
            }
        }

    override fun prepared(transaction: Transaction, sql: String): PreparedStatementApi = when {
        // https://github.com/pgjdbc/pgjdbc/issues/1168
        // Column names always escaped/quoted in RETURNING clause
        autoIncColumns.isNotEmpty() && currentDialect is PostgreSQLDialect ->
            transaction.connection.prepareStatement(sql, true)

        autoIncColumns.isNotEmpty() ->
            // http://viralpatel.net/blogs/oracle-java-jdbc-get-primary-key-insert-sql/
            transaction.connection.prepareStatement(sql, autoIncColumns.map { it.name.inProperCase() }.toTypedArray())

        else -> transaction.connection.prepareStatement(sql, false)
    }

    protected open var arguments: List<List<Pair<Column<*>, Any?>>>? = null
        get() = field ?: run {
            val nullableColumns = table.columns.filter { it.columnType.nullable }
            val valuesAndDefaults = valuesAndDefaults() as MutableMap
            valuesAndDefaults.putAll((nullableColumns - valuesAndDefaults.keys).associateWith { null })
            val result = valuesAndDefaults.toList().sortedBy { it.first }
            listOf(result).apply { field = this }
        }

    override fun arguments(): List<Iterable<Pair<IColumnType, Any?>>> {
        return arguments!!.map { args ->
            val builder = QueryBuilder(true)
            args.filter { (_, value) ->
                value != DefaultValueMarker
            }.forEach { (column, value) ->
                builder.registerArgument(column, value)
            }
            builder.args
        }
    }
}
