/* matmult-float cache-stress benchmark for RISC-V baremetal/verilator
 *
 * This version is intentionally not cache-friendly:
 * - MATRIX_DIM is large enough that the working set is meaningful
 * - the naive i,j,k loop order walks B[k][j] down a column
 * - in row-major C storage that access has a large stride
 *
 * The code stays simple on purpose: static arrays, basic loops, no dynamic
 * allocation, no intrinsics, no advanced runtime dependencies.
 */

#include <stdio.h>
#include <stdint.h>
#include "sbc_mmio.h"

#define MATRIX_DIM 32
#define MOD_SIZE 255
#define ITERATIONS 1

typedef float matrix_t[MATRIX_DIM][MATRIX_DIM];

static int Seed;
static matrix_t ArrayA;
static matrix_t ArrayB;
static matrix_t ResultArray;

static void InitSeed(void);
static int RandomInteger(void);
static void Initialize(void);
static void MultiplyCacheStress(matrix_t A, matrix_t B, matrix_t Res);
static uint32_t MatrixChecksum(matrix_t M);

static void InitSeed(void)
{
    Seed = 0;
}

static int RandomInteger(void)
{
    Seed = ((Seed * 133) + 81) % MOD_SIZE;
    return Seed;
}

static void Initialize(void)
{
    int i;
    int j;

    InitSeed();

    for (i = 0; i < MATRIX_DIM; i++) {
        for (j = 0; j < MATRIX_DIM; j++) {
            ArrayA[i][j] = (float)RandomInteger() / 10.0f;
        }
    }

    for (i = 0; i < MATRIX_DIM; i++) {
        for (j = 0; j < MATRIX_DIM; j++) {
            ArrayB[i][j] = (float)RandomInteger() / 10.0f;
            ResultArray[i][j] = 0.0f;
        }
    }
}

static void MultiplyCacheStress(matrix_t A, matrix_t B, matrix_t Res)
{
    int i;
    int j;
    int k;

    for (i = 0; i < MATRIX_DIM; i++) {
        for (j = 0; j < MATRIX_DIM; j++) {
            float sum;

            sum = 0.0f;
            for (k = 0; k < MATRIX_DIM; k++) {
                sum += A[i][k] * B[k][j];
            }
            Res[i][j] = sum;
        }
    }
}

static uint32_t MatrixChecksum(matrix_t M)
{
    int i;
    int j;
    uint32_t hash;

    hash = 2166136261u;

    for (i = 0; i < MATRIX_DIM; i++) {
        for (j = 0; j < MATRIX_DIM; j++) {
            union {
                float f;
                uint32_t u;
            } bits;

            bits.f = M[i][j];
            hash ^= bits.u;
            hash *= 16777619u;
        }
    }

    return hash;
}

int main(void)
{
    int iter;
    uint32_t checksum;

    printf("\n");
    printf("==========================================\n");
    printf("  Matrix Multiply Float Cache Stress\n");
    printf("==========================================\n");
    printf("Matrix size: %dx%d\n", MATRIX_DIM, MATRIX_DIM);
    printf("Iterations: %d\n", ITERATIONS);
    printf("Row bytes: %d\n", (int)(MATRIX_DIM * (int)sizeof(float)));
    printf("Access pattern: naive i-j-k, column walk on B\n");
    printf("\n");

    printf("Initializing matrices...\n");
    Initialize();

    printf("Running benchmark...\n");
    for (iter = 0; iter < ITERATIONS; iter++) {
        printf("Iteration %d\n", iter);
        MultiplyCacheStress(ArrayA, ArrayB, ResultArray);
    }

    checksum = MatrixChecksum(ResultArray);

    printf("Checksum: 0x%08x\n", checksum);
    /* Amendment 11 §11.3: the full SBC counter line, next to the checksum. Read at the very end,
     * after the checksum is computed, so it cannot perturb the data or the checksum. */
    printf("[SBC-COUNTERS] mig=%lu att=%lu abo=%lu secHits=%lu secMiss=%lu secWrite=%lu secProbe=%lu "
           "dispRel=%lu dispDrop=%lu secC=%lu secPerm=%lu homeBranch=%lu parked=%lu\n",
           (unsigned long)sbc_rd(SBC_MIGRATIONS), (unsigned long)sbc_rd(SBC_ATTEMPTED),
           (unsigned long)sbc_rd(SBC_ABORTED),    (unsigned long)sbc_rd(SBC_SECHITS),
           (unsigned long)sbc_rd(SBC_SECMISS),    (unsigned long)sbc_rd(SBC_SECWRITE),
           (unsigned long)sbc_rd(SBC_SECPROBE),   (unsigned long)sbc_rd(SBC_DISPRELEASE),
           (unsigned long)sbc_rd(SBC_DISPDROP),   (unsigned long)sbc_rd(SBC_SECC),
           (unsigned long)sbc_rd(SBC_SECPERM),    (unsigned long)sbc_rd(SBC_HOMEBRANCH),
           (unsigned long)sbc_rd(SBC_PARKED));
    printf("DONE\n");

    return 0;
}
