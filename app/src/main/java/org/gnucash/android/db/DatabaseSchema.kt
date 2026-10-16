/*
 * Copyright (c) 2014 - 2015 Ngewi Fet <ngewif@gmail.com>
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

import android.database.Cursor
import android.provider.BaseColumns

/**
 * Holds the database schema
 *
 * @author Ngewi Fet <ngewif@gmail.com>
 */
object DatabaseSchema {
    /**
     * Name of database storing information about the books in the application
     */
    const val BOOK_DATABASE_NAME: String = "gnucash_books.db"

    /**
     * Version number of database containing information about the books in the application
     */
    const val BOOK_DATABASE_VERSION: Int = 1

    /**
     * Version number of database containing accounts and transactions info.
     * With any change to the database schema, this number must increase
     */
    const val DATABASE_VERSION: Int = 36

    abstract class CommonColumns : BaseColumns {
        companion object {
            @Column(value = Cursor.FIELD_TYPE_INTEGER, readOnly = true)
            const val COLUMN_ID: String = BaseColumns._ID

            @Column(Cursor.FIELD_TYPE_STRING)
            const val COLUMN_UID: String = "uid"

            @Column(Cursor.FIELD_TYPE_STRING)
            const val COLUMN_CREATED_AT: String = "created_at"

            @Column(Cursor.FIELD_TYPE_STRING)
            const val COLUMN_MODIFIED_AT: String = "modified_at"
        }
    }

    object BookEntry : CommonColumns() {
        const val TABLE_NAME: String = "books"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_DISPLAY_NAME: String = "name"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_SOURCE_URI: String = "uri"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ROOT_GUID: String = "root_account_guid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TEMPLATE_GUID: String = "root_template_guid"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_ACTIVE: String = "is_active"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_LAST_SYNC: String = "last_export_time"
    }

    /**
     * Columns for the account tables
     */
    object AccountEntry : CommonColumns() {
        const val TABLE_NAME: String = "accounts"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_NAME: String = "name"

        @Column(Cursor.FIELD_TYPE_STRING)
        @Deprecated("")
        const val COLUMN_CURRENCY: String = "currency_code"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_COMMODITY_UID: String = "commodity_uid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CODE: String = "code"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_DESCRIPTION: String = "description"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_PARENT_ACCOUNT_UID: String = "parent_account_uid"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_PLACEHOLDER: String = "is_placeholder"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_COLOR_CODE: String = "color_code"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_FAVORITE: String = "favorite"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_FULL_NAME: String = "full_name"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TYPE: String = "type"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_HIDDEN: String = "is_hidden"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_DEFAULT_TRANSFER_ACCOUNT_UID: String = "default_transfer_account_uid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_NOTES: String = "notes"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_TEMPLATE: String = "is_template"

        /* cached parameters */
        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_BALANCE: String = "balance"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_NOCLOSING_BALANCE: String = "noclosing_balance"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_CLEARED_BALANCE: String = "cleared_balance"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_RECONCILED_BALANCE: String = "reconciled_balance"

        const val INDEX_UID: String = "account_uid_index"
    }

    /**
     * Column schema for the transaction table in the database
     */
    object TransactionEntry : CommonColumns() {
        const val TABLE_NAME: String = "transactions"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_DATE_ENTERED: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        //The actual names of columns for description and notes are unlike the variable names because of legacy
        //We will not change them now for backwards compatibility reasons. But the variable names make sense
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_DESCRIPTION: String = "name"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_NOTES: String = "description"

        @Column(Cursor.FIELD_TYPE_STRING)
        @Deprecated("Use commodity UID")
        const val COLUMN_CURRENCY: String = "currency_code"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_COMMODITY_UID: String = "commodity_uid"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_DATE_POSTED: String = "timestamp"

        /**
         * Flag for marking transactions which have been exported.
         * Cannot rely on the MODIFIED timestamp only, because of the trigger when importing transactions.
         */
        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_EXPORTED: String = "is_exported"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_TEMPLATE: String = "is_template"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_SCHEDX_ACTION_UID: String = "scheduled_action_uid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_NUMBER: String = "num"

