# Coder task 014 — build SPLASH-2 `radix` and `fft` for the board

**Opened 2026-10-07.** Host-side only. **No board, no FPGA, no Vivado.** This task can run at any
time, in parallel with task 015, because it never touches the VCU118.

Authorised by the user 2026-10-07: *"include the tier 2 build instructions in seperate .md file."*

**This file is written for a coding model.** The build recipe below was executed and verified on
2026-10-07 before this file was written — both binaries compiled and linked statically. You are
re-running a known-good recipe and then extending it, not discovering one.

---

## 0. Rules — read before anything else

1. **Plan first.** Read this whole file, then reply with a plan of at most 10 lines plus any
   questions. Build nothing until the user says go. Simple English, point form.
2. **A failing check is a finding.** Write it in `REPORT.md`, stop, ask. Never patch a benchmark's
   source to make a build pass unless this file explicitly tells you to (only §5 does).
3. **Do not touch** `lu_ncb`, `lu_cb`, `barnes`, `streamcluster`, `bodytrack`, `blackscholes`,
   `swaptions`, or `canneal` — built, staged, and in use by the live campaign. Do not re-run
   `parsecmgmt` against them. Do not run `parsecmgmt -a fullclean` on anything.
4. **Never delete or overwrite anything in** `software/parsec-benchmark/staged/`. You only *add*
   files there.
5. `software/parsec-benchmark/` is **untracked** in git (`?? software/parsec-benchmark/`). Nothing
   you build here shows up in `git status` as a tracked change. Do not `git add` it.
6. **Binaries must be statically linked.** The board rootfs is a Buildroot/busybox image; do not
   assume a dynamic loader is usable. Every staged binary today is static. Check with `file`.
7. **Report sizes and `file` output for every binary you produce.** Cite paths, never paste build
   logs — they go in `software/parsec-benchmark/log/`.

---

## 1. Goal

Add two SPLASH-2 kernels to the staged set so task 015 can use them as co-runners:

| kernel | why it is wanted |
|---|---|
| **`radix`** | Integer radix sort. The permutation phase scatter-writes across the whole key array, so it is high-miss **and** write-heavy — closest thing available to LU's `memAcc/accessA` of 0.949. Today's only working co-runner, `streamcluster`, streams reads at 0.54. |
| **`fft`** | 1D FFT with an all-to-all transpose. Row stride is a tunable near-power-of-two, which is what concentrates pressure on a subset of the 512 sets — the exact condition SBC's set balancing targets. |

Both are already present as source with full build configs. Neither has ever been built for
RISC-V in this tree.

---

## 2. Why the obvious command is not enough

The project builds PARSEC packages with:

```bash
cd $CY/software/parsec-benchmark
source env.sh
bin/parsecmgmt -a build -c gcc-riscv64 -p splash2.lu_ncb
```

`config/gcc-riscv64.bldconf:43` sets `LDFLAGS="-static"`, **but the SPLASH-2 makefiles hard-assign
`LDFLAGS = -lpthread -lm` and so discard it.** Confirmed in
`ext/splash2/kernels/fft/src/makefile` and `ext/splash2/kernels/lu_ncb/src/makefile` — both read:

```make
LDFLAGS = -lpthread -lm
$(TARGET): $(OBJS)
	$(CC) $(OBJS) $(CFLAGS) -o $(TARGET) $(LDFLAGS)
```

The consequence is visible in the tree right now:

| file | linkage |
|---|---|
| `ext/splash2/kernels/lu_ncb/inst/amd64-linux.gcc-riscv64/bin/lu_ncb` | **dynamically** linked ← what `parsecmgmt` produced |
| `ext/splash2/kernels/lu_ncb/obj/amd64-linux.gcc-riscv64/lu_ncb` | statically linked |
| `staged/lu_ncb` | statically linked ← what the board actually runs |

So `parsecmgmt` alone gives you an unusable binary. Use the direct recipe in §3, which passes
`-static` on the link line explicitly.

---

## 3. The verified recipe

