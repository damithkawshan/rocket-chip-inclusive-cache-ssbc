"""How fast does each parked-side twin resolve, and did the native-side branch ever get a real chance?"""
import re, sys, collections
path = sys.argv[1]
dirw   = re.compile(r'DIR-WRITE set=(\d+) way=(\d+) state=(\d+) displaced=(\d+) tag=\s*(\d+)')
commit = re.compile(r'MIG-COMMIT srcSet=(\d+) dstSet=(\d+)')
entry  = {}
native = collections.defaultdict(dict)
parked = collections.defaultdict(dict)
partner, source = {}, {}
open_twins = {}
lifetimes  = []
opportunities = 0          # native writes to a paired source while the partner held >=1 parked line
opp_parked_pop = []
def rm(s, w):
    e = entry.get((s, w))
    if not e or e[0] == 0: return None
    st, disp, tag = e
    (parked if disp else native)[s].pop(tag, None)
    return (disp, tag)
for ln, line in enumerate(open(path)):
    if 'MIG-COMMIT' in line:
        m = commit.search(line)
        if m: a,b = int(m.group(1)), int(m.group(2)); partner[a]=b; source[b]=a
        continue
    if 'DIR-WRITE' not in line: continue
    m = dirw.search(line)
    if not m: continue
    s, w, st, disp, tag = (int(x) for x in m.groups())
    gone = rm(s, w)
    if gone:
        gdisp, gtag = gone
        S, D = (source.get(s), s) if gdisp else (s, partner.get(s))
        if S is not None and D is not None and (S, D, gtag) in open_twins:
            lifetimes.append(ln - open_twins.pop((S, D, gtag)))
    if st == 0:
        entry[(s, w)] = [0, 0, 0]; continue
    if disp: parked[s][tag] = w
    else:
        native[s][tag] = w
        D = partner.get(s)
        if D is not None and parked[D]:
            opportunities += 1; opp_parked_pop.append(len(parked[D]))
    entry[(s, w)] = [st, disp, tag]
    if disp:
        S = source.get(s)
        if S is not None and tag in native[S]: open_twins[(S, s, tag)] = ln
    else:
        D = partner.get(s)
        if D is not None and tag in parked[D]: open_twins[(s, D, tag)] = ln
lifetimes.sort()
n = len(lifetimes)
print(f"resolved twins: {n:,}   still open at end of run: {len(open_twins)}")
if n:
    print(f"coexistence, in DIR-WRITE events between creation and resolution:")
    print(f"   min {lifetimes[0]}   median {lifetimes[n//2]}   p90 {lifetimes[int(n*0.9)]}   p99 {lifetimes[int(n*0.99)]}   max {lifetimes[-1]}")
    print(f"   resolved within 10 events: {sum(1 for x in lifetimes if x<=10)/n*100:.2f}%")
    print(f"   resolved within 50 events: {sum(1 for x in lifetimes if x<=50)/n*100:.2f}%")
print()
print(f"NATIVE-side detector opportunities (a line installed natively in a paired source")
print(f"while its partner held at least one parked line): {opportunities:,}")
if opp_parked_pop:
    print(f"   mean parked-population of the partner at those moments: {sum(opp_parked_pop)/len(opp_parked_pop):.2f} ways")