        const val INDEX_UID: String = "transaction_uid_index"
    }

    /**
     * Column schema for the splits table in the database
     */
    object SplitEntry : CommonColumns() {
        const val TABLE_NAME: String = "splits"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TYPE: String = "type"

        /**
         * The value columns are in the currency of the transaction containing the split
         */
        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_VALUE_NUM: String = "value_num"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_VALUE_DENOM: String = "value_denom"

        /**
         * The quantity columns are in the currency of the account to which the split belongs
         */
        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_QUANTITY_NUM: String = "quantity_num"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_QUANTITY_DENOM: String = "quantity_denom"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MEMO: String = "memo"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ACCOUNT_UID: String = "account_uid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TRANSACTION_UID: String = "transaction_uid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_RECONCILE_STATE: String = "reconcile_state"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_RECONCILE_DATE: String = "reconcile_date"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_SCHEDX_ACTION_ACCOUNT_UID: String = "sched_account_uid"

        const val INDEX_UID: String = "split_uid_index"
    }

    object ScheduledActionEntry : CommonColumns() {
        const val TABLE_NAME: String = "scheduled_actions"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TYPE: String = "type"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_NAME: String = "name"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ACTION_UID: String = "action_uid"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_START_DATE: String = "start_time"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_END_DATE: String = "end_time"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_LAST_OCCUR: String = "last_run"
        @Deprecated("renamed", ReplaceWith("COLUMN_START_DATE"))
        const val COLUMN_START_TIME: String = COLUMN_START_DATE

        @Deprecated("renamed", ReplaceWith("COLUMN_END_DATE"))
        const val COLUMN_END_TIME: String = COLUMN_END_DATE


        /**
         * Tag for scheduledAction-specific information e.g. backup parameters for backup
         */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TAG: String = "tag"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_ENABLED: String = "is_enabled"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_TOTAL_FREQUENCY: String = "total_frequency"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_INSTANCE_COUNT: String = "execution_count"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_RECURRENCE_UID: String = "recurrence_uid"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_AUTO_CREATE: String = "auto_create"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_AUTO_NOTIFY: String = "auto_notify"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_ADVANCE_CREATION: String = "adv_creation"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_ADVANCE_NOTIFY: String = "adv_notify"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TEMPLATE_ACCT_UID: String = "template_act_uid"

        const val INDEX_UID: String = "scheduled_action_uid_index"
    }

    object CommodityEntry : CommonColumns() {
        const val TABLE_NAME: String = "commodities"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        /**
         * The namespace field denotes the namespace for this commodity,
         * either a currency or symbol from a quote source
         */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_NAMESPACE: String = "namespace"

        /**
         * The fullname is the official full name of the currency
         */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_FULLNAME: String = "fullname"

        /**
         * The mnemonic is the official abbreviated designation for the currency
         */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MNEMONIC: String = "mnemonic"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_LOCAL_SYMBOL: String = "local_symbol"

        /**
         * The fraction is the number of sub-units that the basic commodity can be divided into
         */
        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_SMALLEST_FRACTION: String = "fraction"

        /**
         * A CUSIP is a nine-character alphanumeric code that identifies a North American financial security
         * for the purposes of facilitating clearing and settlement of trades
         */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CUSIP: String = "cusip"

        /**
         * TRUE if prices are to be downloaded for this commodity from a quote source
         */
        @Column(Cursor.FIELD_TYPE_INTEGER)
        @Deprecated("")
        const val COLUMN_QUOTE_FLAG: String = "quote_flag"

        /**
         * Prices are to be downloaded for this commodity from a quote source.
         */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_QUOTE_SOURCE: String = "quote_source"

        /**
         * Time zone of the quote source.
         */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_QUOTE_TZ: String = "quote_tz"

        const val INDEX_UID: String = "commodities_uid_index"
    }