Run from the repo root. `$GCC` and `$CF` are exactly what the 2026-10-01 `lu_ncb` build log used
(`software/parsec-benchmark/log/amd64-linux.gcc-riscv64/build_2026-10-01_00:06:28.log`).

```bash
CY=/home/damith/Research/repos/chipyard_performance_eval/chipyard
GCC=$CY/.conda-env/riscv-tools/bin/riscv64-unknown-linux-gnu-gcc
CF="-O3 -g -funroll-loops -march=rv64gc -mabi=lp64d"
```

### 3.1 radix

```bash
cd $CY/software/parsec-benchmark/ext/splash2/kernels/radix/src
m4 ../../../null_macros/c.m4.null.pthread radix.C > radix.c
$GCC -c $CF radix.c
$GCC radix.o $CF -static -o radix -lpthread -lm
file -b radix        # must say: statically linked
```

### 3.2 fft

```bash
cd $CY/software/parsec-benchmark/ext/splash2/kernels/fft/src
m4 ../../../null_macros/c.m4.null.pthread fft.C > fft.c
$GCC -c $CF fft.c
$GCC fft.o $CF -static -o fft -lpthread -lm
file -b fft          # must say: statically linked
```

### 3.3 What is already done

**Both of the above were run on 2026-10-07 and both succeeded.** The binaries exist:

- `ext/splash2/kernels/fft/src/fft` — statically linked, builds with warnings only
- `ext/splash2/kernels/radix/src/radix` — 863,840 bytes, statically linked, 2 warnings

The warnings are all K&R-style `return type defaults to 'int'` from 1990s SPLASH-2 source
(`FFT1DOnce`, `PrintArray`, and two in radix). **They are expected. Do not "fix" them.**

Your first action is to confirm those two files are still present and still static. If they are,
§3.1/§3.2 are done — record it and move to §4. If they are missing, run the recipe.

> The `m4` macro path is `../../../null_macros/...` (three levels) when building in `src/`. The
> makefile switches to four levels only under `version=IN_PARSEC`. Using the wrong depth gives a
> confusing `m4: cannot open` rather than a compile error.

---

## 4. Stage to the board directory

The staging dir is the only thing `run_pair_session.exp` reads from
(`lib/config.exp`: `STAGED = $CY/software/parsec-benchmark/staged`).

```bash
cd $CY/software/parsec-benchmark
cp ext/splash2/kernels/radix/src/radix staged/radix
cp ext/splash2/kernels/fft/src/fft     staged/fft
chmod +x staged/radix staged/fft
ls -la staged/radix staged/fft
```

Neither kernel reads an input file — both generate their data from arguments — so **no input
files need staging.** This is a real advantage over `canneal` (netlist loader dominated its
window) and matches `streamcluster` with `n > 0`.

### 4.1 Gate

Report in `REPORT.md`:

| binary | bytes | `file -b` says | sha256 (first 12) |
|---|---:|---|---|
| `staged/radix` | | | |
| `staged/fft` | | | |

Both must say `statically linked`. If either says `dynamically linked`, the `-static` did not
take — **stop and ask.**

---

## 5. Size knobs — read before proposing configurations

Task 015 will choose the configurations. Your job here is to report the footprint arithmetic so
that choice is made on numbers, not guesses. The L2 under test is **256 KB, 8-way, 512 sets,
64 B lines**.

### 5.1 radix

Defaults are `radix.C:42-45`: `-p1 -n262144 -r1024 -m524288`.

Allocation is `radix.C:213-214` — two key arrays of `num_keys * sizeof(int)`:

```
footprint ≈ 2 x num_keys x 4 B     (+ radix histograms, small)
```

| `-n` | footprint | vs 256 KB L2 |
|---:|---:|---|
| 16384 | 128 KB | fits |
| 32768 | 256 KB | **exactly L2** |
| 65536 | 512 KB | 2x |
| 131072 | 1 MB | 4x |
| 262144 (default) | 2 MB | 8x |

`-r` (radix) sets histogram width, `-m` the max key value. Keep `-r1024 -m524288` fixed and sweep
`-n` only, so footprint is the single variable.

