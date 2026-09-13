# Adaptive Line Placement with the Set Balancing Cache

**Dyer Rolán, Basilio B. Fraguela, Ramón Doallo**
Depto. de Electrónica e Sistemas
Universidade da Coruña
A Coruña, Spain
{drolan, basilio, doallo}@udc.es

## ABSTRACT
Efficient memory hierarchy design is critical due to the in-creasing gap between the speed of the processors and the memory. One of the sources of inefficiency in current caches is the non-uniform distribution of the memory accesses on the cache sets. Its consequence is that while some cache sets may have working sets that are far from fitting in them, other sets may be underutilized because their working set has fewer lines than the set. In this paper we present a technique that aims to balance the pressure on the cache sets by detecting when it may be beneficial to associate sets, displacing lines from stressed sets to underutilized ones. This new technique, called Set Balancing Cache or SBC, achieved an average reduction of 13% in the miss rate of ten benchmarks from the SPEC CPU2006 suite, resulting in an average IPC improvement of 5%.

**Categories and Subject Descriptors:** B.3.2 [Memory Structures : Design Styles cache memories]
**General Terms:** Design, Performance
**Keywords:** cache, performance, adaptivity, balancing

## 1. INTRODUCTION
Memory references are often not uniformly distributed across the sets of a set-associative cache, the most common design nowadays [14]. As a result, at a given point during the execution of a program there are usually sets whose working set is larger than their number of lines (the associativity of the cache), while the situation in other sets is exactly the opposite. The outcome of this is that some sets exhibit large local miss ratios because they do not have the number of lines they need [9], while other sets achieve good local miss ratios at the expense of a poor usage of their lines, because some or many of them are actually not needed to keep the working set. An intuitive answer to this problem is to increase the associativity of the cache. Multiplying by n the associativity is equivalent to merging n sets in a single one, joining not only all their lines, but also their corresponding working sets. This allows to balance smaller working sets with larger ones, making available previous underutilized lines for the latter, which results in smaller miss rates.

Unfortunately, increments in associativity impact negatively access latency and power consumption (e.g. more tags have to be read and compared in each access) as well as cache area, besides increasing the cost and complexity of the replacement algorithm. Worse, progressive increments in the associativity provide diminishing returns in miss rate reduction, as in general, the larger (and fewer) the sets are, the more similar or balanced their working sets tend to be. This way, only restricted levels of associativity are found in current caches.

In this paper we propose an approach to associate cache sets whose working set does not seem to fit in them with sets whose working set fits, enabling the former to make use of the underutilized lines of the latter. Namely, this cache design, which we call Set Balancing Cache or SBC, shifts lines from sets with high local miss rates to sets with underutilized lines where they can be found later. Notice that while an increase in associativity equates to merging sets in an indiscriminate way, our approach only exploits jointly the resources of several sets when it seems to be beneficial. Also, increases in associativity cannot choose which sets to merge, while the SBC can be implemented using either a static policy, which also preestablishes which sets can be associated, or a dynamic one that allows to associate a set with any other one. Thus, as we will see in the evaluation, the SBC achieves better performance than equivalent increases in associativity while not bringing their inconveniences.

The rest of this paper is organized as follows. Next section will describe the algorithm and structure of a static SBC, in which sets can only be associated with other sets depending on a preset condition on their index. Section 3 will introduce a dynamic SBC that allows to shift lines from a set that presents a bad behavior to the best set available (i.e. not yet associated) in the cache. Both SBC proposals will be evaluated using the environment described in Section 4, the results being discussed in Section 5. The cost of both approaches will be examined in Section 6. A deeper analysis of the cost and performance of the SBC is presented in Section 7. Related work will be discussed and compared in Section 8. The last section is devoted to the conclusions and future work.

