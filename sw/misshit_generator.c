#include <stdio.h>
#include <stdint.h>
#define L2MISS (1024*8*64/32) //(number of set x number of ways x block size) / 32 (size of int) /// to have L2 misses
#define L1_WAYS 4  // 4-way set associative L1 cache
#define L2_WAYS 8  // 8-way set associative L2 cache
#define CACHE_LINE_SIZE 64  // Assume 64-byte cache lines
#define L2_SETS 1024  // Number of sets in 512KB L2 cache
#define L2_STRIDE (CACHE_LINE_SIZE * L2_SETS)  // Stride to access the same L2 set
#define NUM_L2_ACCESSES 256  // Control number of accesses
#define HIT_SET 68  // Desired L2 set index for hits
#define MISS_SET 955 // Desired L2 set index for misses
#define SET_OFFSET 16 ////( L2_WAYS (8) * block size(64))/32 (size of int)


static uint64_t read_cycles() {
    uint64_t cycles;
    asm volatile ("rdcycle %0" : "=r" (cycles));
    return cycles;
}

int* array = (int *) 0x81000000;
int acc = 0;


int main() {
int i = 0;


//warm up the cache
for (int i = 0; i < NUM_L2_ACCESSES; i++) {
     int hit_index = ((i % L2_WAYS) * L2MISS) + HIT_SET*SET_OFFSET;  // Ensure we stay within L2 ways. hit in set = HIT_SET
     acc += array[hit_index];


     int miss_index = (i * L2MISS)+MISS_SET*SET_OFFSET;   // Exceeding L2 ways
     acc += array[miss_index];
}

asm("# loop begin");

///L2 accesses
int internal_acc = 0;
for (int i = 0; i < NUM_L2_ACCESSES*32; i++) {
     int hit_index = ((i % L2_WAYS) * L2MISS) + HIT_SET*SET_OFFSET;  // Ensure we stay within L2 ways.  hit in set = HIT_SET
     internal_acc += array[hit_index];
     int miss_index = (i * L2MISS)+MISS_SET*SET_OFFSET;   // Exceeding L2 ways
     internal_acc += array[miss_index];


     long unsigned Hit_addr = (long unsigned)&array[hit_index];
     int cache_line_hit = (Hit_addr >> 6);
     int L1_index_hit = cache_line_hit & 0x03f;  // 0000.0011.1111
     int L2_index_hit = cache_line_hit & 0x3ff;  // 0011.1111.1111


     long unsigned Miss_addr = (long unsigned)&array[miss_index];
     int cache_line_miss = (Miss_addr >> 6);
     int L1_index_miss = cache_line_miss & 0x03f;  // 0000.0011.1111
     int L2_index_miss = cache_line_miss & 0x3ff;  // 0011.1111.1111




    printf("iteration: %d => hit_addr = 0x%lx, cache_line2 = %d, L1_index2 = %d L2_index2 = %d\n", i, Hit_addr, cache_line_hit, L1_index_hit, L2_index_hit);


    printf("iteration: %d => Miss_addr = 0x%lx, cache_line1 = %d, L1_index1 = %d L2_index1 = %d\n", i, Miss_addr, cache_line_miss, L1_index_miss, L2_index_miss);
}

asm("# loop end");

printf("Accumulated value: %d\n", internal_acc);
printf("Cycles: %llu\n", read_cycles());
return 0;
}