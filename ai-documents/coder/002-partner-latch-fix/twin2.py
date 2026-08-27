"""Split twins by which side created them, and measure how long they coexist.
   PARKED-side  = displaced copy installed while the line is still native  -> migration transient
   NATIVE-side  = a line installed natively while a parked copy is ALREADY live -> the A1 failure mode
                  (a DRAM refill next to a live parked copy)"""
import re, sys, collections
path = sys.argv[1]
dirw   = re.compile(r'DIR-WRITE set=(\d+) way=(\d+) state=(\d+) displaced=(\d+) tag=\s*(\d+)')
commit = re.compile(r'MIG-COMMIT srcSet=(\d+) dstSet=(\d+)')
sechit = re.compile(r'SEC-HIT set=(\d+) partner=(\d+) way=(\d+)')
outera = re.compile(r'OUTER-A addr=0x([0-9a-f]+) set=(\d+) .* hit=(\d+)')

entry  = {}
native = collections.defaultdict(dict)                 # set -> tag -> (set,way)
parked = collections.defaultdict(dict)
partner, source = {}, {}
parked_side, native_side = [], []
open_twins = {}                                        # (S,D,tag) -> creating line no
life_parked, life_native = [], []
inst = 0; twinned_by_native = set()
sechit_total = 0; sechit_on_native_twin = []

def rm(s, w):
    e = entry.get((s, w))
    if not e or e[0] == 0: return
    st, disp, tag, iid = e
    (parked if disp else native)[s].pop(tag, None)

def close(S, D, tag, ln, bucket):
    k = (S, D, tag)
    if k in open_twins:
        bucket.append(ln - open_twins.pop(k))

for ln, line in enumerate(open(path)):
    if 'DIR-WRITE' in line:
        m = dirw.search(line)
        if not m: continue
        s, w, st, disp, tag = (int(x) for x in m.groups())
        old = entry.get((s, w))
        rm(s, w)
        # a removal may resolve an open twin
        if old and old[0] != 0:
            ot = old[2]
            if old[1]:
                S = source.get(s)
                if S is not None: close(S, s, ot, ln, life_parked if (S,s,ot) in open_twins else life_parked)
            else:
                D = partner.get(s)
                if D is not None: close(s, D, ot, ln, life_native)
        iid = None
        if st != 0:
            if disp:
                inst += 1; iid = inst; parked[s][tag] = (s, w)
            else:
                native[s][tag] = (s, w)
        entry[(s, w)] = [st, disp, tag, iid]
        if st == 0: continue
        if disp:
            S = source.get(s)
            if S is not None and tag in native[S]:
                parked_side.append((ln, S, s, tag)); open_twins[(S, s, tag)] = ln
        else:
            D = partner.get(s)
            if D is not None and tag in parked[D]:
                native_side.append((ln, s, D, tag)); open_twins[(s, D, tag)] = ln
                ds, dw = parked[D][tag]
                if entry[(ds, dw)][3] is not None: twinned_by_native.add(entry[(ds, dw)][3])
    elif 'MIG-COMMIT' in line:
        m = commit.search(line)
        if m: a, b = int(m.group(1)), int(m.group(2)); partner[a] = b; source[b] = a
    elif 'SEC-HIT' in line:
        m = sechit.search(line)
        if m:
            sechit_total += 1
            st_, pt, wy = (int(x) for x in m.groups())
            e = entry.get((pt, wy))
            if e and e[3] is not None and e[3] in twinned_by_native:
                sechit_on_native_twin.append((ln, st_, pt, wy, e[2]))

print(f"pairings: {dict(sorted(partner.items()))}\n")
print(f"PARKED-side twins (displaced installed while still native — migration transient): {len(parked_side):,}")
print(f"NATIVE-side twins (refill next to a LIVE parked copy — the A1 failure mode)     : {len(native_side):,}")
print()
print(f"SEC-HITs total                                        : {sechit_total:,}")
print(f"SEC-HITs serving a copy that was ever NATIVE-side twinned: {len(sechit_on_native_twin):,}")
if life_parked:
    life_parked.sort()
    print(f"\nparked-side twin coexistence (in DIR-WRITE lines): median {life_parked[len(life_parked)//2]}, "
          f"p90 {life_parked[int(len(life_parked)*0.9)]}, max {life_parked[-1]}, n={len(life_parked):,}")
if native_side:
    print("\nfirst 10 NATIVE-side twins (line, S, D, tag):")
    for t in native_side[:10]: print("  ", t)