## 2. STATIC SET BALANCING CACHE
We seek to reduce the pressure on the cache sets that are unable to hold all the lines in their working set, by displacing some of those lines to sets that seem to have underutilized lines. These latter sets are those whose working set fits well in them, giving place to small local miss rates. This idea requires in the first place a mechanism to measure the degree to which a cache set is able to hold its working set. We call this value the saturation level of the set and we measure it by means of a counter with saturating arithmetic that is modified each time the set is accessed. If the access results in a miss, the counter is incremented, otherwise it is decremented. We call this counter saturation counter.

The fact that different sets can experience very different levels of demand has already been discussed in the bibliography [12][14]. This fact, which is the base for our proposal, can be illustrated with the saturation counters. Figure 1 classifies the sets in a 8-way 2MB cache with lines of 64 bytes during the execution of the astar benchmark, from the SPEC CPU2006 suite. The classification is a function of their saturation level as measured by saturation counters whose maximum value is 15 in this case. The levels of saturation considered are low (the counter is between 0 and 5), medium (between 6 and 10) and high (between 11 and 15). We can see how after the initialization stage there are some sets that are little saturated, while others are very saturated. These sets of opposite kinds could be associated, moving lines from highly saturated sets to little saturated ones in order to balance their saturation level and avoid misses. This also gives place to make second searches, or in general up to n-th searches if n sets are associated, if a line is not found in the set indicated by the cache indexing function and this set is known to have shifted lines to other set(s). As a result, the operation of the Set Balancing Cache we propose involves, besides the saturation counters explained, an association algorithm, which decides which set(s) are to be associated in the displacements, a displacement algorithm which decides when to displace lines to an associated set, and finally, modifications to the standard cache search algorithm. We will now explain them in turn.

### 2.1 Association algorithm
This algorithm determines to which sets can displace lines a given one. Although the number of sets involved could be any, and it could change over time, we have started studying the simplest approach, in which each cache set is statically associated to another specific set in the cache. That is the reason why we call this first design of our proposal static SBC (SSBC). This design minimizes the additional hardware involved as well as the changes required in the search algorithm of the cache. We have decided the associated set to be the farthest set of the considered one in the cache, that is, the one whose index is obtained complementing the most significant bit of the index of the considered set. This decision is justified by the principle of spatial locality, as if a given set is highly saturated, it is probable its neighbors are in a similar situation. A consequence of this decision is that given to sets X and Y associated by this algorithm, sometimes lines will be displaced from X to Y, and vice versa, depending on the state of their saturation counters. Notice also that when the associativity of a cache design is multiplied by 2, this is equivalent to merging in a single set the same two sets that our policy associates, i.e., those that differ in the most significant bit of the index.

### 2.2 Displacement algorithm
A first issue to decide is when to perform displacements. In order to minimize the changes in the operation of the cache and take advantage of line evictions that take place in a natural way in the sets, we have chosen to perform the displacements when a line is evicted from a highly saturated set. Since the replacement algorithm we consider for the cache sets is LRU, as it is the most extended one, this means that the LRU line will not be sent to the lower level of the memory hierarchy; rather it will be actually displaced to another set.

It is intuitive that displacements should take place from sets with a high saturation level to sets little saturated. A concrete range for the saturation counter, from which value of the counter we consider that displacements should take place, and under which value we consider a set to be little saturated are the parameters to choose for this policy. We have observed experimentally that a good upper limit for a saturation counter in a cache with associativity K is 2K-1, thus the counters used in this paper work in the range 0 to 2K-1.

Regarding the triggering of the displacement of lines from a set, when its saturation counter has a value under its maximum it means that there have been hits in the set recently, thus it is possible its working set fits in it. Only when the counter adopts its maximum value will have most recent accesses (and particularly the most recent one) resulted in misses and it is safer to presume that the set is under pressure. Thus our SBC only tries to displace lines from sets whose saturation counter adopts its maximum value, a decision taken based on our experiments.

