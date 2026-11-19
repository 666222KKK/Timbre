#!/usr/bin/env python3
"""Packs a directory (manifest.json + scripts) into a .jdr package.

Usage:
    python scripts/pack_jdr.py samples/itingshu itingshu.jdr
"""
import json
import re
import sys
import zipfile
from pathlib import Path


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit("用法: python pack_jdr.py <目录> <输出.jdr>")
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    if not src.is_dir():
        sys.exit(f"不是目录: {src}")
    files = sorted(p for p in src.rglob("*") if p.is_file())
    if not files:
        sys.exit(f"目录为空: {src}")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for path in files:
            name = path.relative_to(src).as_posix()
            if name.startswith("/") or ".." in name.split("/"):
                sys.exit(f"非法文件名: {name}")
            z.write(path, name)
            print(f"  + {name} ({path.stat().st_size} bytes)")
    validate(src, out)
    print(f"已生成 {out.resolve()} ({out.stat().st_size} bytes)")


def validate(src: Path, out: Path) -> None:
    """Catches package mistakes the app rejects at import time - most
    importantly a script whose registerSource id no longer matches the
    manifest (搜索报「无法连接在线书源」的常见原因).

    混淆过的脚本无法静态识别注册 id：此时跳过该检查并提示，id 的一致性
    由引擎在导入/加载时兜底校验。"""
    with zipfile.ZipFile(out) as z:
        names = set(z.namelist())
        manifest = json.loads(z.read("manifest.json").decode("utf-8"))
        for source in manifest.get("sources", []):
            script = source.get("script", "")
            if script not in names:
                sys.exit(f"manifest 引用的脚本不存在: {script}")
            registered = re.search(
                r"registerSource\(\{\s*\n?\s*id:\s*'([^']+)'",
                z.read(script).decode("utf-8", errors="replace"),
            )
            if registered is None:
                print(
                    f"  ! {script}: 无法静态识别 registerSource id"
                    "（混淆脚本？），跳过静态校验；引擎运行时仍会校验 id 一致性"
                )
            elif registered.group(1) != source.get("id"):
                sys.exit(
                    f"{script} 注册的 id('{registered.group(1)}') 与 manifest 的"
                    f" id('{source.get('id')}') 不一致 —— 请同步修改脚本内的 registerSource id"
                )
            validate_recommenders(z, names, source)


def validate_recommenders(z, names, source) -> None:
    """推荐位：外置 JSON 是否进包、槽位继承 script、entry/cacheMinutes 合法性。"""
    slots = list(source.get("recommenders", []))
    config_name = source.get("recommenderConfig", "")
    if config_name:
        if config_name not in names:
            sys.exit(f"源 {source.get('id')} 的 recommenderConfig 文件不存在: {config_name}")
        config = json.loads(z.read(config_name).decode("utf-8"))
        default_script = config.get("script", "")
        for slot in config.get("recommenders", []):
            slot = dict(slot)
            if not slot.get("script"):
                slot["script"] = default_script
            slots.append(slot)

    seen: set[str] = set()
    for slot in slots:
        sid = slot.get("id", "")
        entry = slot.get("entry", "")
        script = slot.get("script", "")
        if not sid or not entry:
            sys.exit(f"源 {source.get('id')} 的推荐位缺少 id 或 entry: {slot}")
        if sid in seen:
            sys.exit(f"源 {source.get('id')} 的推荐位 id 重复: {sid}")
        seen.add(sid)
        if not script:
            sys.exit(f"推荐位 {sid} 缺少 script（配置文件未提供默认 script）")
        if script not in names:
            sys.exit(f"推荐位 {sid} 的脚本不存在: {script}")
        body = z.read(script).decode("utf-8", errors="replace")
        if entry not in body:
            print(f"  ! {script}: 未找到 {entry}（可能被混淆），跳过 entry 校验")
        cache = slot.get("cacheMinutes", 0)
        if not isinstance(cache, int) or isinstance(cache, bool) or not 0 <= cache <= 10080:
            sys.exit(f"推荐位 {sid} 的 cacheMinutes 非法: {cache}（应为 0-10080 的整数）")


if __name__ == "__main__":
    main()
