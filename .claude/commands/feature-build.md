---
description: "Building a new feature or significant functionality. The workhorse template – bigger than a quick-task, needs structure."
---

# Feature Build

**When to use:** Building a new feature or significant functionality. The workhorse template – bigger than a quick-task, needs structure.

**Role:** You are a senior developer building a feature from requirements to working code. Plan first, build incrementally, test as you go, document what you did.

---

**Feature to build:** $ARGUMENTS

Build this feature properly. Understand requirements first. Plan the approach. Implement incrementally – small steps, each verified. No big-bang implementations. No "I'll add tests later." Do it right, step by step.

## Don't

- Don't start coding before understanding the full scope
- Don't implement everything at once – break it into small, verifiable steps
- Don't skip tests because "it's obvious"
- Don't refactor existing code while building the feature (separate concern)
- Don't add scope – build exactly what was asked, nothing more
- Don't forget to update documentation

## Step 1: Understand Requirements

Before writing a single line:
- What exactly should this feature do? (Specific behavior, not vague goals)
- Who uses it? What's the user flow?
- What are the edge cases?
- What are the acceptance criteria? (How do we know it's done?)
- What are the constraints? (Performance, security, compatibility)

If requirements are unclear, ASK before building.

## Step 2: Assess Impact

- What existing code will this touch?
- What could break?
- Are there dependencies on other features or services?
- Does this need database changes?
- Does this need new environment variables or config?
- Estimate: is this a 1-file change or a 10-file change?

## Step 3: Plan the Implementation

Break the feature into small steps. Each step should:
- Be independently testable
- Not break existing functionality
- Build on the previous step

Write out the steps before coding:
1. [First small piece – e.g., "Create the database schema"]
2. [Second piece – e.g., "Add the API endpoint"]
3. [Third piece – e.g., "Build the UI component"]
4. [Integration – e.g., "Connect frontend to backend"]
5. [Polish – e.g., "Error handling, loading states, edge cases"]

## Step 4: Build Incrementally

For each step:
1. **Implement** the smallest working version
2. **Test** it works (write a test or manually verify)
3. **Commit** with a clear message describing what was added
4. **Move on** to the next step

Do NOT move to the next step if the current one is broken.

## Step 5: Test the Feature

After all steps are complete:
- [ ] Happy path works end-to-end
- [ ] Error cases are handled gracefully
- [ ] Edge cases are covered
- [ ] Existing tests still pass (regression check)
- [ ] New tests cover the new functionality
- [ ] Manual smoke test confirms it works as a user would experience it

## Step 6: Clean Up & Document

- [ ] Remove any debug code or console.logs
- [ ] Code follows project conventions and PRINCIPLES.md
- [ ] No TODO comments without context
- [ ] Update relevant documentation (README, API docs, CLAUDE.md)
- [ ] If new env vars were added, update .env.example
- [ ] Final commit with clean state

## Output

```
## Feature: [Name]

### What was built
[1-2 sentence description]

### Implementation
- [File]: [What was added/changed]
- [File]: [What was added/changed]

### Tests
- [Test]: [What it verifies]

### How to use
[Brief user-facing description]

### Decisions made
- [Decision]: [Why this approach]
```

## Success Criteria

- Feature works as specified in requirements
- All tests pass (new and existing)
- Code is clean and follows project conventions
- Documentation is updated
- Someone else could understand and maintain this code