Finally, although it is the association algorithm responsibility to choose which is the set that receives the lines in a displacement, it is clear that displacing lines to such set if/when its saturation counter is high can be counterproductive, since that indicates the lack of underutilized lines. In fact we could end up saturating a set that was working fine when trying to solve the problem of excess of load on another set. Thus a second condition required to perform a displacement is that the saturation counter of the receiver is below a given limit we call displacement limit. We have determined experimentally that the associativity K of the cache is a good displacement limit for the counters in the range 0 to 2K-1 we have used. Notice that since displacements only take place as the result of line evictions, the access to the associated set saturation counter needed to verify this second condition can be made during the resolution of the miss that generates the eviction.

Concerning the local replacement algorithm of the set that receives the displaced line, the line is inserted as the most recently used one (MRU). The rationale is that since the displaced line comes from a stressed working set, while the working set of the destination set fits well in it, this line needs more priority than the lines already residing in the set. Besides this way n successive displacements from a set to another one insert n different lines in the destination set. If the displaced line were inserted as the least recently used one (LRU), each new displacement would evict the line inserted in the previous one. We have checked experimentally that the insertion in the MRU position yields better results than in the LRU one.

### 2.3 Search algorithm
In the SBC a set may hold both memory lines that correspond to it according to the standard mapping mechanism of the cache and lines that have been displaced from its associated set. Thus the unambiguous identification of a line in a set requires not only its tag, but also an additional displaced bit or d for short. This bit marks whether the line is native to the set, when it is 0, or it has been displaced from another set, when it is 1. Searches always begin examining the set associated by default to the line, testing for tag equality and d=0. If the line is not found there, a second search is performed in the associated set, this time seeking tag equality and d=1. If the second search is successful, a secondary hit is obtained.

Our proposal avoids unnecessary second searches by means of an additional second search (sc) bit per set that indicates whether its associated set may hold displaced lines. This bit is set when a displacement takes place. Its deactivation takes place when the associated set evicts a line, if the OR of its d bits changes from 1 to 0 as result of the eviction. Checking this condition and resetting the second search bit of the associated set is done in parallel with the resolution of the miss that generates the eviction. Without this strategy to avoid unnecessary second searches, the IPC for the static SBC (SSBC) would have been 0.6% and 1.0% smaller in the two level and the three level cache configurations used in our evaluation in Section 5, respectively.

### 2.4 Discussion
[Refer to the original PDF for Figures and detailed examples of SSBC operation.]

## 3. DYNAMIC SET BALANCING CACHE
The SSBC is very restrictive on associations. Each set only relies on another prefixed set as potential partner to help keep its working set in the cache. It could well happen that both sets were highly saturated while others are underutilized. When a cache set is very saturated, it would be better to have the freedom to associate it to the more underutilized (i.e. with the smallest saturation value) non-associated set in the cache. This is what the dynamic SBC (DSBC) proposes. We now explain in turn the algorithms of this cache.

### 3.1 Association algorithm
The DSBC triggers the association of sets when the saturation counter of a set that is not associated with another set reaches its maximum value, which is 2K-1 in our experiments, where K is the associativity of the cache. When this happens, the DSBC tries to associate it with the available set (i.e. not yet associated with another one) with the smallest saturation level. An additional restriction is that the association will only take place if this smallest saturation level found is smaller than the displacement limit, described in Section 2.2. The reason is that it makes no sense to consider as candidate for association a set whose saturation counter indicates that lines from other sets should not be displaced to it.

In principle this policy would require hardware to compare the saturation counters of all the available sets in order to identify the smallest one. Instead we propose a much simpler and cheaper design that yields almost the same results, which we call Destination Set Selector (DSS). The DSS has a small table that tries to keep the data related to the less saturated cache sets.

[Refer to original PDF for specific algorithm details regarding Destination Set Selector (DSS), Association Table (AT), etc.]

