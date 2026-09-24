#!/usr/bin/env bash
# check_v4_layout.sh <elf> - every hot object of dirty_guest_evict_test must sit in L2 sets 0..4.
# L2 set = (addr >> 6) & 7. A hot line in set 5 or 6 (HOT / PARTNER) or 7 re-creates the problem the
# quiet-program design exists to remove, so this fails BEFORE a slow simulator build is spent on it.
set -uo pipefail
ELF="${1:?usage: $0 <elf>}"
NM="${RISCV_NM:-riscv64-unknown-elf-nm}"
rc=0
for sym in run_on_stack body clear_dst fill_dst trigger dirty_round readback Q qstack; do
  line=$($NM -S "$ELF" | awk -v s="$sym" '$4==s || index($4, s".")==1 {print $1, $2}' | head -1)
  [ -z "$line" ] && { echo "LAYOUT: $sym missing"; rc=1; continue; }
  set -- $line
  addr=$((16#$1)); size=$((16#$2))
  [ "$sym" = qstack ] && size=256          # only the low 256 B are used
  bad=""
  for ((a = addr & ~63; a < addr + size; a += 64)); do
    s=$(( (a >> 6) & 7 )); [ $s -gt 4 ] && bad="$bad $s"
  done
  if [ -n "$bad" ]; then echo "LAYOUT BAD: $sym addr=0x$1 size=$size touches sets:$bad"; rc=1
  else echo "layout ok : $sym addr=0x$1 size=$size sets $(( (addr>>6)&7 ))..$(( ((addr+size-1)>>6)&7 ))"; fi
done
exit $rc
