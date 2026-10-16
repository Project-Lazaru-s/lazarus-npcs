"""Rogue soldier loot that matches what they carry (taser/baton, spec-ops or soldier armour, no firearms).

Idempotent. Rerun after build_drops.py, which regenerates these tables.
Usage: python patch_soldier_loot.py "<profile>"
"""
import json
import sys
from pathlib import Path

NOT_WORN = {"apocalypsenow:army_helmet", "apocalypsenow:bulletproof_vest_chestplate"}


def patch(table):
    pools = []
    for pool in table["pools"]:
        names = {entry.get("name", "") for entry in pool["entries"]}
        chance = next(c for c in pool["conditions"] if c["condition"] == "minecraft:random_chance")
        if any(name.startswith("kubejs:chests/weapon_kit_") for name in names):
            continue  # they never carry firearms, so no gun kits
        if "tacz:ammo" in names:
            chance["chance"] = 0.15
            for entry in pool["entries"]:
                for function in entry.get("functions", []):
                    if function["function"] == "minecraft:set_count":
                        function["count"] = {"min": 4, "max": 10}
        elif "apocalypsenow:military_knife" in names:
            chance["chance"] = 0.08
            pool["entries"] = [e for e in pool["entries"] if e.get("name") not in NOT_WORN]
        pools.append(pool)
    table["pools"] = pools
    return table


def main():
    tables = Path(sys.argv[1]) / "kubejs/data/apocalypsenow/loot_tables/entities"
    for name in ("soldiers.json", "soldiers_02.json"):
        path = tables / name
        table = patch(json.loads(path.read_text(encoding="utf-8")))
        path.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8")
        print(name, [round(next(c["chance"] for c in p["conditions"] if c["condition"] == "minecraft:random_chance"), 2)
                     for p in table["pools"]])


if __name__ == "__main__":
    main()
