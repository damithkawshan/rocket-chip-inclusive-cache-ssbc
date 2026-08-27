# TASK 002 — fix the partner-set latch, then land commit 4

**Opened:** 2026-08-28 · **Branch:** `sbc-paper-aligned` · **Predecessor:** `001-phase3-reuse-loop`

**You inherit an uncommitted working tree.** `MSHR.scala`, `Scheduler.scala` and
`SetBalanceUnit.scala` hold the commit-4 search-and-repatriate work from 001. It is correct except
for one defect. Do not revert it, do not restart it.

**The full derivation is in `001-phase3-reuse-loop/TASK.md`, Amendment 2 §B1–B2, and the symptom
record is in `001/REPORT.md`.** This file does not repeat it. It says what to do.

---

## 0. The point of this task, in one paragraph

Commit 4 closed the reuse loop — 2,287 secondary hits, the first time a parked line ever returned
anything — but two stress cases fail with **silently wrong data** and your bisect isolated the serve
path. The cause is not the copy. **An MSHR can latch another set's partner and then search that
set.** The block copy moves the right bytes; it moves the **wrong line**. Fix the latch, and commit 4
becomes the first build in this project that can plausibly beat the SBC-off control.

**The defect in one line:** the pairing lookup is keyed to the request *waiting at the input port*,
broadcast to every MSHR, and latched by any MSHR whose allocate is not a tag-`repeat` — but `repeat`
is a **tag** comparison being used to decide whether the MSHR's **set** changed. On a reload the set
never changes.

Relevant sites: `Scheduler.scala:208, 313-314, 329-330, 352, 362, 584-585` · `MSHR.scala:994,
999-1000, 1071, 1237`.

---

## 1. Build order — five steps, report after each

| # | Step | Gate |
|---|---|---|
| 0 | **Confirm from an existing log.** No re-run. | ⛔ **HARD GATE** — if it comes back the other way, stop and say so |
| 1 | The fix | elaborates, SBC-off bit-exact |
| 2 | Correctness re-verification | stress 7/7, matmult 29824 |
| 3 | Is the partner fence necessary? | a measured answer either way |
| 4 | Deadlock audit + watchdog | a hang becomes a named assert |
| 5 | Numbers against the SBC-off control | reported, whichever way they fall |

---

## Step 0 — GATE. Confirm before writing a line of RTL.

From the commit-4 stress log you already have:

1. `grep MIG-COMMIT` → the distinct `(srcSet, dstSet)` pairs. These are the **true** pairings.
2. `grep SEC-HIT` → the distinct `(set, partner)` pairs. These are what the searches **actually used**.
3. Compare.

**Any `SEC-HIT` whose partner is not that set's committed partner is a direct sighting of the bug.**

Put both lists in `REPORT.md` either way.

- **They disagree** → the diagnosis holds. Go to Step 1.
- **Every SEC-HIT pair matches a committed pair** → ⛔ **the diagnosis is wrong.** Stop, say so
  plainly in `REPORT.md`, and the SCU serve-path lead from 001 goes back to the top of the list. **Do
  not implement Step 1 on my say-so.** You were four-times wrong on this bug and stopped guessing;
  do the same to me.

---

## Step 1 — the fix

### 1a. Take Option B. Option A is the fallback.

- **Option B — key the query to the asker.** Delete the broadcast latch. Key the pairing query to
  the MSHR the same way `destQuery` already is (`Scheduler.scala:540-541`), and latch `pairSetReg`
  at the moment `searching` is armed rather than at allocate. This makes the wrong-set class
  **unrepresentable** rather than fixed once. `status.bits.set` is already correct at the
  directory-result cycle, because `request` is latched at allocate.
- **Option A — fallback.** Add a `fresh` bit to the allocate bundle. The two paths are already
  distinct (`Scheduler.scala:416` fresh vs `313-314` reload). Latch only when `fresh`; **hold**
  otherwise.

Use A **only** if B fights the elaboration loop-freedom rules — see the LOOP FREEDOM note at
`MSHR.scala:249`, which cost a deadlocked elaboration once already. If you switch, say why.

### 1b. Fix `migAdviceValidReg` in the same change

`MSHR.scala:994` has the identical defect and has been live **since Phase 2** — a reloading MSHR
could latch another set's "this set is hot" and migrate a victim out of a set that was never hot.
Data-safe, which is why nothing ever caught it, but it is noise in every migration number we have.

### 1c. Add one identity assert

Every existing check tests **shape** (one-hot, clean, not-self). None tests **identity** — which is
exactly why this was silent. At search time, assert the partner really is this set's partner, read
**live from the AT**, not from the latch. Behind `enableSetBalancing`.

### 1d. Expect a second finding

`MSHR.scala:915` — `assert(!dstClaim.valid || !pairValidReg || dstClaim.bits === pairSetReg)` — is
skipped whenever `pairValidReg` is wrongly **false**, which is the common mis-latch outcome. It has
been passing partly by vacuity. **If it starts firing after the fix, that is a real finding, not a
regression.** Report it. Do not silence it.

### 1e. Also expect SecHits to rise

Both options fix a mirror-image loss: today a same-set reload with a new tag **clears** a valid
pairing, so the MSHR skips a search it should have done.

---

## Step 2 — correctness re-verification

- `migration_stress_test`: **7/7 PASS, 0 asserts**, specifically including `case_full_dirty_dst` and
  `case_reaccess_migrated`.
