#!/usr/bin/env python3
"""R25 mutation-bite helper.

Applies one named source mutation, or restores it, or verifies the restore is
byte-identical. Line endings are handled adaptively (the repo mixes CRLF and LF),
which is the trap that makes a mutation silently not apply.

Usage:
  python _r25_mut.py apply   <id>
  python _r25_mut.py restore <id>
  python _r25_mut.py verify  <id>
  python _r25_mut.py list
"""
import hashlib
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
BACKUP_DIR = os.path.join(ROOT, "_r25_mut_backup")

# id -> (relative path, unique find fragment (LF), replacement fragment (LF))
CASES = {
    "m01_bulk_delete_no_transaction": (
        "app/src/main/java/org/gnucash/android/ui/search/SearchBulkWriter.kt",
        "        var deleted = 0\n        transactionsDbAdapter.beginTransaction()\n",
        "        var deleted = 0\n",
    ),
    "m02_select_null_keeps_mode_on": (
        "app/src/main/java/org/gnucash/android/ui/transaction/view/ExtendedViewStore.kt",
        "        if (id == null) {\n            setEnabled(false)\n            return\n        }\n",
        "        if (id == null) {\n            return\n        }\n",
    ),
    "m03_update_fields_accepts_empty": (
        "app/src/main/java/org/gnucash/android/ui/transaction/view/ExtendedViewStore.kt",
        "        if (fields.isEmpty()) return\n",
        "",
    ),
    "m04_has_extra_lines_always_true": (
        "app/src/main/java/org/gnucash/android/ui/transaction/view/ExtendedRowContent.kt",
        "        get() = notes != null || number != null || tags.isNotEmpty()",
        "        get() = true",
    ),
    "m05_manual_date_lenient": (
        "app/src/main/java/org/gnucash/android/util/ManualDate.kt",
        "            isLenient = false",
        "            isLenient = true",
    ),
    "m06_search_report_always_offered": (
        "app/src/main/java/org/gnucash/android/ui/report/ReportsActivity.kt",
        "includeSearchResult = searchWhere != null",
        "includeSearchResult = true",
    ),
    "m07_search_result_never_filtered": (
        "app/src/main/java/org/gnucash/android/ui/report/ReportType.kt",
        "                if (value == SEARCH_RESULT && !includeSearchResult) continue\n",
        "",
    ),
    "m08_uids_of_keeps_empty_array": (
        "app/src/main/java/org/gnucash/android/ui/search/BulkEditTransactionsDialogFragment.kt",
        "            result.getStringArray(EXTRA_UIDS)?.toList()?.takeIf { it.isNotEmpty() }",
        "            result.getStringArray(EXTRA_UIDS)?.toList()",
    ),
    "m09_manual_button_unwired": (
        "app/src/main/java/org/gnucash/android/ui/util/dialog/DatePickerDialogFragment.kt",
        ".setOnClickListener { showManualEntry() }",
        ".setOnClickListener { }",
    ),
    "m10_report_single_currency_bucket": (
        "app/src/main/java/org/gnucash/android/ui/report/search/SearchReportLoader.kt",
        "                val currency = amount.commodity.currencyCode",
        "                val currency = \"X\"",
    ),
    "m11_report_folds_every_split": (
        "app/src/main/java/org/gnucash/android/ui/report/search/SearchReportLoader.kt",
        "        .filter { it.type == TransactionType.DEBIT }\n",
        "",
    ),
    "m12_select_ignores_argument": (
        "app/src/main/java/org/gnucash/android/ui/transaction/view/ExtendedViewsFragment.kt",
        "        store().select(id)",
        "        store().select(null)",
    ),
    "m13_range_order_not_checked": (
        "app/src/main/java/org/gnucash/android/util/ManualDateRange.kt",
        "        if (end < start) return null\n",
        "",
    ),
    "m14_range_manual_button_unwired": (
        "app/src/main/java/org/gnucash/android/ui/util/dialog/DateRangePickerDialogFragment.kt",
        ".setOnClickListener { showManualEntry() }",
        ".setOnClickListener { }",
    ),
}


def _paths(mid):
    rel, find, repl = CASES[mid]
    src = os.path.join(ROOT, rel.replace("/", os.sep))
    flat = rel.replace("/", "__")
    bak = os.path.join(BACKUP_DIR, mid + "__" + flat)
    return src, find, repl, bak


def sha(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def apply(mid):
    src, find, repl, bak = _paths(mid)
    with open(src, "r", encoding="utf-8", newline="") as fh:
        raw = fh.read()
    # Back up the pristine bytes once (the baseline).
    if not os.path.isfile(bak):
        os.makedirs(BACKUP_DIR, exist_ok=True)
        shutil.copy2(src, bak)
    eol = "\r\n" if "\r\n" in raw else "\n"
    text = raw.replace("\r\n", "\n")
    occurrences = text.count(find)
    if occurrences != 1:
        raise SystemExit(
            "MUTATION ANCHOR NOT UNIQUE (%d) in %s for %s" % (occurrences, src, mid)
        )
    mutated = text.replace(find, repl, 1).replace("\n", eol)
    with open(src, "w", encoding="utf-8", newline="") as fh:
        fh.write(mutated)
    print("APPLIED %s (%s, eol=%s)" % (mid, os.path.basename(src), repr(eol)))


def restore(mid):
    src, _find, _repl, bak = _paths(mid)
    if not os.path.isfile(bak):
        raise SystemExit("no backup for %s" % mid)
    shutil.copy2(bak, src)
    print("RESTORED %s" % mid)


def verify(mid):
    src, _find, _repl, bak = _paths(mid)
    if not os.path.isfile(bak):
        print("VERIFY %s: NO BACKUP" % mid)
        return 1
    same = sha(src) == sha(bak)
    print("VERIFY %s: %s" % (mid, "byte-identical" if same else "DIFFERS"))
    return 0 if same else 1


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    cmd = sys.argv[1]
    if cmd == "list":
        for mid in CASES:
            print(mid)
        return
    mid = sys.argv[2]
    if mid not in CASES:
        raise SystemExit("unknown id %s" % mid)
    if cmd == "apply":
        apply(mid)
    elif cmd == "restore":
        restore(mid)
    elif cmd == "verify":
        raise SystemExit(verify(mid))
    else:
        raise SystemExit("unknown command %s" % cmd)


if __name__ == "__main__":
    main()
