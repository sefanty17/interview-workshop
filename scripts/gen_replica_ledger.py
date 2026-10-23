"""生成前端复刻台账骨架（docs/02-复刻台账.md）。

扫描 references/lingchat 里设置面板、自由对话房间、工具条、粒子动画的全部 .vue，
按批次分组，输出「原版文件 / 行数 / 目标文件 / 状态」表格。
状态列不会被覆盖——重跑时从已有台账里继承状态。
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LING = ROOT / "references" / "lingchat" / "src" / "components"
OUT = ROOT / "docs" / "02-复刻台账.md"
TARGET_PREFIX = "frontend/src/components"

# (批次, 相对 components 的目录, 说明)
GROUPS = [
    ("B1", "settings", "设置外壳", ("SettingsPanel.vue", "SettingsNav.vue")),
    ("B2", "settings/pages", "设置各页", None),
    ("B2", "settings/pages/Adeventure", "冒险面板", None),
    ("B3", "settings/background", "场景管理组件", None),
    ("B3", "settings/scene", "场景编辑弹窗", None),
    ("B3", "settings/character", "角色管理组件", None),
    ("B3", "settings/history", "历史详情弹窗", None),
    ("B4", "game/standard", "房间主结构", (
        "GameDialog.vue", "GameBackground.vue", "GameRolesStage.vue",
        "GameRoleAvatar.vue", "StaticRolePresentation.vue", "GameExtraUI.vue",
    )),
    ("B5", "game/standard/extra", "房间附加层", None),
    ("B5", "game/standard", "房间辅助", ("ScreenshotButton.vue", "TouchAreas.vue")),
    ("B5", "tools", "自由模式工具条", None),
    ("B6", "game/standard/particles", "粒子效果", None),
    ("B6", "game/standard/animations", "背景动画", None),
    ("B6", "game/live2d", "Live2D", None),
]


def status_map() -> dict[str, str]:
    """从已有台账里抽出 目标文件 -> 状态，重跑时保留进度。"""
    if not OUT.exists():
        return {}
    text = OUT.read_text(encoding="utf-8")
    found: dict[str, str] = {}
    for line in text.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 5 and cells[3].startswith(TARGET_PREFIX):
            found[cells[3]] = cells[4]
    return found


def line_count(p: Path) -> int:
    try:
        return len(p.read_text(encoding="utf-8").splitlines())
    except UnicodeDecodeError:
        return 0


def collect() -> tuple[dict[str, list[tuple[str, int, str]]], list[str]]:
    prev = status_map()
    buckets: dict[str, list[tuple[str, int, str]]] = {}
    missing: list[str] = []
    for batch, sub, label, names in GROUPS:
        d = LING / sub
        if not d.exists():
            missing.append(str(d))
            continue
        files = sorted(d.glob("*.vue"))
        for f in files:
            if names is not None and f.name not in names:
                continue
            rel = f"{TARGET_PREFIX}/{sub}/{f.name}"
            buckets.setdefault(f"{batch}\u0000{label}", []).append(
                (f"components/{sub}/{f.name}", line_count(f), prev.get(rel, "待办"))
            )
    return buckets, missing


def main() -> None:
    buckets, missing = collect()
    total_files = sum(len(v) for v in buckets.values())
    total_lines = sum(n for v in buckets.values() for _, n, _ in v)
    done = sum(1 for v in buckets.values() for *_, s in v if s == "通过")

    rows: list[str] = []
    rows.append("# 前端复刻台账\n")
    rows.append("> 由 `scripts/gen_replica_ledger.py` 生成，**状态列自动保留**，可重复运行刷新行数。")
    rows.append("> 状态取值：`待办` → `已派` → `审查中` → `通过` / `打回(n)`。")
    rows.append(f"> 覆盖 {total_files} 个组件 / {total_lines} 行原版代码；已通过 {done} 个。\n")

    for key, items in buckets.items():
        batch, label = key.split("\u0000")
        rows.append(f"## {batch} · {label}（{len(items)} 个 / {sum(n for _, n, _ in items)} 行）\n")
        rows.append("| 原版文件 | 行数 | 目标文件 | 状态 | 备注 |")
        rows.append("|---|---:|---|---|---|")
        for src, n, st in items:
            target = f"{TARGET_PREFIX}/{src[len('components/'):]}"
            rows.append(f"| `{src}` | {n} | `{target}` | {st} |  |")
        rows.append("")

    if missing:
        rows.append("## 缺失目录\n")
        for m in missing:
            rows.append(f"- `{m}`")

    OUT.write_text("\n".join(rows), encoding="utf-8")
    print(f"wrote {OUT} | {total_files} components / {total_lines} lines | passed={done}")


if __name__ == "__main__":
    main()