    object PriceEntry : CommonColumns() {
        const val TABLE_NAME: String = "prices"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_SECURITY_UID: String = "commodity_guid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CURRENCY_UID: String = "currency_guid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_DATE: String = "date"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_SOURCE: String = "source"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TYPE: String = "type"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_VALUE_NUM: String = "value_num"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_VALUE_DENOM: String = "value_denom"

        const val INDEX_UID: String = "prices_uid_index"
    }

    object BudgetEntry : CommonColumns() {
        const val TABLE_NAME: String = "budgets"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_NAME: String = "name"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_DESCRIPTION: String = "description"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_NUM_PERIODS: String = "num_periods"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_RECURRENCE_UID: String = "recurrence_uid"

        const val INDEX_UID: String = "budgets_uid_index"
    }

    object BudgetAmountEntry : CommonColumns() {
        const val TABLE_NAME: String = "budget_amounts"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_BUDGET_UID: String = "budget_uid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ACCOUNT_UID: String = "account_uid"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_PERIOD_NUM: String = "period_num"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_AMOUNT_NUM: String = "amount_num"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_AMOUNT_DENOM: String = "amount_denom"
        const val COLUMN_NOTES: String = "notes"

        const val INDEX_UID: String = "budget_amounts_uid_index"
    }

    object RecurrenceEntry : CommonColumns() {
        const val TABLE_NAME: String = "recurrences"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_MULTIPLIER: String = "recurrence_mult"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_PERIOD_TYPE: String = "recurrence_period_type"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_PERIOD_START: String = "recurrence_period_start"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_PERIOD_END: String = "recurrence_period_end"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_BYDAY: String = "recurrence_byday"

        const val INDEX_UID: String = "recurrence_uid_index"
    }

    /**
     * Local-only, UI-level saved search configurations (feature ①a).
     *
     * Not part of the GnuCash domain model: there is no `uid` and the rows are
     * never exported to GnuCash XML. The whole criteria set is kept in the
     * opaque [COLUMN_FORM] JSON column (see `SearchTemplateCodec`), so the shape
     * of a search can evolve without changing this table.
     *
     * Timestamps are epoch millis (INTEGER) — intentionally NOT the
     * `CommonColumns` TEXT timestamps, since these rows have no model/trigger.
     */
    object SearchTemplateEntry {
        const val TABLE_NAME: String = "search_templates"

        @Column(Cursor.FIELD_TYPE_INTEGER, readOnly = true)
        const val COLUMN_ID: String = BaseColumns._ID

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_NAME: String = "name"

        /** Opaque serialised [org.gnucash.android.ui.search.SearchForm] (JSON). */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_FORM: String = "form_json"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_SORT_ORDER: String = "sort_order"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_CREATED_AT: String = "created_at"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_UPDATED_AT: String = "updated_at"
    }

    /**
     * Local-only saved *transaction* templates (feature ①d).
     *
     * Deliberately **not** the same thing as [SearchTemplateEntry]: this table
     * stores the shape of a *new transaction* (debit/credit account, an
     * **optional** amount and the remaining transaction fields) so it can be
     * replayed with one tap.
     *
     * Like the search templates it is local only — no `uid`, never exported to
     * GnuCash XML, never part of the sync merge. The whole payload lives in the
     * opaque [COLUMN_PAYLOAD] JSON column (see `TransactionTemplateCodec`), so
     * the set of fields can grow without touching this schema again.
     *
     * Timestamps are epoch millis (INTEGER) — intentionally NOT the
     * `CommonColumns` TEXT timestamps, since these rows have no model/trigger.
     */
    object TransactionTemplateEntry {
        const val TABLE_NAME: String = "transaction_templates"

        @Column(Cursor.FIELD_TYPE_INTEGER, readOnly = true)
        const val COLUMN_ID: String = BaseColumns._ID

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_NAME: String = "name"

