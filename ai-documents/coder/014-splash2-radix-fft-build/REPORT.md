# Coder report 014 — build SPLASH-2 `radix` and `fft` for the board

**Started:** 2026-10-07 · **Status:** DONE for TASK §3–§6 and §8 · §5.4 (`fft_noalias`) NOT authorised, not built

> Fill this in as you go, not at the end. A stage with an empty row is not done. Cite log paths —
> never paste logs. Anything unexpected goes under "Findings" and you stop and ask.

## ▶ RESUME HERE

Both binaries are staged and static. Nothing is committed (user: "do not add any commits till we
finalize anything"). **Two TASK.md drifts found in §5.2/§5.3 that change how task 015 should size
`fft` — read Findings F1 and F2 before choosing `-fft-m` / `-fft-l`.** Task 015 S6 is unblocked on the
binary side; its `-fft-m 16` setting is 3 MB of data (12x L2), not 2 MB (8x). Next: task 015 §4.

## Findings

**F1 — `fft` allocates THREE large arrays, not two (TASK §5.2 is low by 1.5x).**
TASK says "two arrays (`fft.C:188`)". `fft.C:188` is `N = 1<<M;`. The allocations are at
`fft.C:228-231`:

```
x      = G_MALLOC(2*(N+rootN*pad_length)*sizeof(double)+PAGE_SIZE)
trans  = G_MALLOC(2*(N+rootN*pad_length)*sizeof(double)+PAGE_SIZE)
umain  = G_MALLOC(2*rootN*sizeof(double))                       // small
umain2 = G_MALLOC(2*(N+rootN*pad_length)*sizeof(double)+PAGE_SIZE)
```

`umain2` is the full roots-of-unity matrix, same size as `x`. It is live: `FFT1D` passes it to
`TwiddleOneCol` (`fft.C:669`) for every column of every transform. So the data footprint is
**3 arrays**. Per phase, only two are touched at once (transposes: `x`+`trans`; twiddle: `trans`+`umain2`),
so "2 arrays" is right for what the L2 sees in one phase, and "3 arrays" is right for the whole run.
I report both below. Table in TASK §5.2 should be read as the per-phase figure.

**F2 — `pad_length` has a second, page-rounding step TASK §5.3 does not mention.**
TASK §5.3 quotes `fft.C:196-215` (the line-size step). The code continues at `fft.C:212-224` and
**recomputes `pad_length` in whole pages** whenever `rowsperproc*rootN*16 >= 4096`. I copied the logic
verbatim into a host C program (scratchpad `fftpad.c`, P=1) and ran it:

| `-m` | `-l` | rootN | pad_length | row stride B | stride in lines |
|---:|---:|---:|---:|---:|---:|
| 10 | 4 / 6 | 32 | 8 | 640 | 10.00 |
| 12 | 4 / 6 | 64 | 4 | 1088 | 17.00 |
| 14 | 4 | 128 | 2 | 2080 | 32.50 |
| 14 | 6 | 128 | 4 | 2112 | 33.00 |
| 16 | 4 | 256 | 1 | 4112 | 64.25 |
| 16 | 6 | 256 | 4 | 4160 | 65.00 |
| 18 | 4 | 512 | 1 | 8208 | 128.25 |
| 18 | 6 | 512 | 4 | 8256 | 129.00 |

- **TASK §5.3's numbers are correct at `-m16`** (1 vs 4 elements, 4112 vs 4160 B). They hold because
  the page rounding happens to be exact there.
- **At `-m12` and below, `-l4` and `-l6` give the identical program.** The `-l` knob only does
  anything for `-m >= 14`. A `-fft-l 4` vs `-fft-l 6` comparison at `-m12` would compare a binary to itself.
- Neither setting is pathological power-of-two aliasing (the row stride is never an exact multiple of
  64 lines), which agrees with TASK §5.3's warning. Report `-l` as a stride change only.

**F3 — build logs.** TASK §0.7 says build logs go in `software/parsec-benchmark/log/`. The 2026-10-07
binaries were built by running the §3 recipe directly (not through `parsecmgmt`), so **no build log
exists** (`find log -newermt 2026-10-07` is empty). I did not rebuild; the binaries on disk are the
2026-10-07 15:33 build, which TASK §3.3 says succeeded.

**F4 — radix: no drift.** Defaults at `radix.C:42-45` and allocations at `radix.C:213-214` are exactly
as TASK says. Other allocations are small (`rank_ff`: `radix*4 + PAGE` ≈ 8 KB per processor).

## Binaries produced (TASK §4.1 gate)

Staged with `cp -n` (cannot overwrite) then `chmod +x`. Both are byte-identical to the build output
(`cmp`). Existing staged files untouched (`lu_ncb`, `barnes` mtime 2026-10-01; `streamcluster` 2026-09-30).

| binary | bytes | `file -b` says | sha256 (first 12) |
|---|---:|---|---|
| `staged/radix` | 863,840 | ELF 64-bit RISC-V, double-float ABI, **statically linked**, with debug_info, not stripped | `0f51463d6d55` |
| `staged/fft` | 922,024 | ELF 64-bit RISC-V, double-float ABI, **statically linked**, with debug_info, not stripped | `9c5f61553380` |

Path prefix: `chipyard/software/parsec-benchmark/`. Neither kernel reads an input file, so nothing else was staged.

## Footprint arithmetic confirmed against source (TASK §5)

| kernel | formula found in source | file:line | matches TASK.md? |
|---|---|---|---|
| radix | `key[0]`, `key[1]` = `num_keys*sizeof(int)` each → **2 × n × 4 B** | `radix.C:213-214` | **yes**. Defaults `-p1 -n262144 -r1024 -m524288` at `radix.C:42-45` also match |
| fft | `x`, `trans`, `umain2` = `2*(N+rootN*pad_length)*8 + PAGE` each → **3 arrays** (2 live per phase) | `fft.C:228-231` | **NO — F1.** TASK says two arrays at `:188`. `M` even check at `fft.C:140-145` matches |

radix footprint (exact, matches TASK §5.1):

| `-n` | footprint | x L2 (256 KB) |
|---:|---:|---:|
| 16384 | 128 KB | 0.5 |
| 32768 | 256 KB | 1.0 |
| 65536 | 512 KB | **2.0** |
| 131072 | 1 MB | 4.0 |
| 262144 | 2 MB | 8.0 |

fft footprint (exact, with page padding from the host calculation; `-l4`):

| `-m` | one array | 2 arrays (per phase) | x L2 | 3 arrays (whole run) | x L2 |
|---:|---:|---:|---:|---:|---:|
| 10 | 20 KB | 40 KB | 0.16 | 60 KB | 0.23 |
| 12 | 68 KB | 136 KB | 0.53 | 204 KB | 0.80 |
| 14 | 260 KB | 520 KB | **2.03** | 780 KB | 3.05 |
| 16 | 1,028 KB | 2,056 KB | 8.03 | 3,084 KB | **12.05** |
| 18 | 4,104 KB | 8,208 KB | 32.1 | 12,312 KB | 48.1 |

TASK §5.2's column ("512 KB / 2 MB / 8 MB") is the **2-array** figure, to within padding. `-m16` is
3 MB total, not 2 MB.

## Recommended starting configuration (TASK §8.4)

| kernel | proposed args | footprint | x L2 (256 KB) | arithmetic |
|---|---|---:|---:|---|
| radix | `-p1 -n65536 -r1024 -m524288` | 512 KB | **2.00** | 2 × 65,536 × 4 B = 524,288 B |
| fft | `-p1 -m14 -l4` | 520 KB per phase, 780 KB whole run | **2.03** per phase, 3.05 total | per array 2×(16,384 + 128×2)×8 = 266,240 B ≈ 260 KB; ×2 = 520 KB, ×3 = 780 KB |

`M` must be even, so fft cannot land on exactly 2x: `-m12` is 0.8x (fits) and `-m14` is the nearest
above. Pick `-m14` and say "2x per phase, 3x overall" in any table. For the `-l4` vs `-l6` pair use
**`-m14` or `-m16`** — below that they are the same program (F2).

## Host smoke test (TASK §6, optional)

| kernel | `qemu-riscv64` available? | command | result |
|---|---|---|---|
| radix | no (`which qemu-riscv64` finds nothing) | — | skipped, not installed (TASK says do not install) |
| fft | no | — | skipped; the real check is the board, in task 015 |

## Unpadded `fft` variant (TASK §5.4)

Not authorised — not built.

- Authorised by: n/a
- Diff applied: none
- Binary: none
- Stock `staged/fft` left untouched: yes

One note for whoever asks for it later: F2 means the unpadded variant must force `pad_length = 0`
**after** the page-rounding step at `fft.C:212-224`, not just in the line-size step, or the rounding
will put the padding straight back.

## Build log paths

| what | path |
|---|---|
| build recipe run | none — see F3 (direct recipe, no log file; build 2026-10-07 15:33) |
| host pad_length calculator | scratchpad `fftpad.c` (session-local, not kept in the repo) |

## Verdict

**Done.** `staged/radix` and `staged/fft` exist, are static, executable, and byte-identical to the
2026-10-07 build. Footprint formulas confirmed against source; radix matches TASK exactly, fft does
**not** (F1 three arrays, F2 page-rounded padding). **Left for task 015:** (1) its S6 text says
`-fft-m 16` is "2 MB"; it is 3 MB (12x L2). (2) `-fft-l 4/6` only differs for `-m >= 14`. (3) the harness
still needs `wl_radix_*` / `wl_fft_*` (015 §P2). Nothing committed; nothing in `staged/` was overwritten.
