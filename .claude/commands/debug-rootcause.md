---
description: "Something is broken. Find out WHY, not just what. Fix the cause, not the symptom."
---

# Debug & Root Cause Analysis

**When to use:** Something is broken. Find out WHY, not just what. Fix the cause, not the symptom.

**Role:** You are a detective. The bug is the crime. Your job is to find the root cause, understand exactly how it happens, fix it properly, and make sure it never happens again.

---

**The bug:** $ARGUMENTS

Systematically debug this. Don't guess. Don't try random fixes. Understand the problem completely before touching any code. Get to the root cause. Fix it once, fix it right.

## Step 1: Understand the Symptom

Before investigating, get crystal clear on:
- What EXACTLY is the broken behavior? (Specific error, wrong output, crash, etc.)
- What is the EXPECTED behavior?
- When did it start? What changed? (Check recent commits, deploys, config changes)
- Is it reproducible? Always, sometimes, only under certain conditions?
- What's the impact? (Blocking? Workaround exists? Cosmetic?)

## Step 2: Reproduce It

You MUST reproduce the bug before fixing it:
- Find the exact steps to trigger the issue
- Find the minimal reproduction (simplest case that still breaks)
- Document the reproduction steps so anyone can trigger it
- If you can't reproduce it, document what you tried and why it matters

**If you can't reproduce it, STOP. You cannot fix what you cannot see.**

## Step 3: Isolate

Narrow down the problem:
- Where in the code does it fail? (Stack trace, error logs, breakpoints)
- What's the last point where things are correct?
- What's the first point where things go wrong?
- Is it a data issue, logic issue, timing issue, or environment issue?

Techniques:
- Binary search through the code path (add logging at midpoints)
- Comment out sections to isolate the trigger
- Test with known-good inputs to rule out data issues
- Check if it happens in different environments

## Step 4: Find the Root Cause

Don't stop at the first thing that looks wrong. Ask 5 WHYs:
1. Why is this value wrong? → Because function X returns null
2. Why does function X return null? → Because the database query fails
3. Why does the query fail? → Because the column name changed
4. Why did the column name change? → Because a migration ran
5. Why did the migration run with wrong column name? → **ROOT CAUSE: migration was auto-generated without review**

The root cause is rarely where the error appears. Trace it back.

## Step 5: Understand the Full Impact

Before fixing:
- What else uses the same code path? Could they be affected too?
- Are there related bugs that share the same root cause?
- Could the fix break something else?
- Is this a symptom of a bigger systemic issue?

## Step 6: Fix It

Now and only now, write the fix:
- Fix the ROOT CAUSE, not the symptom
- Keep the fix minimal – don't refactor while debugging
- Add a test that fails without the fix and passes with it
- If the fix is risky, document the rollback plan

## Step 7: Verify the Fix

- Does the original reproduction case now work?
- Does the new test pass?
- Do ALL existing tests still pass? (Regression check)
- Test edge cases related to the fix
- Test in conditions similar to where the bug was found

## Step 8: Prevent Recurrence

- Why wasn't this caught earlier? (Missing test? Missing validation? Missing review?)
- Add the test that would have caught it
- If it's a pattern that could repeat, add a lint rule or validation
- Document the root cause and fix in commit message

## Output Format

```
## Bug Report
- **Symptom:** [What's broken]
- **Reproduction:** [Exact steps]
- **Root Cause:** [The actual underlying reason]
- **Impact:** [What's affected]

## Fix
- **What changed:** [Files and logic modified]
- **Why this fix:** [Why this approach over alternatives]
- **Test added:** [What test verifies the fix]

## Prevention
- **Why it wasn't caught:** [Gap in testing/review/validation]
- **What was added to prevent recurrence:** [Test, lint rule, etc.]
```

## Success Criteria

- Root cause is identified and explained (not just "fixed the error")
- Fix addresses the cause, not the symptom
- A test exists that would catch this if it regressed
- Existing tests still pass
- The fix is minimal and focused (no drive-by refactoring)
