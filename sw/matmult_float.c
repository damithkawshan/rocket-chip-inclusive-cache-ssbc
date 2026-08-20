/* matmult-float benchmark adapted for RISC-V baremetal/verilator
 * 
 * Based on BEEBS matmult benchmark
 * Copyright (C) 2014 Embecosm Limited and University of Bristol
 * 
 * This is a standalone version that can be compiled directly for
 * RISC-V baremetal execution in Verilator simulation.
 */

#include <stdio.h>
#include <math.h>
#include <stdint.h>

/* Matrix dimensions */
#define UPPERLIMIT 10
#define MOD_SIZE 255

typedef float matrix[UPPERLIMIT][UPPERLIMIT];

#define RANDOM_VALUE ((float) RandomInteger() / 10.0f)
#define ZERO 0.0f

/* Global state */
static int Seed;
static matrix ArrayA, ArrayB, ResultArray;

/* Function prototypes */
static void InitSeed(void);
static int RandomInteger(void);
static void Initialize(void);
static void Multiply(matrix A, matrix B, matrix Res);
static int verify_benchmark(void);

/*
 * Initializes the seed used in the random number generator.
 */
static void InitSeed(void)
{
    Seed = 0;
}

/*
 * Generates random integers between 0 and 254
 */
static int RandomInteger(void)
{
    Seed = ((Seed * 133) + 81) % MOD_SIZE;
    return Seed;
}

/*
 * Initialize matrices with pseudo-random values
 */
static void Initialize(void)
{
    int OuterIndex, InnerIndex;

    InitSeed();
    
    for (OuterIndex = 0; OuterIndex < UPPERLIMIT; OuterIndex++)
        for (InnerIndex = 0; InnerIndex < UPPERLIMIT; InnerIndex++)
            ArrayA[OuterIndex][InnerIndex] = RANDOM_VALUE;
    
    for (OuterIndex = 0; OuterIndex < UPPERLIMIT; OuterIndex++)
        for (InnerIndex = 0; InnerIndex < UPPERLIMIT; InnerIndex++)
            ArrayB[OuterIndex][InnerIndex] = RANDOM_VALUE;
}

/*
 * Multiplies arrays A and B and stores the result in Res.
 */
static void Multiply(matrix A, matrix B, matrix Res)
{
    int Outer, Inner, Index;

    for (Outer = 0; Outer < UPPERLIMIT; Outer++) {
        for (Inner = 0; Inner < UPPERLIMIT; Inner++) {
            Res[Outer][Inner] = ZERO;
            for (Index = 0; Index < UPPERLIMIT; Index++) {
                Res[Outer][Inner] += A[Outer][Index] * B[Index][Inner];
            }
        }
    }
}

/*
 * Compare floating point values with tolerance
 */
static int values_match(float v1, float v2)
{
    if (v1 != v2) {
        float diff = v1 > v2 ? (v1 - v2) : (v2 - v1);
        float abs_v1 = v1 > 0 ? v1 : -v1;
        /* Allow 0.01% relative error */
        if (diff > abs_v1 * 0.0001f && diff > 0.001f) {
            return 0;
        }
    }
    return 1;
}

/*
 * Verify the benchmark result against expected values
 */
static int verify_benchmark(void)
{
    int i, j;
    
    /* Expected results for 10x10 matrix multiplication */
    static const matrix exp = {
        {949.950073f, 860.760010f, 1184.940186f, 971.279968f, 1180.799927f, 887.309937f, 1281.239990f, 613.529968f, 1144.799927f, 612.809998f},
        {989.550049f, 758.339966f, 1288.259888f, 760.320007f, 832.500000f, 739.890015f, 1381.860107f, 969.119995f, 1147.049927f, 698.940002f},
        {809.099976f, 644.309937f, 1241.190063f, 959.130005f, 677.700012f, 763.109924f, 1251.989990f, 551.880005f, 904.950012f, 731.609924f},
        {1216.799927f, 916.290039f, 1371.059937f, 563.669983f, 1106.999878f, 603.090027f, 1277.910034f, 835.469971f, 850.049927f, 784.890015f},
        {668.700012f, 477.360016f, 1100.339966f, 732.330017f, 762.299988f, 647.910034f, 841.139893f, 408.329987f, 931.049988f, 562.409973f},
        {828.000000f, 751.139954f, 1537.109985f, 715.320007f, 949.950012f, 1004.940063f, 1425.960083f, 1079.369873f, 1296.000000f, 792.989990f},
        {1077.299927f, 540.809998f, 852.840027f, 667.979980f, 844.649963f, 733.859924f, 985.139954f, 775.979980f, 903.150024f, 596.609985f},
        {822.599976f, 631.439941f, 1133.010010f, 238.769989f, 667.799988f, 567.989990f, 1093.859985f, 884.070007f, 552.599976f, 630.539978f},
        {949.950073f, 860.760010f, 1184.940186f, 971.279968f, 1180.799927f, 887.309937f, 1281.239990f, 613.529968f, 1144.799927f, 612.809998f},
        {989.550049f, 758.339966f, 1288.259888f, 760.320007f, 832.500000f, 739.890015f, 1381.860107f, 969.119995f, 1147.049927f, 698.940002f}
    };

    for (i = 0; i < UPPERLIMIT; i++) {
        for (j = 0; j < UPPERLIMIT; j++) {
            if (!values_match(ResultArray[i][j], exp[i][j])) {
                printf("MISMATCH at [%d][%d]: got %d, expected %d\n", 
                       i, j, (int)ResultArray[i][j], (int)exp[i][j]);
                return 0;
            }
        }
    }
    return 1;
}

int main(void)
{
    int iterations;
    int num_iterations = 10;  /* Number of times to run the benchmark */
    
    printf("\n");
    printf("==========================================\n");
    printf("  Matrix Multiply Float Benchmark\n");
    printf("==========================================\n");
    printf("Matrix size: %dx%d\n", UPPERLIMIT, UPPERLIMIT);
    printf("Iterations: %d\n", num_iterations);
    printf("\n");
    
    /* Initialize matrices */
    printf("Initializing matrices...\n");
    Initialize();
    
    /* Run the benchmark multiple times */
    printf("Running benchmark...\n");
    for (iterations = 0; iterations < num_iterations; iterations++) {
        Multiply(ArrayA, ArrayB, ResultArray);
    }
    
    /* Verify result */
    printf("Verifying result...\n");
    if (verify_benchmark()) {
        printf("\n==========================================\n");
        printf("PASS: Matrix multiply verification passed\n");
        printf("==========================================\n");
        return 0;
    } else {
        printf("\n==========================================\n");
        printf("FAIL: Matrix multiply verification failed\n");
        printf("==========================================\n");
        return 1;
    }
}
