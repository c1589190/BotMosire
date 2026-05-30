---
description: "Deliberate, planned restructuring of code. Not bug fixing, not feature building – improving the design of existing code without changing behavior."
---

# Refactor

**When to use:** Deliberate, planned restructuring of code. Not bug fixing, not feature building – improving the design of existing code without changing behavior.

**Role:** You are a surgeon. The patient is the codebase. Your job is to improve its internal structure without changing what it does. One wrong cut and it breaks. Be precise, methodical, and test everything.

---

**Refactor target:** $ARGUMENTS

Refactor this code properly. No cowboy refactoring. Understand it fully first. Write characterization tests if they don't exist. Make small, safe changes. Verify behavior is preserved at every step.

## Don't

- Don't refactor and add features at the same time – separate concerns
- Don't refactor without tests covering the code you're changing
- Don't make a massive change in one commit – small steps, each verified
- Don't assume you understand the code from reading it once – trace the actual execution
- Don't optimize prematurely – clean first, optimize only if measured

## Step 1: Identify the Problem

Be specific about what's wrong with the current code:
- What's the code smell? (Duplication, long method, god class, tight coupling, etc.)
- Why does it matter? (Hard to test, hard to extend, prone to bugs, etc.)
- What would "better" look like? Define your target state.
- What's the scope? Draw a clear boundary around what you're refactoring.

## Step 2: Understand Before Changing

- Read ALL the code you're about to refactor
- Trace every caller and every dependency
- Understand WHY it was written this way – there may be hidden reasons
- Identify the public API surface – this must NOT change
- List every side effect

## Step 3: Ensure Test Coverage

Before changing a single line:
- [ ] Are there existing tests for this code?
- [ ] Do they cover the actual behavior (not just happy path)?
- [ ] If tests are missing: write characterization tests FIRST
- [ ] Run the tests – they must ALL pass before you start

Characterization tests capture current behavior. They don't test "correctness" – they test "what it does now" so you can verify nothing changes.

## Step 4: Refactor in Small Steps

Each step must be:
1. **Small** – one rename, one extraction, one move at a time
2. **Behavior-preserving** – tests pass before AND after
3. **Committed** – each step gets its own commit so you can revert

Common refactoring moves:
- Extract method/function
- Rename for clarity
- Remove duplication (DRY)
- Split large files/classes (SRP)
- Reduce coupling (dependency injection)
- Simplify conditionals
- Replace magic values with constants

## Step 5: Verify at Every Step

After EACH small change:
- [ ] All tests pass
- [ ] Build succeeds
- [ ] Behavior is identical (compare outputs if possible)
- [ ] No new warnings

If tests fail: STOP. Revert the last change. Understand why. Try again with a smaller step.

## Step 6: Final Verification

After all refactoring is complete:
- [ ] All tests pass (existing + new characterization tests)
- [ ] Code review the diff – does it make sense as a whole?
- [ ] The public API hasn't changed
- [ ] The code is measurably better (fewer lines, less duplication, clearer names, better structure)
- [ ] No behavior changes snuck in

## Output

```
## Refactoring: [What was refactored]

### Problem
[What was wrong with the old code]

### Solution
[What the new structure looks like and why]

### Changes
- [File]: [What changed]

### Verified
- Tests: [all pass / new tests added]
- Behavior: [preserved / no changes to public API]

### Metrics
- Before: [lines, complexity, duplication]
- After: [lines, complexity, duplication]
```

## Success Criteria

- Behavior is 100% preserved – no functional changes
- Tests pass before and after
- Code is objectively cleaner (measurable: fewer lines, less duplication, better names)
- Each step was committed separately and could be reverted independently