        /** Opaque serialised `TransactionTemplate` (JSON). */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_PAYLOAD: String = "payload_json"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_SORT_ORDER: String = "sort_order"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_CREATED_AT: String = "created_at"

        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_UPDATED_AT: String = "updated_at"
    }

    /**
     * First-class tag entity (feature ⑧).
     *
     * GnuCash desktop has no tag entity — its "tags" are just `#tag` tokens inside
     * description/memo text. To stay compatible we keep the `#tag` text in place
     * **and** maintain this table (see the dual-write contract, plan §6.3).
     *
     * Tag identity is [COLUMN_NAME] (case-insensitive, enforced by a UNIQUE NOCASE
     * index); [COLUMN_UID] exists so the tag can later be referenced/synced like
     * any other model without a name-based join.
     */
    object TagEntry : CommonColumns() {
        const val TABLE_NAME: String = "tags"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_NAME: String = "name"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_COLOR: String = "color_code"

        const val INDEX_UID: String = "tag_uid_index"
        const val INDEX_NAME: String = "tag_name_index"
    }

    /**
     * Join table linking a transaction to the tags it carries (feature ⑧).
     *
     * Deliberately **not** a `CommonColumns` model: it is a pure relation, with no
     * `uid` of its own (like `budget_amounts`, rows are recreated wholesale on
     * save). [COLUMN_TAG] stores the tag **name**, because that is exactly what
     * [org.gnucash.android.ui.search.SearchQueryBuilder] matches against — keeping
     * the tag predicate a bare `WHERE` fragment (no extra join).
     *
     * Both foreign keys cascade on delete: removing a transaction or a tag
     * removes its links automatically (`PRAGMA foreign_keys=ON` is set on open).
     */
    object TransactionTagEntry {
        const val TABLE_NAME: String = "transaction_tags"

        @Column(Cursor.FIELD_TYPE_INTEGER, readOnly = true)
        const val COLUMN_ID: String = BaseColumns._ID

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TRANSACTION_UID: String = "transaction_uid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TAG: String = "tag"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_TAG_UID: String = "tag_uid"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = "created_at"

        const val INDEX_TRANSACTION: String = "transaction_tag_tx_index"
        const val INDEX_TAG: String = "transaction_tag_tag_index"
        const val INDEX_UNIQUE: String = "transaction_tag_unique_index"
    }

    /**
     * The per-record **base** snapshot used by the bidirectional merge (feature ②
     * v2, plan §7.1).
     *
     * A "base" is the state of a record **as of the last successful sync** —
     * it is the third input that makes a three-way merge possible at all (without
     * it, "both sides changed" cannot be told apart from "one side changed").
     *
     * Addressable by `(record_type, record_key)` where `record_key` is the
     * GnuCash GUID; [COLUMN_PAYLOAD] holds an opaque serialised snapshot of the
     * record's fields, so the shape of a record can evolve without a migration.
     * [COLUMN_BASE_MODIFIED_AT] is the record's `modified_at` at snapshot time and
     * doubles as the snapshot's own watermark source.
     *
     * [COLUMN_BASE_ETAG] and [COLUMN_PENDING_SINCE] (schema 34) were added when the
     * round orchestration landed: the first lets a round skip re-downloading an
     * unchanged record and provides the `If-Match` value for a conditional write;
     * the second lets "seen absent, not yet confirmed" survive between rounds.
     */
    object SyncStateEntry : CommonColumns() {
        const val TABLE_NAME: String = "sync_state"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        /** Which model the snapshot is for (`book`/`account`/`transaction`/`split`). */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_RECORD_TYPE: String = "record_type"

        /** The record's stable identity — the GnuCash GUID. */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_RECORD_KEY: String = "record_key"

        /** Opaque serialised field snapshot of the record as of the last sync. */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_PAYLOAD: String = "payload"

        /** `modified_at` of the record when the snapshot was taken (watermark source). */
        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_BASE_MODIFIED_AT: String = "base_modified_at"

