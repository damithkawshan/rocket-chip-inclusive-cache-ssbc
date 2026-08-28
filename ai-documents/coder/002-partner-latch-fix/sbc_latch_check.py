#!/usr/bin/env python3
"""002 acceptance: the four Step-0b numbers, from one stress-test .out.

  usage: sbc_latch_check.py <migration_stress_test.out>

1. wrong-partner searches  - a SEC-HIT/SEC-MISS whose partner is not that set's committed partner
2. unsearched misses       - an OUTER-A(hit=0) on a paired source with no preceding SEC-MISS,
                             split by whether the set was already paired
3. native-side twins       - a line installed natively in a paired source while a parked copy of it
                             was still live in the partner (the stale-twin failure mode)
4. parked-side twins       - the migration's own mig_dir1 -> refill write pair; the detector's
                             positive control, not a defect
"""
import re, sys, collections

path = sys.argv[1]
dirw   = re.compile(r'DIR-WRITE set=(\d+) way=(\d+) state=(\d+) displaced=(\d+) tag=\s*(\d+)')
commit = re.compile(r'MIG-COMMIT srcSet=(\d+) dstSet=(\d+)')
srch   = re.compile(r'SEC-(HIT|MISS) set=(\d+) partner=(\d+)')
outera = re.compile(r'OUTER-A addr=0x[0-9a-f]+ set=(\d+) perm=\d+ param=\d+ hit=(\d+)')

# Pass 1: learn which sets ever become paired sources, so pre-pairing misses on them can be
# counted rather than dropped (they are correct - nothing is parked yet - but the split matters).
ever_source = set()
for line in open(path):
    if 'MIG-COMMIT' in line:
        m = commit.search(line)
        if m: ever_source.add(int(m.group(1)))

partner, source = {}, {}
entry  = {}
native = collections.defaultdict(dict)
parked = collections.defaultdict(dict)
pending, paired = collections.defaultdict(bool), collections.defaultdict(bool)
searched = collections.Counter(); before = collections.Counter(); after = collections.Counter()
wrong_partner = []; native_twins = []; parked_twins = 0; opportunities = 0
sec = collections.Counter(); upgrades = 0

def rm(s, w):
    e = entry.get((s, w))
    if not e or e[0] == 0: return
    (parked if e[1] else native)[s].pop(e[2], None)

for ln, line in enumerate(open(path)):
    if '[SBC]' not in line: continue
    if 'MIG-COMMIT' in line:
        m = commit.search(line)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            partner[a] = b; source[b] = a; paired[a] = True
    elif 'SEC-HIT' in line or 'SEC-MISS' in line:
        m = srch.search(line)
        if not m: continue
        kind, s, p = m.group(1), int(m.group(2)), int(m.group(3))
        sec[kind] += 1
        if partner.get(s) != p: wrong_partner.append((ln, s, p, partner.get(s)))
        pending[s] = (kind == 'MISS')
    elif 'OUTER-A' in line:
        m = outera.search(line)
        if not m: continue
        s, hit = int(m.group(1)), int(m.group(2))
        if hit: upgrades += 1; continue
        if s not in ever_source: continue
        if pending[s]: searched[s] += 1; pending[s] = False
        elif paired[s]: after[s] += 1
        else: before[s] += 1
    elif 'DIR-WRITE' in line:
        m = dirw.search(line)
        if not m: continue
        s, w, st, disp, tag = (int(x) for x in m.groups())
        rm(s, w)
        if st == 0: entry[(s, w)] = [0, 0, 0]; continue
        if disp: parked[s][tag] = w
        else:
            native[s][tag] = w
            D = partner.get(s)
            if D is not None and parked[D]: opportunities += 1
        entry[(s, w)] = [st, disp, tag]
        if disp:
            S = source.get(s)
            if S is not None and tag in native[S]: parked_twins += 1
        else:
            D = partner.get(s)
            if D is not None and tag in parked[D]: native_twins.append((ln, s, D, tag))

print(f"pairings                     : {dict(sorted(partner.items()))}")
print(f"SEC-HIT / SEC-MISS           : {sec['HIT']:,} / {sec['MISS']:,}")
print(f"OUTER-A upgrades (hit=1)     : {upgrades:,}   [no search expected - excluded]")
print()
print(f"1. wrong-partner searches    : {len(wrong_partner):<8,}  (required: 0)")
print(f"2. unsearched, AFTER pairing : {sum(after.values()):<8,}  (required: 0)")
print(f"   unsearched, BEFORE pairing: {sum(before.values()):<8,}  (expected: ~150, these are correct)")
print(f"   searched                  : {sum(searched.values()):,}")
print(f"3. NATIVE-side twins         : {len(native_twins):<8,}  (required: 0)")
print(f"4. parked-side twins         : {parked_twins:<8,}  [positive control - migration transient]")
print(f"   native-side opportunities : {opportunities:,}")
if after:  print("\n   after-pairing unsearched, by set:", dict(after))
if before: print("   before-pairing unsearched, by set:", dict(before))
for t in wrong_partner[:5]: print("   WRONG PARTNER:", t)
for t in native_twins[:5]:  print("   NATIVE TWIN  :", t)
