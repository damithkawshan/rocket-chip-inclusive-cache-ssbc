#!/usr/bin/env python3
"""plru_replay.py - task 008 check V3: replay the PLRU tracker from the sim printfs.

Rebuilds every set's PLRU tree from `[SBC] PLRU-TOUCH` lines (touches from EARLIER cycles only; within
a cycle the access (src=0) is applied before the install (src=1)), then checks every
`[SBC] PLRU-VICTIM` line:
  * plruWay == the model's victim, on every line (both modes)
  * chosen == plruWay whenever usePlru=1 and tier=2 (in random mode tier 2 is the LFSR way)
It also reports tier counts, how often res != chosen (write-bypass tag match), and how often a
migration install lands on the same way as the previous install into that set.

The model is a line-for-line copy of rocket-chip util/Replacement.scala PseudoLRU.

Usage: plru_replay.py <run-dir | sbc.log> [more ...]   (writes <run-dir>/plru_replay.txt)
"""
import os
import re
import sys
from collections import Counter, defaultdict


def log2ceil(n):
    return (n - 1).bit_length()


def next_state(state, way, n):
    """PseudoLRU.get_next_state(state, touch_way, tree_nways)."""
    if n > 2:
        right = 1 << (log2ceil(n) - 1)
        left = n - right
        set_left_older = 0 if (way >> (log2ceil(n) - 1)) & 1 else 1
        left_state = (state >> (right - 1)) & ((1 << (left - 1)) - 1)
        right_state = state & ((1 << (right - 1)) - 1)
        rway = way & ((1 << log2ceil(right)) - 1)
        new_right = next_state(right_state, rway, right) if set_left_older else right_state
        if left > 1:
            lway = way & ((1 << log2ceil(left)) - 1)
            new_left = left_state if set_left_older else next_state(left_state, lway, left)
            return (set_left_older << (n - 2)) | (new_left << (right - 1)) | new_right
        return (set_left_older << (n - 2)) | new_right
    if n == 2:
        return 0 if way & 1 else 1
    return 0


def rw_width(n):
    if n > 2:
        right = 1 << (log2ceil(n) - 1)
        left = n - right
        return 1 + (max(rw_width(left), rw_width(right)) if left > 1 else max(1, rw_width(right)))
    return 1


def replace_way(state, n):
    """PseudoLRU.get_replace_way(state, tree_nways)."""
    if n > 2:
        right = 1 << (log2ceil(n) - 1)
        left = n - right
        left_older = (state >> (n - 2)) & 1
        left_state = (state >> (right - 1)) & ((1 << (left - 1)) - 1)
        right_state = state & ((1 << (right - 1)) - 1)
        if left > 1:
            sub = replace_way(left_state, left) if left_older else replace_way(right_state, right)
            w = max(rw_width(left), rw_width(right))
        else:
            sub = 0 if left_older else replace_way(right_state, right)
            w = max(1, rw_width(right))
        return (left_older << w) | sub
    if n == 2:
        return state & 1
    return 0


RE_TOUCH = re.compile(r"\[SBC\] PLRU-TOUCH cyc=\s*(\d+) set=\s*(\d+) way=\s*(\d+) src=\s*(\d+)")
RE_VICTIM = re.compile(r"\[SBC\] PLRU-VICTIM cyc=\s*(\d+) set=\s*(\d+) plruWay=\s*(\d+) chosen=\s*(\d+)"
                       r" res=\s*(\d+) usePlru=\s*(\d+) tier=\s*(\d+) busy=\s*([01]+) internal=\s*(\d+)")


