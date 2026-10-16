/*
 * Copyright (c) 2012 - 2015 Ngewi Fet <ngewif@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnucash.android.db

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import org.gnucash.android.db.DatabaseSchema.AccountEntry
import org.gnucash.android.db.DatabaseSchema.BudgetAmountEntry
import org.gnucash.android.db.DatabaseSchema.BudgetEntry
import org.gnucash.android.db.DatabaseSchema.CommodityEntry
import org.gnucash.android.db.DatabaseSchema.CommonColumns
import org.gnucash.android.db.DatabaseSchema.PriceEntry
import org.gnucash.android.db.DatabaseSchema.RecurrenceEntry
import org.gnucash.android.db.DatabaseSchema.ScheduledActionEntry
import org.gnucash.android.db.DatabaseSchema.SearchTemplateEntry
import org.gnucash.android.db.DatabaseSchema.SplitEntry
import org.gnucash.android.db.DatabaseSchema.SyncResolutionEntry
import org.gnucash.android.db.DatabaseSchema.SyncStateEntry
import org.gnucash.android.db.DatabaseSchema.TagEntry
import org.gnucash.android.db.DatabaseSchema.TombstoneEntry
import org.gnucash.android.db.DatabaseSchema.TransactionEntry
import org.gnucash.android.db.DatabaseSchema.TransactionTagEntry
import org.gnucash.android.db.DatabaseSchema.TransactionTemplateEntry
import org.gnucash.android.db.MigrationHelper.importCommodities
import org.gnucash.android.db.MigrationHelper.migrate
import org.gnucash.android.model.Commodity
import timber.log.Timber

/**
 * Helper class for managing the SQLite database.
 * Creates the database and handles upgrades
 *
 * @author Ngewi Fet <ngewif@gmail.com>
 */
