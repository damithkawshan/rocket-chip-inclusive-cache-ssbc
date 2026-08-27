Step 5 — what I did, in plain English
The goal: until now every migration aborted on purpose (the destination way was hard-stubbed as "no free slot"). Step 5 makes real migrations happen by actually looking up a free slot in the destination set.

Finding a home in the destination set

A migrating MSHR now does a second directory read — the first read looked at the source set (the hot line to move); the second looks at the destination set (the cold set) to find an empty way to drop it into.
I added a small "read request" lane from the MSHR to the scheduler (modeled exactly on the existing copy lane), and taught the scheduler to drive the directory's single read port with it and route the answer back to the right MSHR. While that read is happening, new incoming requests politely wait one cycle so they don't steal the port.
"Prefer-invalid" — making the answer trustworthy

The directory normally picks a random victim way. I added a preferInvalid flag: when the migration probes the destination set, the directory returns an empty (invalid) way if one exists, otherwise it falls back to random.
This turns the result into a clean yes/no test: if the returned way is empty → there's room, proceed; if it's not empty → the destination set is full, abort. Normal cache reads don't set this flag, so they behave exactly as before.
The decision (proceed vs abort)

Source not usable (dirty / shared / already moved) → abort immediately, before even doing the second read.
Destination full → abort after the second read.
Otherwise → proceed: copy the block, install the "displaced" tag at the free destination way, then invalidate the original location.
Three counters (so software can see what's happening)

Attempted — bumped the moment a migration is set up.
Aborted — bumped when a migration gives up (either reason above).
Migrations (committed) — bumped when a migration fully completes, via a commit{MIGRATE} signal. (The "write it into the Association Table" part of commit is still left for step 7 — right now commit only counts.)
These are read back at the MMIO offsets from step 1 (0x328 committed, 0x348 attempted, 0x350 aborted).
Fixed the abort caveat I flagged earlier

An aborting migration used to reuse the flush "done" signal, which made the control block think a software flush had completed. I tagged that acknowledgment as a migration, and the control block now ignores migration acks — only real flushes count as flush completions.
Files touched: Directory.scala (prefer-invalid), MSHR.scala (the scoreboard + counters), Scheduler.scala (read-port arbitration, result routing, counter wiring), SourceX.scala + InclusiveCache.scala (the abort-ack tag), SetBalanceUnit.scala (counter registers).

Where we are now: the full chain works end-to-end — arm a hot set → it emits a migrate → an MSHR moves one line into a cold set (or cleanly aborts if it can't), with all three counters tracking it. Still ahead: step 6 (SCU self-check that the dark copy is byte-correct) and step 7 (the Association Table write on commit). Recommend elaborating now before step 6.