- `bringup_matmult`: checksum **29824**, 0 asserts.
- Every `SEC-HIT set=X partner=P` in the log has `P` equal to `X`'s committed partner (the Step-0
  check, re-run on the fixed build).
- SBC **off** unchanged — the fix must be inert when `enableSetBalancing` is false.

---

## Step 3 — is the partner fence necessary?

The fence (`secValid` / `secSet` in `MSHRStatus`, `partnerBusy`, and the `dstSetConflict` term) was
built on a diagnosis now known to be wrong. You said you would rather hand that decision over than
guess — right call. Now it can be decided by measurement.

- Re-run Step 2 with the fence **in** and with it **out**.
- If both pass: say so, and recommend. Unjustified mechanism in the fence path is not free — it also
  costs throughput by stalling an innocent set.
- If removing it fails: keep it, and say what failed. That is a genuine finding about the search
  window, not a retreat.

**Do this after the latch fix, never before.** While the partner is wrong the fence is *actively
harmful*, so a measurement taken now would be meaningless.

---

## Step 4 — deadlock audit

Your no-deadlock note reads: *"under 1:1 pinning a destination is never a source, so the MSHR
holding the partner never waits on us."* **A wrong partner voided that premise.** MSHR-A on set X
wrongly points at Z; MSHR-B on set Z wrongly points at X; each waits on `partnerBusy`. Neither can
retire — `sec_ready = !searching && s_sinval` gates **both** `reload` and the final writeback, and
`!searching` also blocks the outer Acquire.

1. **Re-check the commit-4 hang you attributed to the `d_ready` sibling gate.** That fix was clearly
   right on its own terms. The question is only whether it accounted for **every** hang you saw.
   If you have the log, say so; if you do not, say that instead.
2. **Add a watchdog assert on the `partnerBusy` wait**, same pattern as `migDeferCtr`
   (`MSHR.scala:918-920`). Today the only signal is a `sbcDebug`-gated `SEC-STUCK` printf, so a
   production run just stops making progress. Turn it into a named assert with a cycle count.
3. After the fix the premise is restored and the assert should never fire. That is the point —
   it protects the premise, it does not paper over it.

---

## Step 5 — numbers

Report, whichever way they fall:

| | before fix | after fix |
|---|---|---|
| `SEC-HIT` total | | |
| `SEC-MISS` total | | |
| `f` = hits / (hits + misses) | **do not quote the before value** | |
| matmult cycles | 15,699,736 | |
| matmult `OUTER-A` | 13,146 | |
| stress asserts | 0 | |

Control to beat: **SBC off = 15,683,316 cycles / 13,147 OUTER-A.**

Two notes:

- **`f` from the current build is void.** It is polluted three ways: searches that should have
  happened did not, searches ran against the wrong set (false SecMiss), and weak-permission rejects
  destroyed foreign parked lines. Report the raw counts, not the ratio, for the "before" column.
- **A null result is information.** matmult made only 11 migrations. If secondary hits do not move
  the cycle count, that is a real finding about the workload, not a failure of the fix. Say it
  plainly. Do not go looking for a benchmark that flatters the result — that is my job, not yours.

---

## Scope boundaries

- **Do not rebuild `s_verify`.** The copy moves the right bytes from the place it was told to read —
  a copy verifier would have **passed**. The `CLAUDE.md` note that made it look like the lead is
  about a different failure mode. It stays parked.
- **Do not touch destination eligibility**, or the `ABORT-DST` rise you correctly logged in 001 and
  correctly did not act on.
- **Do not build teardown.** That is task 003.
- **Do not build temporary protection for displaced lines.** Settled by your own measurement in 001.
- **Strip the `DIR-WRITE` printf** before anything lands — you flagged it as expensive. Keep
  `SEC-STUCK` and `STALL`.

---

## Traps carried forward from 001 — all four cost you a cycle already

1. **A gate added in one place and missed in its sibling.** `707445c` was this, and so was your own
   `d_ready` deadlock. Name the condition once, use the name at both sites.
2. **`+verbose` stderr is the full instruction trace.** Never pipe it through `awk`/`grep` live —
   ~100x slowdown that looks exactly like a hang. Post-process the `.out`.
3. **Never overlap two `make run-binary` runs for the same CONFIG.** They interleave into one file
   that looks like a real run. Kill the `make` PID and its children, not just the wrapper.
4. **Pass `TIMEOUT_CYCLES=` as a make variable**, not `SIM_FLAGS=+max-cycles=`.

---

## When to stop and ask

- **Step 0 comes back clean** → stop immediately. See the gate.
- **Option B fights loop-freedom and Option A also looks wrong** → stop, describe both, ask.
- **The fix passes but `SEC-HIT` collapses to near zero** → stop. That would mean the pairings
  themselves are wrong, which is a different and bigger problem than the latch.
- **The `MSHR.scala:915` assert fires and you cannot see why** → report it, do not silence it.

---

## Acceptance

1. Step 0's two pair lists, and which way they came out.
2. Stress **7/7, 0 asserts**; matmult checksum **29824**.
3. Every `SEC-HIT` partner equals that set's committed partner.
4. A measured answer on the fence (Step 3).
5. A named assert covering the `partnerBusy` wait (Step 4).
6. The Step-5 table filled in, including the honest cycle count against the control.
7. `REPORT.md` verdict filled in.