**Thread count must be 1** (`-p1`) — the board gives each workload one core, and `-p` must be a
power of 2.

### 5.2 fft

Defaults are `fft.C:50-53`: `M = 10`, `P = 1`, `LOG2_LINE_SIZE = 4`.

`N = 2^M` complex doubles at 16 B each, in two arrays (`fft.C:188`), and **M must be even**
(`fft.C:144` errors out otherwise):

```
footprint ≈ 2 x 2^M x 16 B
```

| `-m` | footprint | vs 256 KB L2 |
|---:|---:|---|
| 10 (default) | 32 KB | fits |
| 12 | 128 KB | fits |
| 14 | 512 KB | 2x |
| 16 | 2 MB | 8x |
| 18 | 8 MB | 32x |

### 5.3 The `-l` knob, and an honest note about it

`fft.C:196-215` computes a row padding from the claimed cache-line size:

```c
line_size = 1 << log2_line_size;
if (line_size <= 2*sizeof(double)) { pad_length = 1; }
else { pad_length = line_size / (2*sizeof(double)); }
```

At `-m16` (rootN = 256 rows) the two interesting settings are:

| flag | `pad_length` | row stride | note |
|---|---:|---:|---|
| `-l4` (default, 16 B) | 1 element | 4112 B | 64.25 lines — non-power-of-two |
| `-l6` (64 B, our real line) | 4 elements | 4160 B | 65 lines exactly |

**Be careful how this is described.** SPLASH-2 pads *deliberately*, to avoid cache conflicts, and
the minimum `pad_length` the stock code can produce is 1 — never 0. So **neither** setting gives
pathological power-of-two aliasing; both strides are coprime-ish with 512 sets and spread
reasonably. The `-l` knob is a modest stride change, not an aliasing on/off switch. Do not report
it as one.

### 5.4 Optional: the unpadded variant (source modification — ask first)

To get genuine power-of-two aliasing you must force `pad_length = 0`, which the stock code cannot
emit. With `pad_length = 0` at `-m16`, the row stride becomes exactly 4096 B = 64 lines, so 256
rows land on only 8 distinct set groups — roughly 8x pressure on 1/8 of the cache. That is the
same shape as LU at `b=128` (which puts ~2048 lines on ~128 of 512 sets) and is the sharpest
available test of set balancing.

This is a **source modification**, so:

- **Do not do it unless the user says go.** Report the plan and wait.
- If authorised: build it as a **separate binary named `fft_noalias`**, leave stock `fft`
  untouched, and record the exact diff in `REPORT.md`.
- A `#ifdef` guarded by `-DSBC_NO_PAD` is preferred over editing the expression in place.

---

## 6. Smoke test on the host (optional but recommended)

You cannot run a RISC-V static binary natively. If `qemu-riscv64` is available, a 10-second check
that the argument parsing works and the program terminates is worth it:

```bash
qemu-riscv64 staged/radix -p1 -n16384 -r1024 -m524288
qemu-riscv64 staged/fft   -p1 -m12
```

If `qemu-riscv64` is not installed, **do not install it** — skip this and say so in the report.
The real check is on the board, in task 015.

---

## 7. Do NOT wire these into the harness

`run_pair_session.exp` needs `wl_radix_stage` / `wl_radix_cmd` procs and an entry in `wl_known`
before it can run them. **That edit belongs to task 015, not here** (015 §P2 does it together with
two other harness fixes, so they land as one reviewed change). Building and staging is the whole
of task 014.

---

## 8. Deliverables

1. `staged/radix` and `staged/fft`, both static, both `chmod +x`.
2. The §4.1 gate table filled in.
3. The footprint tables from §5.1/§5.2 confirmed against the source as you actually find it —
   if a line number or formula in this file does not match, **say so**; the source is the truth
   and this file may have drifted.
4. A one-line recommendation for a starting configuration of each kernel that lands near 2x the
   L2, with the arithmetic shown.
5. `REPORT.md` filled in.

Nothing in this task is irreversible. If a build goes wrong, `make clean` in that kernel's `src/`
directory removes `*.c *.h *.o` and the target, and nothing else in the tree depends on it.