### 3.2 Displacement algorithm
Just as in the SSBC, displacements take place when lines are evicted from sets whose saturation counter has its maximum value. In the DSBC, sets are not associated by default to any other specific set, thus another condition for the displacements to take place is that the saturated set is associated to another set. Another important difference with respect to the SSBC is that displacements are unidirectional, that is, lines can only be displaced from the set that requested the association (the one whose counter reached its maximum value), which we call source set, to the one that was chosen by the Destination Set Selector to be associated to it, which we call destination set.

### 3.3 Search algorithm
Just as in the SSBC, there is a displaced bit d per line that indicates whether is has been displaced from another set. The cache always begins a search looking for a line with the desired tag and d=0 in the set with the index i specified by the memory address sought. Simultaneously the corresponding i-th entry in the Association Table, AT(i) is read.

### 3.4 Disassociation algorithm
The approach followed to break associations is very similar to the one used to avoid unnecessary second searches in the SSBC. A disassociation can take place upon a first search miss (i.e., a native miss) in a destination set i. If the OR of the d bits of this set changes from 1 to 0 as result of the eviction triggered by the miss, the association is broken.

## 4. SIMULATION ENVIRONMENT
To evaluate our approach we have used the SESC simulator with two baseline configurations based on a four-issue CPU clocked at 4GHz, with two and three on-chip cache levels respectively. Both configurations assume a 45 nm technology process and are detailed in Table 1 [see PDF].

### 4.1 Benchmarks
We use 10 representative benchmarks of the SPEC CPU 2006 suite, both from the INT and FP sets. They have been executed using the reference input set (ref), during 10 billion instructions after the initialization.

## 5. PERFORMANCE EVALUATION
The SBC has been applied, for both static and dynamic versions, in the second level for the two-level configuration and in the two lower levels for the three-level configuration. The dynamic SBC uses a Destination Set Selector (described in Section 3.1) with four entries based on our experiments.

[Detailed performance graphs, DSS efficiency results, and average access time improvements are provided in the source PDF figures and tables.]

## 6. COST
In this section we evaluate the cost of the SBC in terms of storage requirements, area and energy, which has been estimated using CACTI 5.3.
The SBC requires additional hardware because of the need of a saturation counter per set to monitor its behavior and additional bits in the directory to identify displaced lines (d bit). The SSBC and the DSBC only have an overhead of 0.31% and 0.58% respectively, compared to the baseline configuration. The energy consumption overhead on average per access calculated by CACTI is less than 1% for SBC and 79% for the baseline with double associativity.

## 7. ANALYSIS
[Evaluations on how the performance and cost of the SBC vary with respect to the parameters of the cache, and comparison against victim cache.]

## 8. RELATED WORK
[Overview of pseudo-associative caches, Adaptive group-associative cache (AGAC), Indirect Index Cache (IIC), NuRAPID cache, B-Cache, V-Way cache, Scavenger, and Dynamic Insertion Policy (DIP).]

## 9. CONCLUSIONS
We have presented the Set Balancing Cache (SBC), a new design aimed at non-first level caches with a good cost-benefit relation. This cache associates sets with a high demand with sets that have underutilized lines in order to balance the load among both kinds of sets and thus reduce the miss rate.

Experiments using 10 representative benchmarks of the SPEC CPU2006 suite achieved an average reduction of 9.2% and 12.8% of the miss rate for the static and the dynamic SBC, respectively, or 14% and 19% computed as the geometric mean.

This led to average IPC improvements between 2.7% and 5.25% depending on the type of SBC and the memory hierarchy tested. Furthermore, the SBC designs proved consistently to be better than increasing the associativity, both in term of area and performance.

## 10. ACKNOWLEDGMENTS
This work was supported by the Xunta de Galicia under projects INCITE08PXIB105161P and "Consolidación e Estructuración de Unidades de Investigación Competitivas" 3/2006 and the MICINN, cofunded by the Fondo Social Europeo, under the grant with reference TIN2007-67536-C03-02. The authors are also members of the HiPEAC network.

## 11. REFERENCES
[See original document for 19 detailed references cited.]