def replay(path, ways):
    state = defaultdict(int)
    touches, victims = [], []   # events of the current cycle
    cur = -1
    st = Counter()
    tiers = Counter()           # (usePlru, internal, tier)
    first_bad = []
    last_install = {}
    installs = Counter()

    def flush():
        # Victims read the state as it was at the START of the cycle; then this cycle's touches land.
        for (c, s, pw, ch, res, up, tier, internal) in victims:
            st["victims"] += 1
            tiers[(up, internal, tier)] += 1
            model = replace_way(state[s], ways)
            if pw != model:
                st["plruWay_mismatch"] += 1
                if len(first_bad) < 10:
                    first_bad.append(f"cyc={c} set={s} plruWay={pw} model={model} state={state[s]:0{ways-1}b}")
            if up == 1 and tier == 2:
                st["tier2_plru"] += 1
                if ch != pw:
                    st["chosen_ne_plruWay"] += 1
                    if len(first_bad) < 10:
                        first_bad.append(f"cyc={c} set={s} tier=2 chosen={ch} plruWay={pw}")
            if res != ch:
                st["res_ne_chosen"] += 1
        for (c, s, w, src) in sorted(touches, key=lambda t: t[3]):
            st[f"touch_src{src}"] += 1
            state[s] = next_state(state[s], w, ways)
            if src == 1:
                if s in last_install:
                    installs["with_previous"] += 1
                    if last_install[s] == w:
                        installs["same_way_as_previous"] += 1
                last_install[s] = w
        touches.clear()
        victims.clear()

    with open(path, errors="replace") as f:
        for line in f:
            if "PLRU-" not in line:
                continue
            m = RE_TOUCH.search(line)
            if m:
                c, s, w, src = map(int, m.groups())
                ev = ("t", (c, s, w, src))
            else:
                m = RE_VICTIM.search(line)
                if not m:
                    st["unparsed"] += 1
                    continue
                g = m.groups()
                c = int(g[0])
                ev = ("v", (c, int(g[1]), int(g[2]), int(g[3]), int(g[4]), int(g[5]), int(g[6]), int(g[8])))
            if c != cur:
                if c < cur:
                    st["cycle_went_backwards"] += 1
                flush()
                cur = c
            (touches if ev[0] == "t" else victims).append(ev[1])
    flush()
    return st, tiers, installs, first_bad


def main():
    ways = int(os.environ.get("PLRU_WAYS", "8"))
    args = sys.argv[1:] or ["sbc.log"]
    for a in args:
        log = os.path.join(a, "sbc.log") if os.path.isdir(a) else a
        st, tiers, installs, bad = replay(log, ways)
        out = [f"== {log}  (ways={ways})"]
        ok = st["plruWay_mismatch"] == 0 and st["chosen_ne_plruWay"] == 0 and st["victims"] > 0 \
            and st["cycle_went_backwards"] == 0 and st["unparsed"] == 0
        out.append(f"V3 verdict            : {'PASS' if ok else 'FAIL'}")
        out.append(f"victim lines          : {st['victims']}")
        out.append(f"touches access/install: {st['touch_src0']} / {st['touch_src1']}")
        out.append(f"plruWay != model      : {st['plruWay_mismatch']}")
        out.append(f"tier2 with usePlru=1  : {st['tier2_plru']}   chosen != plruWay: {st['chosen_ne_plruWay']}")
        out.append(f"res != chosen         : {st['res_ne_chosen']}   (write-bypass tag match)")
        out.append(f"unparsed / backwards  : {st['unparsed']} / {st['cycle_went_backwards']}")
        out.append("tier counts (usePlru, internal, tier) - tier 0 invalid, 1 evictable, 2 policy, 3 lowest free:")
        for k in sorted(tiers):
            out.append(f"  usePlru={k[0]} internal={k[1]} tier={k[2]} : {tiers[k]}")
        wp = installs["with_previous"]
        same = installs["same_way_as_previous"]
        out.append(f"installs with a previous install in the set: {wp}; same way as the previous one: {same}"
                   + (f" ({100.0 * same / wp:.1f}%)" if wp else ""))
        if bad:
            out.append("first mismatches:")
            out += ["  " + b for b in bad]
        text = "\n".join(out)
        print(text)
        if os.path.isdir(a):
            with open(os.path.join(a, "plru_replay.txt"), "w") as f:
                f.write(text + "\n")


if __name__ == "__main__":
    main()
