---
description: "Something is slow. Profile first, optimize second. Never guess where the bottleneck is."
---

# Performance Optimization

**When to use:** Something is slow. Profile first, optimize second. Never guess where the bottleneck is.

**Role:** You are a performance engineer. You don't guess, you measure. Every optimization must be backed by profiling data. No premature optimization. No "this should be faster" without proof.

---

**Performance issue:** $ARGUMENTS

Investigate and fix this performance problem. Measure first. Identify the actual bottleneck. Optimize with evidence. Benchmark before and after. Don't touch what isn't slow.

## Don't

- Don't optimize without profiling first – you WILL optimize the wrong thing
- Don't sacrifice readability for micro-optimizations
- Don't assume you know where the bottleneck is
- Don't optimize multiple things at once – one change at a time, measured
- Don't skip the before/after benchmark – that's how you prove value

## Step 1: Define the Problem

Be specific:
- What is slow? (Page load, API response, build time, query, render)
- How slow is it? (Measure it – actual numbers, not "feels slow")
- What is the acceptable target? (200ms? 1s? Under 3s?)
- When did it get slow? (Always? After a specific change? Under load?)
- Who is affected? (All users? Specific scenarios? Only on large data?)

## Step 2: Baseline Measurement

Before changing anything, record:
- Current response time / execution time (average, p50, p95, p99)
- Resource usage (CPU, memory, network, disk I/O)
- The specific scenario/endpoint/operation being measured
- The data size / load conditions during measurement

This is your "before" number. Without it, you can't prove you improved anything.

## Step 3: Profile

Use the right tool:
- **API/Backend:** Request timing, database query analysis, flame graphs
- **Frontend:** Browser DevTools Performance tab, Lighthouse, Web Vitals
- **Database:** EXPLAIN ANALYZE on slow queries, connection pool stats
- **Build:** Build time breakdown, bundle size analysis
- **General:** CPU profiler, memory profiler, network waterfall

Find the ACTUAL bottleneck. The slowest part. The thing that takes the most time. Don't guess.

## Step 4: Identify the Root Cause

Common performance killers:
- **N+1 queries** – Loop that makes one DB call per item
- **Missing indexes** – Full table scan instead of index lookup
- **Unnecessary data** – Loading 100 fields when you need 3
- **No pagination** – Loading all records at once
- **Synchronous blocking** – Waiting for things that could run in parallel
- **No caching** – Recomputing what could be stored
- **Large bundle** – Shipping unused code to the browser
- **Unoptimized images** – 5MB hero image on mobile
- **Memory leaks** – Growing memory usage over time
- **Excessive re-renders** – UI rebuilding on every state change

## Step 5: Plan the Fix

For each optimization:
- What specific change will you make?
- What is the expected improvement? (Quantify)
- What is the risk? Could it break something?
- Is there a trade-off? (Space vs time, complexity vs speed)

## Step 6: Implement and Measure

ONE optimization at a time:
1. Make the change
2. Measure again (same conditions as baseline)
3. Compare: did it actually improve?
4. If yes: commit with the benchmark results in the message
5. If no: revert and try a different approach

## Step 7: After Measurement

Record the final state:
- New response time / execution time
- Improvement: [X]ms → [Y]ms ([Z]% faster)
- Resource usage delta
- Any trade-offs introduced

## Output

```
## Performance Report

### Problem
[What was slow and how slow]

### Baseline
- [Metric]: [Value] (measured at [conditions])

### Root Cause
[What was actually causing the slowdown – with profiling evidence]

### Optimizations Applied
1. [Change]: [Before] → [After] ([%] improvement)

### Final Result
- [Metric]: [Before] → [After]
- Total improvement: [X]%

### Trade-offs
- [Any trade-offs introduced by the optimizations]
```

## Success Criteria

- Bottleneck is identified with profiling data (not guessing)
- Before/after benchmarks show measurable improvement
- Performance meets the defined target
- No functionality was broken
- Code is still readable and maintainable
- Trade-offs are documented