        /**
         * The peer's entity tag for the record as of the last successful sync, or
         * `NULL` when none was seen. Drives both the "skip an unchanged download"
         * shortcut and the `If-Match` precondition of the next write.
         */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_BASE_ETAG: String = "base_etag"

        /**
         * Epoch millis at which the record was first seen absent from a **complete**
         * peer listing, or `NULL` when it is not being watched. A second consecutive
         * absence promotes the record to a tombstone; a reappearance clears this.
         */
        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_PENDING_SINCE: String = "pending_since"

        const val INDEX_UID: String = "sync_state_uid_index"
        const val INDEX_UNIQUE: String = "sync_state_record_index"
        const val INDEX_TYPE: String = "sync_state_type_index"
    }

    /**
     * An explicit "this record was deleted" marker (feature ② v2, plan §7.1).
     *
     * Deletions must travel as markers rather than as absence: an absent record
     * in a snapshot is indistinguishable from "the listing was truncated", which
     * has historically caused both ends to delete data (plan §7.3, constraint 2).
     * A tombstone is therefore only ever written on **confirmed absence**, and its
     * presence is itself the evidence.
     */
    object TombstoneEntry : CommonColumns() {
        const val TABLE_NAME: String = "tombstones"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_UID: String = CommonColumns.COLUMN_UID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_ID: String = CommonColumns.COLUMN_ID
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_CREATED_AT: String = CommonColumns.COLUMN_CREATED_AT
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_MODIFIED_AT: String = CommonColumns.COLUMN_MODIFIED_AT

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_RECORD_TYPE: String = "record_type"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_RECORD_KEY: String = "record_key"

        /** Epoch millis at which the deletion was confirmed. */
        @Column(Cursor.FIELD_TYPE_INTEGER)
        const val COLUMN_DELETED_AT: String = "deleted_at"

        /** Identifier of the device that confirmed the deletion (audit trail). */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_DEVICE_ID: String = "device_id"

        const val INDEX_UID: String = "tombstone_uid_index"
        const val INDEX_UNIQUE: String = "tombstone_record_index"
    }

    /**
     * A stored "keep this side" answer from the conflict centre (feature ② v3).
     *
     * The conflict centre is otherwise a mirror: it shows what a round parked. This table is
     * the one thing it lets the user *do* — pick a side — and the only way that answer means
     * anything is for the **next** round to read it. A round cannot apply it on the spot,
     * because the round that parked the conflict had already decided to touch nothing and the
     * peer may have moved again by the time the user looks at the screen.
     *
     * Deliberately **not** a column on [SyncStateEntry], even though both are keyed by
     * `(record_type, record_key)`. A resolution must be storable for a record with **no base
     * row**: "created on both devices with different content" is a conflict with no common
     * ancestor, so putting the override on the base row would make the most common conflict
     * unresolvable — there would be nothing to hang it on.
     *
     * There is no `uid` and no timestamp pair here, unlike the other sync tables: the row *is*
     * its `(record_type, record_key)` key plus one value, it is never addressed by a UID, and
     * nothing orders or ages it. A `created_at` nobody reads would just be one more column to
     * keep true.
     */
    object SyncResolutionEntry {
        const val TABLE_NAME: String = "sync_resolutions"

        @Column(Cursor.FIELD_TYPE_INTEGER, readOnly = true)
        const val COLUMN_ID: String = BaseColumns._ID

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_RECORD_TYPE: String = "record_type"

        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_RECORD_KEY: String = "record_key"

        /**
         * Which side wins: [org.gnucash.android.sync.ResolveSide.id] (`local` / `remote`).
         * Stored as the *id*, not the enum name, for the same reason
         * [org.gnucash.android.sync.SyncRecordType.id] is: renaming the Kotlin constant must
         * not orphan existing rows.
         */
        @Column(Cursor.FIELD_TYPE_STRING)
        const val COLUMN_SIDE: String = "side"

        const val INDEX_UNIQUE: String = "sync_resolution_record_index"
    }
}
