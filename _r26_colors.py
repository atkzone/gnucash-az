"""R26 小修：搜索结果报表不能用基类的 protected COLORS。

Kotlin 2.4 的 SyntheticAccessorLowering 在「跨文件访问另一文件的 companion protected 成员」时
直接 AssertionError（SyntheticAccessorLowering should not attempt to modify other files!）。
工程里其它报表片段（饼图/柱状图/折线图/资金流）**一律自带私有调色板**，本文件照此办理。
"""
import io
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
rel = "app/src/main/java/org/gnucash/android/ui/report/search/SearchResultReportFragment.kt"
path = os.path.join(ROOT, rel)
raw = io.open(path, encoding="utf-8", newline="").read()
assert "\r\n" not in raw, "expected LF"
text = raw

edits = [
    ("            colors = COLORS.toList()",
     "            colors = PALETTE"),
    ("            colors = buckets.indices.map { COLORS[it % COLORS.size] }",
     "            colors = buckets.indices.map { PALETTE[it % PALETTE.size] }"),
    ("            color = COLORS[0]",
     "            color = PALETTE[0]"),
    ("""        private const val ANIMATION_DURATION = 600
    }
}
""",
     """        private const val ANIMATION_DURATION = 600

        /**
         * Slice / bar colours, cycled when there are more buckets than colours.
         *
         * A local palette rather than the base class's `COLORS`: that one is `protected`
         * on its companion, and reading it from another file makes the Kotlin 2.4 backend
         * fail outright (`SyntheticAccessorLowering should not attempt to modify other
         * files!`). Every other report fragment here carries its own palette for the same
         * reason.
         */
        private val PALETTE: List<Int> = listOf(
            Color.parseColor("#4CAF50"),
            Color.parseColor("#2196F3"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#E91E63"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#00BCD4"),
            Color.parseColor("#FFC107"),
            Color.parseColor("#795548")
        )
    }
}
"""),
]

for old, new in edits:
    n = text.count(old)
    if n != 1:
        raise SystemExit("anchor count %d for %r" % (n, old[:60]))
    text = text.replace(old, new, 1)

io.open(path, "w", encoding="utf-8", newline="").write(text)
print("OK", rel, "bytes=", len(text))
