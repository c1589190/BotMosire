---
description: "Periodic maintenance. Make sure everything is well documented, organized, consolidated. No mess. No chaos. Professional."
---

# Housekeeping & Cleanup

**When to use:** Periodic maintenance. Make sure everything is well documented, organized, consolidated. No mess. No chaos. Professional.

**Role:** You are the project's janitor and librarian. Your job is to leave this codebase cleaner, more organized, and better documented than you found it.

---

**Focus areas:** $ARGUMENTS

Perform a full housekeeping audit. Make sure all is well documented and organized. Docs consolidated. Docs updated. No variety of weird files in root. No dead code. No orphaned configs. Everything has a place and a purpose.

## Don't

- Don't delete files without understanding what they do first
- Don't consolidate docs that serve different audiences
- Don't remove "unused" code without verifying it's actually unused
- Don't make this a refactoring session – focus on cleanup only
- Don't skip the before/after metrics – that's how you prove value

## Step 0: Take Inventory (Before Metrics)

Before changing anything, record the current state:
- Total `.md` files: [count]
- Files in root directory: [list]
- `npm audit` results: [summary]
- Unused dependencies: [list]
- Known documentation gaps: [list]

This is your "before" snapshot to measure progress.

## Step 1: Documentation Audit

Read EVERY markdown file in the project. For each one, ask:
- Is this still accurate? Does it reflect the actual current state?
- Does this duplicate content from another doc?
- Does anyone need this? Does it have a clear purpose and audience?
- When was this last relevant?

**Actions:**
- **Outdated** → Update to reflect reality or delete if no longer relevant
- **Duplicate** → Merge into the single source of truth, delete the copy
- **Orphaned** → If no one needs it, remove it
- **Missing** → If there's a gap (feature without docs), flag it

**Key docs to verify:**
- `CLAUDE.md` – Does it accurately describe the project?
- `README.md` – Would a new developer understand the project from this?
- Any guides in `docs/` – Still correct? Still needed?

## Step 2: Root Directory Cleanup

The root directory should be CLEAN. Only these belong there:
- Config files that MUST be in root (package.json, tsconfig, .eslintrc, etc.)
- Entry points (README.md, CLAUDE.md)
- Standard files (.gitignore, .env.example, LICENSE)

Everything else goes in appropriate subdirectories. Ask for each root file:
- Does this HAVE to be here, or can it live in `src/`, `docs/`, `config/`?
- Is this a leftover from debugging, testing, or experimentation?
- Is this actually used by anything?

## Step 3: Dead Code & File Cleanup

Search for and eliminate:
- Unused imports and exports
- Commented-out code blocks (either restore with reason or delete)
- Empty files or placeholder files
- Unused components, functions, or modules
- `console.log` / debug statements left in production code
- TODO comments without context (add context or create an issue)

## Step 4: Dependency Audit

- Run `npm audit` (or equivalent) – fix or document vulnerabilities
- Identify unused dependencies (`depcheck` or manual review)
- Remove anything not actually imported/used
- Verify lock file is committed and current
- Check for outdated major versions that might need attention

## Step 5: Configuration Review

- `.env.example` – Does it list ALL required env vars? Are descriptions clear?
- TypeScript/ESLint/Prettier configs – Appropriate? Consistent?
- Build scripts – Do they all work? Any dead scripts?
- Are there hardcoded values that should be configurable?

## Step 6: Consolidation

This is the most important step. Consolidate aggressively:
- Multiple docs covering the same topic → merge into one
- Scattered config files → centralize where possible
- Redundant helper functions → single utility
- Multiple similar patterns → standardize on one

**Before removing anything, document:**
- What was removed and why
- What it was replaced with (if applicable)
- Risk of removal (none/low/medium)

## Step 7: After Metrics & Report

Record the new state and produce a summary:

```
## Housekeeping Report – [Date]

### Before → After
- .md files: [X] → [Y] ([consolidated/removed])
- Root files: [X] → [Y]
- Unused dependencies: [X] → [Y]
- Vulnerabilities: [X] → [Y]

### Changes Made
- [What was cleaned up]
- [What was consolidated]
- [What was removed and why]

### Issues Found (Need Separate Attention)
- [Things that need more than housekeeping to fix]

### Recommendations
- [What should be done next time]
- [Recurring mess patterns to address at the source]
```

## Success Criteria

- Root directory is clean – every file has a clear reason to be there
- No duplicate documentation – each topic has exactly one source of truth
- No dead code, unused deps, or orphaned files
- All remaining docs are accurate and up-to-date
- The "after" metrics are measurably better than "before"
