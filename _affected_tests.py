#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按「改动 → 符号 → 反向引用」算出**相关测试集**，产出可直接喂给 gradle 的 `--tests` 参数。

用法：
  # 1) 显式给改动文件（本轮清单）——最常用
  python _affected_tests.py app/src/main/kotlin/.../SyncRoundPlanner.kt app/src/main/java/.../DatabaseSchema.kt

  # 2) 让 git 自己给（相对 HEAD 的未提交改动）
  python _affected_tests.py --git
  python _affected_tests.py --git --baseline=<ref>      # 指定基线（默认 HEAD）

  # 3) 最紧口径：直接给「本轮新增/改动的符号名」（无基线时唯一可行）
  python _affected_tests.py --symbols=ResolveSide,putResolution,commitResolutions

  # 4) 只要类名清单 / 只要 gradle 参数
  python _affected_tests.py ... --format=list|args

原理（三步，全部可复现、可 review）：
  A. 改动文件 → **声明的符号**（class/object/interface/enum/fun/val/const，长度≥4，去停用词）
     —— 用 `git diff -U0 <baseline>` 抓新增/改动的声明行，比"整文件所有名字"更准（少误命中）。
  B. 符号 → **引用它的测试文件**（在 app/src/test 下逐一 grep），再抽出文件里的顶层测试类 FQN
     （一个 .kt 可含多个 `class XxxTest`，如 *MigrationTest 与 adapter 测试同文件）。
  C. 并上**共享面守卫集**：改动落在"底座文件"上时无条件加跑（见 GLOBAL_SURFACES）。

⚠️ 局限（必须知道，别把"相关集绿"当成"全量绿"）：
  * ⚠️⚠️ **本仓库当前只有 1 个提交（import commit），27 轮开发全未提交** ⇒
    `git diff HEAD` = **自 import 以来的累计改动**，**不是本轮改动**。
    后果：对 DatabaseSchema.kt 这类大文件，符号集会膨胀到"整文件符号面"⇒ 命中几乎全套。
    这种"膨胀"在本仓库**是安全的（是超集）**，但**不能用来收窄**。
    ⇒ 想收窄只有两条路：(a) 每轮开工前先 `git commit` 存基线，下轮 `--baseline=<上轮提交>`；
      (b) 无基线时用 `--symbols=` 直接喂本轮新增符号（最紧）。
  * 符号反查只能抓**名字出现在测试里**的引用。漏三类：
    (1) 源码守卫测试用**字符串**匹配（如 `"showConflicts(result.reviews)"`）——不含符号名；
    (2) 经**共享夹具/状态**间接受影响（Robolectric 每个用例都建库 ⇒ 建库路径坏了会全域失败）；
    (3) 符号被**改名/删除**时，旧引用会编译失败（这类靠编译期，不靠测试）。
  * 所以：**触及 GLOBAL_SURFACES ⇒ 升档**（跑半全量/全量），别只看相关集。