class DatabaseHelper(private val context: Context, databaseName: String) :
    SQLiteOpenHelper(context, databaseName, null, DatabaseSchema.DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        val holder = DatabaseHolder(context, db)
        createDatabaseTables(holder)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA foreign_keys=ON")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        migrate(context, db, oldVersion, newVersion)
    }

    /**
     * Creates the tables in the database and import default commodities into the database
     *
     * @param holder Database holder
     */
    private fun createDatabaseTables(holder: DatabaseHolder) {
        Timber.i("Creating database tables for db ${holder.name}")
        val db = holder.db
        db.execSQL(ACCOUNTS_TABLE_CREATE)
        db.execSQL(TRANSACTIONS_TABLE_CREATE)
        db.execSQL(SPLITS_TABLE_CREATE)
        db.execSQL(SCHEDULED_ACTIONS_TABLE_CREATE)
        db.execSQL(COMMODITIES_TABLE_CREATE)
        db.execSQL(PRICES_TABLE_CREATE)
        db.execSQL(RECURRENCE_TABLE_CREATE)
        db.execSQL(BUDGETS_TABLE_CREATE)
        db.execSQL(BUDGET_AMOUNTS_TABLE_CREATE)
        db.execSQL(SEARCH_TEMPLATES_TABLE_CREATE)
        db.execSQL(TAGS_TABLE_CREATE)
        db.execSQL(TRANSACTION_TAGS_TABLE_CREATE)
        db.execSQL(SYNC_STATE_TABLE_CREATE)
        db.execSQL(TOMBSTONES_TABLE_CREATE)
        db.execSQL(SYNC_RESOLUTIONS_TABLE_CREATE)
        db.execSQL(TRANSACTION_TEMPLATES_TABLE_CREATE)

        db.execSQL(createUpdatedAtTrigger(AccountEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(TransactionEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(SplitEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(ScheduledActionEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(CommodityEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(PriceEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(RecurrenceEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(BudgetEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(BudgetAmountEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(TagEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(SyncStateEntry.TABLE_NAME))
        db.execSQL(createUpdatedAtTrigger(TombstoneEntry.TABLE_NAME))

        val createAccountUidIndex = ("CREATE UNIQUE INDEX '" + AccountEntry.INDEX_UID + "' ON "
                + AccountEntry.TABLE_NAME + "(" + AccountEntry.COLUMN_UID + ")")

        val createTransactionUidIndex =
            ("CREATE UNIQUE INDEX '" + TransactionEntry.INDEX_UID + "' ON "
                    + TransactionEntry.TABLE_NAME + "(" + TransactionEntry.COLUMN_UID + ")")

        val createSplitUidIndex = ("CREATE UNIQUE INDEX '" + SplitEntry.INDEX_UID + "' ON "
                + SplitEntry.TABLE_NAME + "(" + SplitEntry.COLUMN_UID + ")")

        val createScheduledEventUidIndex = ("CREATE UNIQUE INDEX '" + ScheduledActionEntry.INDEX_UID
                + "' ON " + ScheduledActionEntry.TABLE_NAME + "(" + ScheduledActionEntry.COLUMN_UID + ")")

        val createCommodityUidIndex = ("CREATE UNIQUE INDEX '" + CommodityEntry.INDEX_UID
                + "' ON " + CommodityEntry.TABLE_NAME + "(" + CommodityEntry.COLUMN_UID + ")")

        val createPriceUidIndex = ("CREATE UNIQUE INDEX '" + PriceEntry.INDEX_UID
                + "' ON " + PriceEntry.TABLE_NAME + "(" + PriceEntry.COLUMN_UID + ")")

        val createBudgetUidIndex = ("CREATE UNIQUE INDEX '" + BudgetEntry.INDEX_UID
                + "' ON " + BudgetEntry.TABLE_NAME + "(" + BudgetEntry.COLUMN_UID + ")")

        val createBudgetAmountUidIndex = ("CREATE UNIQUE INDEX '" + BudgetAmountEntry.INDEX_UID
                + "' ON " + BudgetAmountEntry.TABLE_NAME + "(" + BudgetAmountEntry.COLUMN_UID + ")")

        val createRecurrenceUidIndex = ("CREATE UNIQUE INDEX '" + RecurrenceEntry.INDEX_UID
                + "' ON " + RecurrenceEntry.TABLE_NAME + "(" + RecurrenceEntry.COLUMN_UID + ")")

        db.execSQL(createAccountUidIndex)
        db.execSQL(createTransactionUidIndex)
        db.execSQL(createSplitUidIndex)
        db.execSQL(createScheduledEventUidIndex)
        db.execSQL(createCommodityUidIndex)
        db.execSQL(createPriceUidIndex)
        db.execSQL(createBudgetUidIndex)
        db.execSQL(createRecurrenceUidIndex)
        db.execSQL(createBudgetAmountUidIndex)
        for (statement in TAG_INDEX_STATEMENTS) {
            db.execSQL(statement)
        }
        for (statement in SYNC_INDEX_STATEMENTS) {
            db.execSQL(statement)
        }
        for (statement in SYNC_RESOLUTION_INDEX_STATEMENTS) {
            db.execSQL(statement)
        }
        createResetBalancesTriggers(db)

        try {
            importCommodities(holder)
        } catch (e: Exception) {
            val msg = "Error loading currencies into the database"
            Timber.e(e, msg)
            throw SQLiteException(msg, e)
        }
    }

    val holder: DatabaseHolder
        get() = DatabaseHolder(context, writableDatabase, databaseName)

    val readableHolder: DatabaseHolder
        get() = DatabaseHolder(context, readableDatabase, databaseName)

    companion object {
        /**
         * SQL statement to create the accounts table in the database
         */
        private const val ACCOUNTS_TABLE_CREATE = ("create table " + AccountEntry.TABLE_NAME + " ("
                + AccountEntry.COLUMN_ID + " integer primary key autoincrement, "
                + AccountEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                + AccountEntry.COLUMN_NAME + " varchar(255) not null, "
                + AccountEntry.COLUMN_TYPE + " varchar(255) not null, "
                + AccountEntry.COLUMN_CURRENCY + " varchar(255), "
                + AccountEntry.COLUMN_COMMODITY_UID + " varchar(255) not null, "
                + AccountEntry.COLUMN_CODE + " varchar(255), "
                + AccountEntry.COLUMN_DESCRIPTION + " varchar(255), "
                + AccountEntry.COLUMN_COLOR_CODE + " varchar(255), "
                + AccountEntry.COLUMN_FAVORITE + " tinyint default 0, "
                + AccountEntry.COLUMN_HIDDEN + " tinyint default 0, "
                + AccountEntry.COLUMN_FULL_NAME + " varchar(255), "
                + AccountEntry.COLUMN_PLACEHOLDER + " tinyint default 0, "
                + AccountEntry.COLUMN_PARENT_ACCOUNT_UID + " varchar(255), "
                + AccountEntry.COLUMN_DEFAULT_TRANSFER_ACCOUNT_UID + " varchar(255), "
                + AccountEntry.COLUMN_NOTES + " text, "
                + AccountEntry.COLUMN_BALANCE + " varchar(255), "
                + AccountEntry.COLUMN_CLEARED_BALANCE + " varchar(255), "
                + AccountEntry.COLUMN_NOCLOSING_BALANCE + " varchar(255), "
                + AccountEntry.COLUMN_RECONCILED_BALANCE + " varchar(255), "
                + AccountEntry.COLUMN_TEMPLATE + " tinyint default 0, "
                + AccountEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + AccountEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "FOREIGN KEY (" + AccountEntry.COLUMN_COMMODITY_UID + ") REFERENCES " + CommodityEntry.TABLE_NAME + " (" + CommodityEntry.COLUMN_UID + ") "
                + ");"
                )

        /**
         * SQL statement to create the transactions table in the database
         */
        private const val TRANSACTIONS_TABLE_CREATE =
            ("create table " + TransactionEntry.TABLE_NAME + " ("
                    + TransactionEntry.COLUMN_ID + " integer primary key autoincrement, "
                    + TransactionEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                    + TransactionEntry.COLUMN_DESCRIPTION + " varchar(255), "
                    + TransactionEntry.COLUMN_NOTES + " text, "
                    + TransactionEntry.COLUMN_DATE_POSTED + " integer not null, "
                    + TransactionEntry.COLUMN_EXPORTED + " tinyint default 0, "
                    + TransactionEntry.COLUMN_TEMPLATE + " tinyint default 0, "
                    + TransactionEntry.COLUMN_CURRENCY + " varchar(255), "
                    + TransactionEntry.COLUMN_COMMODITY_UID + " varchar(255) not null, "
                    + TransactionEntry.COLUMN_SCHEDX_ACTION_UID + " varchar(255), "
                    + TransactionEntry.COLUMN_NUMBER + " varchar(255), "
                    + TransactionEntry.COLUMN_DATE_ENTERED + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + TransactionEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (" + TransactionEntry.COLUMN_SCHEDX_ACTION_UID + ") REFERENCES " + ScheduledActionEntry.TABLE_NAME + " (" + ScheduledActionEntry.COLUMN_UID + ") ON DELETE SET NULL, "
                    + "FOREIGN KEY (" + TransactionEntry.COLUMN_COMMODITY_UID + ") REFERENCES " + CommodityEntry.TABLE_NAME + " (" + CommodityEntry.COLUMN_UID + ") "
                    + ");"
                    )

        /**
         * SQL statement to create the transaction splits table
         */
        private const val SPLITS_TABLE_CREATE = ("CREATE TABLE " + SplitEntry.TABLE_NAME + " ("
                + SplitEntry.COLUMN_ID + " integer primary key autoincrement, "
                + SplitEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                + SplitEntry.COLUMN_MEMO + " text, "
                + SplitEntry.COLUMN_TYPE + " varchar(255) not null, "
                + SplitEntry.COLUMN_VALUE_NUM + " integer not null, "
                + SplitEntry.COLUMN_VALUE_DENOM + " integer not null, "
                + SplitEntry.COLUMN_QUANTITY_NUM + " integer not null, "
                + SplitEntry.COLUMN_QUANTITY_DENOM + " integer not null, "
                + SplitEntry.COLUMN_ACCOUNT_UID + " varchar(255) not null, "
                + SplitEntry.COLUMN_TRANSACTION_UID + " varchar(255) not null, "
                + SplitEntry.COLUMN_RECONCILE_STATE + " varchar(1) not null default 'n', "
                + SplitEntry.COLUMN_RECONCILE_DATE + " timestamp not null default current_timestamp, "
                + SplitEntry.COLUMN_SCHEDX_ACTION_ACCOUNT_UID + " varchar(255), "
                + SplitEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + SplitEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "FOREIGN KEY (" + SplitEntry.COLUMN_ACCOUNT_UID + ") REFERENCES " + AccountEntry.TABLE_NAME + " (" + AccountEntry.COLUMN_UID + ") ON DELETE CASCADE, "
                + "FOREIGN KEY (" + SplitEntry.COLUMN_TRANSACTION_UID + ") REFERENCES " + TransactionEntry.TABLE_NAME + " (" + TransactionEntry.COLUMN_UID + ") ON DELETE CASCADE "
                + ");"
                )


        private const val SCHEDULED_ACTIONS_TABLE_CREATE =
            ("CREATE TABLE " + ScheduledActionEntry.TABLE_NAME + " ("
                    + ScheduledActionEntry.COLUMN_ID + " integer primary key autoincrement, "
                    + ScheduledActionEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                    + ScheduledActionEntry.COLUMN_ACTION_UID + " varchar(255) not null, "
                    + ScheduledActionEntry.COLUMN_TYPE + " varchar(255) not null, "
                    + ScheduledActionEntry.COLUMN_NAME + " varchar(255), "
                    + ScheduledActionEntry.COLUMN_RECURRENCE_UID + " varchar(255) not null, "
                    + ScheduledActionEntry.COLUMN_TEMPLATE_ACCT_UID + " varchar(255) not null, "
                    + ScheduledActionEntry.COLUMN_LAST_OCCUR + " integer default 0, "
                    + ScheduledActionEntry.COLUMN_START_TIME + " integer not null, "
                    + ScheduledActionEntry.COLUMN_END_TIME + " integer default 0, "
                    + ScheduledActionEntry.COLUMN_TAG + " text, "
                    + ScheduledActionEntry.COLUMN_ENABLED + " tinyint default 1, " //enabled by default
                    + ScheduledActionEntry.COLUMN_AUTO_CREATE + " tinyint default 1, "
                    + ScheduledActionEntry.COLUMN_AUTO_NOTIFY + " tinyint default 0, "
                    + ScheduledActionEntry.COLUMN_ADVANCE_CREATION + " integer default 0, "
                    + ScheduledActionEntry.COLUMN_ADVANCE_NOTIFY + " integer default 0, "
                    + ScheduledActionEntry.COLUMN_TOTAL_FREQUENCY + " integer default 0, "
                    + ScheduledActionEntry.COLUMN_INSTANCE_COUNT + " integer default 0, "
                    + ScheduledActionEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + ScheduledActionEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (" + ScheduledActionEntry.COLUMN_RECURRENCE_UID + ") REFERENCES " + RecurrenceEntry.TABLE_NAME + " (" + RecurrenceEntry.COLUMN_UID + ") "
                    + ");"
                    )

        private const val COMMODITIES_TABLE_CREATE = ("CREATE TABLE " + CommodityEntry.TABLE_NAME + " ("
                + CommodityEntry.COLUMN_ID + " integer primary key autoincrement, "
                + CommodityEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                + CommodityEntry.COLUMN_NAMESPACE + " varchar(255) not null default '" + Commodity.COMMODITY_CURRENCY + "', "
                + CommodityEntry.COLUMN_FULLNAME + " varchar(255) not null, "
                + CommodityEntry.COLUMN_MNEMONIC + " varchar(255) not null, "
                + CommodityEntry.COLUMN_LOCAL_SYMBOL + " varchar(255) not null default '', "
                + CommodityEntry.COLUMN_CUSIP + " varchar(255), "
                + CommodityEntry.COLUMN_SMALLEST_FRACTION + " integer not null, "
                + CommodityEntry.COLUMN_QUOTE_FLAG + " tinyint not null default 0, "
                + CommodityEntry.COLUMN_QUOTE_SOURCE + " varchar(255), "
                + CommodityEntry.COLUMN_QUOTE_TZ + " varchar(100), "
                + CommodityEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + CommodityEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP "
                + ");"
                )

        /**
         * SQL statement to create the commodity prices table
         */
        internal const val PRICES_TABLE_CREATE = ("CREATE TABLE " + PriceEntry.TABLE_NAME + " ("
                + PriceEntry.COLUMN_ID + " integer primary key autoincrement, "
                + PriceEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                + PriceEntry.COLUMN_SECURITY_UID + " varchar(255) not null, "
                + PriceEntry.COLUMN_CURRENCY_UID + " varchar(255) not null, "
                + PriceEntry.COLUMN_TYPE + " varchar(255), "
                + PriceEntry.COLUMN_DATE + " TIMESTAMP not null, "
                + PriceEntry.COLUMN_SOURCE + " text, "
                + PriceEntry.COLUMN_VALUE_NUM + " integer not null, "
                + PriceEntry.COLUMN_VALUE_DENOM + " integer not null, "
                + PriceEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + PriceEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "FOREIGN KEY (" + PriceEntry.COLUMN_SECURITY_UID + ") REFERENCES " + CommodityEntry.TABLE_NAME + " (" + CommodityEntry.COLUMN_UID + ") ON DELETE CASCADE, "
                + "FOREIGN KEY (" + PriceEntry.COLUMN_CURRENCY_UID + ") REFERENCES " + CommodityEntry.TABLE_NAME + " (" + CommodityEntry.COLUMN_UID + ") ON DELETE CASCADE "
                + ");"
                )


        private const val BUDGETS_TABLE_CREATE = ("CREATE TABLE " + BudgetEntry.TABLE_NAME + " ("
                + BudgetEntry.COLUMN_ID + " integer primary key autoincrement, "
                + BudgetEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                + BudgetEntry.COLUMN_NAME + " varchar(255) not null, "
                + BudgetEntry.COLUMN_DESCRIPTION + " varchar(255), "
                + BudgetEntry.COLUMN_RECURRENCE_UID + " varchar(255) not null, "
                + BudgetEntry.COLUMN_NUM_PERIODS + " integer, "
                + BudgetEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + BudgetEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "FOREIGN KEY (" + BudgetEntry.COLUMN_RECURRENCE_UID + ") REFERENCES " + RecurrenceEntry.TABLE_NAME + " (" + RecurrenceEntry.COLUMN_UID + ") "
                + ");"
                )

        private const val BUDGET_AMOUNTS_TABLE_CREATE =
            ("CREATE TABLE " + BudgetAmountEntry.TABLE_NAME + " ("
                    + BudgetAmountEntry.COLUMN_ID + " integer primary key autoincrement, "
                    + BudgetAmountEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                    + BudgetAmountEntry.COLUMN_BUDGET_UID + " varchar(255) not null, "
                    + BudgetAmountEntry.COLUMN_ACCOUNT_UID + " varchar(255) not null, "
                    + BudgetAmountEntry.COLUMN_AMOUNT_NUM + " integer not null, "
                    + BudgetAmountEntry.COLUMN_AMOUNT_DENOM + " integer not null, "
                    + BudgetAmountEntry.COLUMN_PERIOD_NUM + " integer not null, "
                    + BudgetAmountEntry.COLUMN_NOTES + " text, "
                    + BudgetAmountEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + BudgetAmountEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (" + BudgetAmountEntry.COLUMN_ACCOUNT_UID + ") REFERENCES " + AccountEntry.TABLE_NAME + " (" + AccountEntry.COLUMN_UID + ") ON DELETE CASCADE, "
                    + "FOREIGN KEY (" + BudgetAmountEntry.COLUMN_BUDGET_UID + ") REFERENCES " + BudgetEntry.TABLE_NAME + " (" + BudgetEntry.COLUMN_UID + ") ON DELETE CASCADE "
                    + ");"
                    )


        private const val RECURRENCE_TABLE_CREATE = ("CREATE TABLE " + RecurrenceEntry.TABLE_NAME + " ("
                + RecurrenceEntry.COLUMN_ID + " integer primary key autoincrement, "
                + RecurrenceEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                + RecurrenceEntry.COLUMN_MULTIPLIER + " integer not null default 1, "
                + RecurrenceEntry.COLUMN_PERIOD_TYPE + " varchar(255) not null, "
                + RecurrenceEntry.COLUMN_BYDAY + " varchar(255), "
                + RecurrenceEntry.COLUMN_PERIOD_START + " timestamp not null, "
                + RecurrenceEntry.COLUMN_PERIOD_END + " timestamp, "
                + RecurrenceEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + RecurrenceEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP); "
                )

        /**
         * SQL statement to create the saved search templates table (feature ①a).
         *
         * `IF NOT EXISTS` so the same statement can be reused both on create
         * (v31+) and in [MigrationHelper] (30 → 31), where re-running must be a
         * no-op rather than an error.
         *
         * `internal` (not private) because [MigrationHelper] needs it, mirroring
         * how [PRICES_TABLE_CREATE] is shared.
         */
        internal const val SEARCH_TEMPLATES_TABLE_CREATE =
            ("CREATE TABLE IF NOT EXISTS " + SearchTemplateEntry.TABLE_NAME + " ("
                    + SearchTemplateEntry.COLUMN_ID + " integer primary key autoincrement, "
                    + SearchTemplateEntry.COLUMN_NAME + " varchar(255) not null, "
                    + SearchTemplateEntry.COLUMN_FORM + " text not null, "
                    + SearchTemplateEntry.COLUMN_SORT_ORDER + " integer not null default 0, "
                    + SearchTemplateEntry.COLUMN_CREATED_AT + " integer not null default 0, "
                    + SearchTemplateEntry.COLUMN_UPDATED_AT + " integer not null default 0"
                    + ");"
                    )

        /**
         * SQL statement to create the local-only saved-transaction-template table
         * (feature ①d).
         *
         * `IF NOT EXISTS` so the same statement can be reused both on create
         * (v35+) and in [MigrationHelper] (34 → 35), where re-running must be a
         * no-op rather than an error.
         */
        internal const val TRANSACTION_TEMPLATES_TABLE_CREATE =
            ("CREATE TABLE IF NOT EXISTS " + TransactionTemplateEntry.TABLE_NAME + " ("
                    + TransactionTemplateEntry.COLUMN_ID + " integer primary key autoincrement, "
                    + TransactionTemplateEntry.COLUMN_NAME + " varchar(255) not null, "
                    + TransactionTemplateEntry.COLUMN_PAYLOAD + " text not null, "
                    + TransactionTemplateEntry.COLUMN_SORT_ORDER + " integer not null default 0, "
                    + TransactionTemplateEntry.COLUMN_CREATED_AT + " integer not null default 0, "
                    + TransactionTemplateEntry.COLUMN_UPDATED_AT + " integer not null default 0"
                    + ");"
                    )

        /**
         * SQL statement to create the tags table (feature ⑧).
         *
         * `IF NOT EXISTS` — the same statement is reused by [MigrationHelper]
         * (31 → 32), where re-running must be a no-op. [TagEntry.COLUMN_NAME] is
         * declared `COLLATE NOCASE` so "Food" and "food" are one tag, which also
         * makes the correlated tag predicate in the search layer case-insensitive.
         */
        internal const val TAGS_TABLE_CREATE =
            ("CREATE TABLE IF NOT EXISTS " + TagEntry.TABLE_NAME + " ("
                    + TagEntry.COLUMN_ID + " integer primary key autoincrement, "
                    + TagEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                    + TagEntry.COLUMN_NAME + " varchar(255) not null COLLATE NOCASE, "
                    + TagEntry.COLUMN_COLOR + " varchar(255), "
                    + TagEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + TagEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ");"
                    )

        /**
         * SQL statement to create the transaction↔tag join table (feature ⑧).
         *
         * Both foreign keys cascade, so links disappear together with their
         * transaction or tag. [TransactionTagEntry.COLUMN_TAG] holds the tag
         * **name** (case-insensitive) because that is what the search layer matches
         * against — keeping the tag predicate a bare `WHERE` fragment.
         */
        internal const val TRANSACTION_TAGS_TABLE_CREATE =
            ("CREATE TABLE IF NOT EXISTS " + TransactionTagEntry.TABLE_NAME + " ("
                    + TransactionTagEntry.COLUMN_ID + " integer primary key autoincrement, "
                    + TransactionTagEntry.COLUMN_TRANSACTION_UID + " varchar(255) not null, "
                    + TransactionTagEntry.COLUMN_TAG + " varchar(255) not null COLLATE NOCASE, "
                    + TransactionTagEntry.COLUMN_TAG_UID + " varchar(255), "
                    + TransactionTagEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (" + TransactionTagEntry.COLUMN_TRANSACTION_UID + ") REFERENCES "
                    + TransactionEntry.TABLE_NAME + " (" + TransactionEntry.COLUMN_UID + ") ON DELETE CASCADE, "
                    + "FOREIGN KEY (" + TransactionTagEntry.COLUMN_TAG_UID + ") REFERENCES "
                    + TagEntry.TABLE_NAME + " (" + TagEntry.COLUMN_UID + ") ON DELETE CASCADE "
                    + ");"
                    )

        /**
         * Indexes for the tag tables. `IF NOT EXISTS` so [MigrationHelper] can reuse
         * them; kept as **one statement per constant** because `execSQL` runs a
         * single statement.
         */
        internal const val TAG_UID_INDEX_CREATE =
            "CREATE UNIQUE INDEX IF NOT EXISTS '" + TagEntry.INDEX_UID + "' ON " +
                    TagEntry.TABLE_NAME + "(" + TagEntry.COLUMN_UID + ");"

        internal const val TAG_NAME_INDEX_CREATE =
            "CREATE UNIQUE INDEX IF NOT EXISTS '" + TagEntry.INDEX_NAME + "' ON " +
                    TagEntry.TABLE_NAME + "(" + TagEntry.COLUMN_NAME + " COLLATE NOCASE);"

        internal const val TRANSACTION_TAG_UNIQUE_INDEX_CREATE =
            "CREATE UNIQUE INDEX IF NOT EXISTS '" + TransactionTagEntry.INDEX_UNIQUE + "' ON " +
                    TransactionTagEntry.TABLE_NAME + "(" + TransactionTagEntry.COLUMN_TRANSACTION_UID +
                    ", " + TransactionTagEntry.COLUMN_TAG + ");"

        internal const val TRANSACTION_TAG_TX_INDEX_CREATE =
            "CREATE INDEX IF NOT EXISTS '" + TransactionTagEntry.INDEX_TRANSACTION + "' ON " +
                    TransactionTagEntry.TABLE_NAME + "(" + TransactionTagEntry.COLUMN_TRANSACTION_UID + ");"

        internal const val TRANSACTION_TAG_TAG_INDEX_CREATE =
            "CREATE INDEX IF NOT EXISTS '" + TransactionTagEntry.INDEX_TAG + "' ON " +
                    TransactionTagEntry.TABLE_NAME + "(" + TransactionTagEntry.COLUMN_TAG + ");"

        /** All tag-table index statements in creation order (shared with [MigrationHelper]). */
        internal val TAG_INDEX_STATEMENTS = arrayOf(
            TAG_UID_INDEX_CREATE,
            TAG_NAME_INDEX_CREATE,
            TRANSACTION_TAG_UNIQUE_INDEX_CREATE,
            TRANSACTION_TAG_TX_INDEX_CREATE,
            TRANSACTION_TAG_TAG_INDEX_CREATE
        )

        /**
         * SQL statement to create the sync base-snapshot table (feature ② v2).
         *
         * `IF NOT EXISTS` so the statement is shared with [MigrationHelper] (32 → 33,
         * and again from 33 → 34, where the added columns are back-filled separately),
         * where re-running must be a no-op. The `(record_type, record_key)` pair is
         * unique — one base per record — and [SyncStateEntry.COLUMN_PAYLOAD] is
         * opaque so the serialisation format can evolve freely.
         *
         * [SyncStateEntry.COLUMN_BASE_ETAG] and [SyncStateEntry.COLUMN_PENDING_SINCE]
         * are part of the current (34) shape; an existing 33 table is upgraded by
         * `migrateTo34`.
         */
        internal const val SYNC_STATE_TABLE_CREATE =
            ("CREATE TABLE IF NOT EXISTS " + SyncStateEntry.TABLE_NAME + " ("
                    + SyncStateEntry.COLUMN_ID + " integer primary key autoincrement, "
                    + SyncStateEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                    + SyncStateEntry.COLUMN_RECORD_TYPE + " varchar(64) not null, "
                    + SyncStateEntry.COLUMN_RECORD_KEY + " varchar(255) not null, "
                    + SyncStateEntry.COLUMN_PAYLOAD + " text, "
                    + SyncStateEntry.COLUMN_BASE_MODIFIED_AT + " integer not null default 0, "
                    + SyncStateEntry.COLUMN_BASE_ETAG + " varchar(255), "
                    + SyncStateEntry.COLUMN_PENDING_SINCE + " integer, "
                    + SyncStateEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + SyncStateEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ");"
                    )

        /**
         * SQL statement to create the deletion-marker table (feature ② v2).
         *
         * `(record_type, record_key)` is unique so a record can only be tombstoned
         * once; re-tombstoning is an upsert, never a duplicate.
         */
        internal const val TOMBSTONES_TABLE_CREATE =
            ("CREATE TABLE IF NOT EXISTS " + TombstoneEntry.TABLE_NAME + " ("
                    + TombstoneEntry.COLUMN_ID + " integer primary key autoincrement, "
                    + TombstoneEntry.COLUMN_UID + " varchar(255) not null UNIQUE, "
                    + TombstoneEntry.COLUMN_RECORD_TYPE + " varchar(64) not null, "
                    + TombstoneEntry.COLUMN_RECORD_KEY + " varchar(255) not null, "
                    + TombstoneEntry.COLUMN_DELETED_AT + " integer not null default 0, "
                    + TombstoneEntry.COLUMN_DEVICE_ID + " varchar(255), "
                    + TombstoneEntry.COLUMN_CREATED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + TombstoneEntry.COLUMN_MODIFIED_AT + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ");"
                    )

        /**
         * SQL statement to create the conflict-resolution table (feature ② v3).
         *
         * `IF NOT EXISTS` so the statement is shared with [MigrationHelper] (35 → 36), where
         * re-running must be a no-op. There is no `uid`/timestamp pair here (see
         * [SyncResolutionEntry]); `(record_type, record_key)` is the whole key, and a record
         * can carry at most one pending answer — `putResolution` replaces rather than appends,
         * so "keep local" after "keep remote" is one row, not two contradicting ones.
         *
         * Kept out of [SYNC_INDEX_STATEMENTS] on purpose: that array is replayed by the 32 → 33
         * migration, where this table does not exist yet, and `CREATE INDEX` on a missing table
         * is an error rather than a no-op.
         */
        internal const val SYNC_RESOLUTIONS_TABLE_CREATE =
            ("CREATE TABLE IF NOT EXISTS " + SyncResolutionEntry.TABLE_NAME + " ("
                    + SyncResolutionEntry.COLUMN_ID + " integer primary key autoincrement, "
                    + SyncResolutionEntry.COLUMN_RECORD_TYPE + " varchar(64) not null, "
                    + SyncResolutionEntry.COLUMN_RECORD_KEY + " varchar(255) not null, "
                    + SyncResolutionEntry.COLUMN_SIDE + " varchar(16) not null"
                    + ");"
                    )

        /**
         * Index for the resolution table (feature ② v3). `IF NOT EXISTS`, and its own
         * statement list, so [MigrationHelper] can reuse it without the 32 → 33 replay
         * touching a table that does not exist at that point.
         */
        internal const val SYNC_RESOLUTION_RECORD_INDEX_CREATE =
            "CREATE UNIQUE INDEX IF NOT EXISTS '" + SyncResolutionEntry.INDEX_UNIQUE + "' ON " +
                    SyncResolutionEntry.TABLE_NAME + "(" + SyncResolutionEntry.COLUMN_RECORD_TYPE +
                    ", " + SyncResolutionEntry.COLUMN_RECORD_KEY + ");"

        /** Resolution-table index statements, shared verbatim with [MigrationHelper]. */
        internal val SYNC_RESOLUTION_INDEX_STATEMENTS = arrayOf(
            SYNC_RESOLUTION_RECORD_INDEX_CREATE
        )

        /**
         * Indexes for the sync tables. `IF NOT EXISTS`, one statement per constant,
         * so [MigrationHelper] can reuse them verbatim.
         */
        internal const val SYNC_STATE_UID_INDEX_CREATE =
            "CREATE UNIQUE INDEX IF NOT EXISTS '" + SyncStateEntry.INDEX_UID + "' ON " +
                    SyncStateEntry.TABLE_NAME + "(" + SyncStateEntry.COLUMN_UID + ");"

        internal const val SYNC_STATE_RECORD_INDEX_CREATE =
            "CREATE UNIQUE INDEX IF NOT EXISTS '" + SyncStateEntry.INDEX_UNIQUE + "' ON " +
                    SyncStateEntry.TABLE_NAME + "(" + SyncStateEntry.COLUMN_RECORD_TYPE +
                    ", " + SyncStateEntry.COLUMN_RECORD_KEY + ");"

        internal const val SYNC_STATE_TYPE_INDEX_CREATE =
            "CREATE INDEX IF NOT EXISTS '" + SyncStateEntry.INDEX_TYPE + "' ON " +
                    SyncStateEntry.TABLE_NAME + "(" + SyncStateEntry.COLUMN_RECORD_TYPE + ");"

        internal const val TOMBSTONE_UID_INDEX_CREATE =
            "CREATE UNIQUE INDEX IF NOT EXISTS '" + TombstoneEntry.INDEX_UID + "' ON " +
                    TombstoneEntry.TABLE_NAME + "(" + TombstoneEntry.COLUMN_UID + ");"

        internal const val TOMBSTONE_RECORD_INDEX_CREATE =
            "CREATE UNIQUE INDEX IF NOT EXISTS '" + TombstoneEntry.INDEX_UNIQUE + "' ON " +
                    TombstoneEntry.TABLE_NAME + "(" + TombstoneEntry.COLUMN_RECORD_TYPE +
                    ", " + TombstoneEntry.COLUMN_RECORD_KEY + ");"

        /** All sync-table index statements in creation order (shared with [MigrationHelper]). */
        internal val SYNC_INDEX_STATEMENTS = arrayOf(
            SYNC_STATE_UID_INDEX_CREATE,
            SYNC_STATE_RECORD_INDEX_CREATE,
            SYNC_STATE_TYPE_INDEX_CREATE,
            TOMBSTONE_UID_INDEX_CREATE,
            TOMBSTONE_RECORD_INDEX_CREATE
        )

        /**
         * Creates an update trigger to update the updated_at column for all records in the database.
         * This has to be run per table, and is currently appended to the create table statement.
         *
         * @param tableName Name of table on which to create trigger
         * @return SQL statement for creating trigger
         */
        fun createUpdatedAtTrigger(tableName: String): String {
            return ("CREATE TRIGGER update_time_trigger_" + tableName
                    + "  AFTER UPDATE ON " + tableName + " FOR EACH ROW"
                    + "  BEGIN " + "UPDATE " + tableName
                    + "  SET " + CommonColumns.COLUMN_MODIFIED_AT + " = CURRENT_TIMESTAMP"
                    + "  WHERE OLD." + CommonColumns.COLUMN_UID + " = NEW." + CommonColumns.COLUMN_UID + ";"
                    + "  END;")
        }

        /**
         * Escape the given argument for use in a `LIKE` statement.
         *
         * @param value the value to escape.
         */
        fun sqlEscapeLike(value: String?): String {
            val escaper = StringBuilder()
            DatabaseUtils.appendEscapedSQLString(escaper, value)
            var escape = false
            for (i in escaper.lastIndex downTo 1) {
                val c = escaper[i]
                if ((c == '%') || (c == '_')) {
                    escape = true
                    escaper.insert(i, '_')
                }
            }
            escaper.insert(1, '%')
            escaper.insert(escaper.lastIndex, '%')
            if (escape) {
                escaper.append(" ESCAPE '_'")
            }
            return escaper.toString()
        }

        fun createResetBalancesTriggers(db: SQLiteDatabase) {
            val sqlReset = ("UPDATE " + AccountEntry.TABLE_NAME + " SET "
                    + AccountEntry.COLUMN_BALANCE + " = NULL, "
                    + AccountEntry.COLUMN_CLEARED_BALANCE + " = NULL, "
                    + AccountEntry.COLUMN_NOCLOSING_BALANCE + " = NULL, "
                    + AccountEntry.COLUMN_RECONCILED_BALANCE + " = NULL")

            val sqlWhenDelete = ("CREATE TRIGGER reset_balances_delete_" + SplitEntry.TABLE_NAME
                    + " AFTER DELETE ON " + SplitEntry.TABLE_NAME
                    + " BEGIN " + sqlReset + "; END;")
            db.execSQL(sqlWhenDelete)

            val sqlWhenInsert = ("CREATE TRIGGER reset_balances_insert_" + SplitEntry.TABLE_NAME
                    + " AFTER INSERT ON " + SplitEntry.TABLE_NAME
                    + " BEGIN " + sqlReset + "; END;")
            db.execSQL(sqlWhenInsert)

            val sqlWhenUpdate = ("CREATE TRIGGER reset_balances_update_" + SplitEntry.TABLE_NAME
                    + " AFTER UPDATE ON " + SplitEntry.TABLE_NAME
                    + " BEGIN " + sqlReset + "; END;")
            db.execSQL(sqlWhenUpdate)

            // Expect that `reset_balances_delete_splits` trigger would be called
            // *but*
            // 'Triggers are not activated by foreign key actions.'
            val sqlWhenDeleteTx =
                ("CREATE TRIGGER reset_balances_delete_" + TransactionEntry.TABLE_NAME
                        + " AFTER DELETE ON " + TransactionEntry.TABLE_NAME
                        + " BEGIN " + sqlReset + "; END;")
            db.execSQL(sqlWhenDeleteTx)

            val sqlWhenDeleteAccount =
                ("CREATE TRIGGER reset_balances_delete_" + AccountEntry.TABLE_NAME
                        + " AFTER DELETE ON " + AccountEntry.TABLE_NAME
                        + " BEGIN " + sqlReset + "; END;")
            db.execSQL(sqlWhenDeleteAccount)

            val sqlWhenUpdateAccount =
                ("CREATE TRIGGER reset_balances_update_" + AccountEntry.TABLE_NAME
                        + " AFTER UPDATE OF "
                        + AccountEntry.COLUMN_COMMODITY_UID + ", "
                        + AccountEntry.COLUMN_PARENT_ACCOUNT_UID + ", "
                        + AccountEntry.COLUMN_TYPE
                        + " ON " + AccountEntry.TABLE_NAME
                        + " BEGIN " + sqlReset + "; END;")
            db.execSQL(sqlWhenUpdateAccount)
        }

        fun hasTableColumn(db: SQLiteDatabase, tableName: String, columnName: String): Boolean {
            var result = false
            db.rawQuery("PRAGMA table_info($tableName)", null).forEach { cursor ->
                val name = cursor.getString("name")
                if (columnName == name) {
                    result = true
                    return@forEach
                }
            }
            return result
        }
    }
}

fun SQLiteDatabase.hasTableColumn(tableName: String, columnName: String): Boolean {
    return DatabaseHelper.hasTableColumn(this, tableName, columnName)
}