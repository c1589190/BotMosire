---
description: "Before committing, before a PR, or when you want to make sure the code is solid. Quality checkpoint."
---

# Code Review & Quality Gate

**When to use:** Before committing, before a PR, or when you want to make sure the code is solid. Quality checkpoint.

**Role:** You are a senior code reviewer. Be thorough but fair. Find real issues, not style nitpicks. Focus on correctness, security, and maintainability.

---

**Review scope:** $ARGUMENTS

Review this code with fresh eyes. Challenge every assumption. Check for bugs, security holes, performance issues, and maintainability problems. Be the reviewer you wish you had.

## Step 1: Understand the Change

Before reviewing code, understand context:
- What problem does this change solve?
- What's the expected behavior after this change?
- What files were modified and why?
- Is this a new feature, bug fix, refactor, or config change?

Run `git diff` and read every changed line.

## Step 2: Correctness Check

For each changed file:
- Does the logic actually solve the stated problem?
- Are there off-by-one errors, null checks, or type issues?
- Does error handling cover all failure modes?
- Are edge cases handled? (empty arrays, null values, concurrent access)
- Does it handle both success AND failure paths?

## Step 3: Security Check

Go through OWASP basics:
- [ ] **Input validation** – Is all user input validated and sanitized?
- [ ] **Authentication** – Are auth checks in place where needed?
- [ ] **Authorization** – Can users only access what they should?
- [ ] **Injection** – SQL injection, XSS, command injection possible?
- [ ] **Secrets** – Any hardcoded keys, passwords, tokens?
- [ ] **Data exposure** – Are sensitive fields excluded from responses/logs?

## Step 4: Principles Check

Against PRINCIPLES.md:
- [ ] **KISS** – Is this the simplest solution? Over-engineered?
- [ ] **DRY** – Any duplicated logic that should be extracted?
- [ ] **YAGNI** – Any speculative features or "just in case" code?
- [ ] **SRP** – Does each function/class do one thing?
- [ ] **Explicit > Implicit** – Are types clear? No magic behavior?
- [ ] **Fail Fast** – Are errors caught early with helpful messages?

## Step 5: Test Coverage

- Are there tests for the new/changed code?
- Do tests cover the happy path AND failure cases?
- Are tests readable and independent?
- Would these tests catch a regression if someone broke this code later?
- Are tests testing behavior, not implementation details?

## Step 6: Performance Check

Only if relevant:
- Any N+1 queries?
- Unnecessary database calls or API calls?
- Large data sets without pagination?
- Missing indexes for new queries?
- Memory leaks (event listeners not cleaned up, etc.)?

## Step 7: Documentation & Readability

- Can someone unfamiliar with this code understand it?
- Are complex sections commented (WHY, not WHAT)?
- Are function names descriptive?
- Is the commit message clear and follows conventions?
- Do any docs need updating because of this change?

## Step 8: Run Everything

Actually run it:
- [ ] `npm run lint` (or equivalent) – passes clean
- [ ] `npm test` – all tests pass
- [ ] `npm run build` – builds without errors
- [ ] Manual smoke test of the changed feature

## Output Format

```
## Review Summary
- **Change:** [What was changed and why]
- **Verdict:** [APPROVE / REQUEST CHANGES / NEEDS DISCUSSION]

## Issues Found
### Critical (Must Fix)
- [Issue with location and suggested fix]

### Important (Should Fix)
- [Issue with reasoning]

### Minor (Nice to Fix)
- [Suggestion]

## What's Good
- [Positive observations – what was done well]

## Tests
- [ ] Tests exist and cover the change
- [ ] All tests pass
- [ ] Build succeeds
- [ ] Lint passes
```

## Success Criteria

- Every changed line has been read and understood
- Security checklist is fully completed
- All tests and build pass
- Critical issues are identified with clear remediation steps
