/* sbc_guest_cap_test.c — task 007 C4, T-CAP.
 *
 * With guestCap = GUEST_CAP, the one pinned source (5 -> 6) must never have more than GUEST_CAP lines
 * parked, AND must actually reach the cap - a run that never gets there proves nothing. Under
 * sbcForceDstSet only one pairing exists at a time, so SBC_Parked is that source's guest count.
 * Every load is also checked against its clean golden value.
 *
 * Proof the cap refused migrations (not just that the count stayed low): grep the run's .out for
 * "MIG-DECLINE ... reason=cap" / "atCap=1".
 *
 * Config: VerilatorRocket8KL116KL2GuestCap1Config (pinning 5<->6, guestCap = 1).
 */
#include "sip_common.h"

#define GUEST_CAP  1      /* must equal guestCap in the config */
#define NTAG       64     /* >> ways: the source keeps missing, stays hot, keeps trying to migrate */
#define LOOPS      2500
#define TAG0       200

typedef unsigned long ulong;

int main(void) {
    printf("==== guest cap test (guestCap=%d, pairing pinned %d->%d) ====\n", GUEST_CAP, HOT_SET, PARTNER);
    if (!(sbc_rd(SBC_STATUS) & 1)) { printf("SKIP: SBC is not built into this config\n"); return 0; }
    sbc_wr(SBC_MIGRATEENABLE, 1);

    uint64_t g[NTAG];
    cap(HOT_SET, TAG0, NTAG, g);   /* clean golden values; plain loads keep the lines migratable */

    snap_t a, b; snap(&a);
    uint64_t maxpk = 0, over = 0, atcap = 0;
    int bad = 0;
    for (int loop = 0; loop < LOOPS; loop++) {
        /* top up a few clean native ways in the partner so a migration always has somewhere to land */
        if ((loop % 6) == 0)
            for (int k = 0; k < 3; k++) sink += do_ld(addr(PARTNER, 50000 + (loop & 0x1f) * 3 + k));
        for (int i = 0; i < 8; i++) {
            int t = (loop * 5 + i * 3) % NTAG;
            if (do_ld(addr(HOT_SET, TAG0 + t)) != g[t]) bad++;
        }
        uint64_t pk = sbc_rd(SBC_PARKED);
        if (pk > maxpk) maxpk = pk;
        if (pk > (uint64_t)GUEST_CAP) over++;
        if (pk == (uint64_t)GUEST_CAP) atcap++;
    }
    snap(&b);

    uint64_t dmig = b.mig - a.mig, dabo = b.abo - a.abo;
    int pass = over == 0 && maxpk == (uint64_t)GUEST_CAP && bad == 0 && dmig > (uint64_t)GUEST_CAP;
    printf("T-CAP guests-per-pairing: %s  cap=%d maxParked=%lu overCapPolls=%lu atCapPolls=%lu/%d "
           "migrations+=%lu aborted+=%lu badReads=%d\n",
           pass ? "PASS" : "FAIL", GUEST_CAP, (ulong)maxpk, (ulong)over, (ulong)atcap, LOOPS,
           (ulong)dmig, (ulong)dabo, bad);
    printf(pass ? "PASS: guest cap holds\n" : "FAIL: guest cap\n");
    return pass ? 0 : 1;
}