"""
import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
TEST_DIR = os.path.join(ROOT, "app", "src", "test")

# 共享面：改动落在这些文件上 ⇒ 影响面是「所有建库/所有子类」级别的，相关集不够用
GLOBAL_SURFACES = {
    "db/DatabaseSchema.kt",
    "db/DatabaseHelper.kt",
    "db/MigrationHelper.kt",
    "db/adapter/AbstractDbAdapter.kt",
    "db/adapter/TransactionsDbAdapter.kt",
    "GnuCashTest",
    "gradle/libs.versions.toml",
    "app/build.gradle.kts",
    "app/src/main/res/values/strings.xml",   # 删/改字符串是编译期；新增则安全，但值得看一眼
}

STOP = {"Test", "Main", "String", "Int", "Long", "Boolean", "List", "Map", "Set", "Unit",
        "Override", "Suppress", "Deprecated", "object", "class", "interface", "enum",
        "return", "import", "package", "private", "public", "internal", "val", "var", "fun",
        "remember", "apply", "also", "let", "run", "with", "toString"}

DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"                       # 注解
    r"(?:(?:internal|private|public|protected|open|abstract|sealed|data|enum|annotation|inline|suspend|operator|override)\s+)*"
    r"(?:class|object|interface|fun|val|var)\s+([A-Za-z_]\w*)"
)
CLASS_DECL = re.compile(r"^(?:internal |open |abstract |sealed |data |enum )*class\s+([A-Za-z_]\w*)", re.M)


def changed_files_from_git(baseline):
    out = subprocess.run(["git", "diff", "--name-only", baseline],
                         cwd=ROOT, capture_output=True, text=True).stdout
    out2 = subprocess.run(["git", "ls-files", "--others", "--exclude-standard"],
                          cwd=ROOT, capture_output=True, text=True).stdout
    files = [f for f in (out + "\n" + out2).splitlines()]
    return [f for f in files if f.startswith("app/src/main/") and f.endswith((".kt", ".xml"))]


def _add(lines, syms):
    for line in lines:
        m = DECL.match(line)
        if m:
            name = m.group(1)
            if len(name) >= 4 and name not in STOP:
                syms.add(name)


def changed_symbols(paths, baseline="HEAD"):
    """返回 (syms, fallback_files)。fallback = 该文件相对基线无 diff（视为已提交），退化为整文件符号。"""
    syms = set()
    fallback = []
    for p in paths:
        full = os.path.join(ROOT, p.replace("/", os.sep))
        if not os.path.isfile(full):
            continue
        d = subprocess.run(["git", "diff", "-U0", baseline, "--", p],
                           cwd=ROOT, capture_output=True, text=True).stdout
        added = [l[1:] for l in d.splitlines()
                 if l.startswith("+") and not l.startswith("+++")]
        if added:
            _add(added, syms)
        else:
            fallback.append(p)
            _add(io.open(full, encoding="utf-8", errors="ignore").read().splitlines(), syms)
    return syms, fallback


def tests_for(syms):
    hit = {}
    for dirpath, _dirs, files in os.walk(TEST_DIR):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            fp = os.path.join(dirpath, fn)
            src = io.open(fp, encoding="utf-8", errors="ignore").read()
            used = sorted(s for s in syms if s in src)
            if not used:
                continue
            rel_dir = os.path.relpath(os.path.dirname(fp), os.path.join(TEST_DIR, "java"))
            pkg = rel_dir.replace(os.sep, ".")
            for cls in CLASS_DECL.findall(src):
                hit.setdefault(pkg + "." + cls, set()).update(used)
    return hit


def main():
    args = sys.argv[1:]
    fmt = "list"
    baseline = "HEAD"
    explicit = None
    rest = []
    for a in args:
        if a == "--format=args":
            fmt = "args"
        elif a == "--format=list":
            fmt = "list"
        elif a == "--git":
            rest.append("__GIT__")
        elif a.startswith("--baseline="):
            baseline = a.split("=", 1)[1]
        elif a.startswith("--symbols="):
            explicit = [s.strip() for s in a.split("=", 1)[1].split(",") if s.strip()]
        else:
            rest.append(a)

    if explicit:
        syms, fallback, paths = set(explicit), [], ["<--symbols>"]
    else:
        if "__GIT__" in rest:
            rest.remove("__GIT__")
            paths = changed_files_from_git(baseline)
        else:
            paths = rest
        if not paths:
            raise SystemExit(__doc__)
        syms, fallback = changed_symbols(paths, baseline)

    hit = tests_for(syms)

    print("# baseline: %s" % baseline)
    print("# changed files: %d" % len(paths))
    if fallback:
        print("!! 以下文件相对基线**无 diff**（可能已提交）⇒ 退化为整文件符号，命中会被放大：")
        for p in fallback:
            print("   -", p)
    esc = [p for p in paths if any(g in p for g in GLOBAL_SURFACES)]
    if esc:
        print("!! 触及共享面 ⇒ 建议升档（半全量/全量）：")
        for p in esc:
            print("   -", p)
    print("# symbols: %d -> test classes: %d" % (len(syms), len(hit)))
    classes = sorted(hit)
    if fmt == "args":
        print(" ".join('--tests "%s"' % c for c in classes))
    else:
        for c in classes:
            print(c)
        print("\n# gradle:")
        print("./gradlew :app:testDevelopmentDebugUnitTest --no-build-cache " +
              " ".join('--tests "%s"' % c for c in classes))


if __name__ == "__main__":
    main()
