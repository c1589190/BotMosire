---
description: "Context window is ~10-15% remaining. STOP EVERYTHING. This is not optional."
---

# Low-Context Handover

**When to use:** Context window is ~10-15% remaining. STOP EVERYTHING. This is not optional.

**Role:** You are now a documentarian. Your only job is to capture the complete state so the next session can pick up seamlessly.

---

**Session context:** $ARGUMENTS

CRITICAL LOW CONTEXT – IMMEDIATE ACTION REQUIRED.

STOP all tasks. STOP all implementation. STOP all debugging. Nothing else matters except documenting the current state RIGHT NOW.

Any plan not yet written down must be documented immediately as the first task. No other work may continue or be completed before this is done.

## Don't

- Don't try to "just finish this one thing" – you will run out of context
- Don't write incomplete handovers – the next session depends on this
- Don't leave uncommitted changes without documenting them
- Don't skip the rollback info – the next session needs to know how to undo

## Step 1: 30-Second Summary (Write This First)

Before anything else, write a one-paragraph summary:
- What was the focus of this session?
- What's done? What's not done?
- What's the single most important thing the next session needs to know?

## Step 2: Handle Partial Work

Before documenting, secure any in-progress work:
- **Uncommitted changes?** → `git stash` or commit with `WIP:` prefix
- **Partial implementation?** → Document exactly what's done, what's missing
- **Broken state?** → Document what's broken and how to fix it
- **Tests failing?** → Document which tests and why

## Step 3: Create/Update HANDOVER.md

Create `HANDOVER.md` in the project root with this EXACT structure:

```markdown
# Handover – [Date]

## 30-Second Summary
[One paragraph: what happened, what's the state, what's next]

## Session Focus
[What was this session trying to accomplish?]

## Completed
- [x] [Thing that's done and working]
- [x] [Another completed item]

## In Progress
- [ ] [Partial work] – Status: [what's done, what's left]
- [ ] [Another item] – Blocked by: [reason]

## Decisions Made
| Decision | Why | Alternatives Considered |
|----------|-----|------------------------|
| [Choice] | [Reasoning] | [What else was considered] |

## Known Issues
- [Issue]: [Impact and workaround if any]

## Next Steps (Priority Order)
1. [Most important – do this first]
2. [Second priority]
3. [Third priority]

## Rollback Info
- If the current approach doesn't work: [what to undo and how]
- Last known good state: [commit hash or description]

## Files Modified This Session
- `path/to/file.ts` – [what changed and why]

## Quick Links
- [Relevant docs, issues, or references]
```

## Step 4: Instructions for Next Session

The next AI session MUST:
- Read ALL task-relevant guides and `.md` files in FULL before taking ANY action
- Verify the current state matches what's documented in HANDOVER.md
- NOT assume anything – validate everything against the actual codebase
- Check git status, test results, and build state before continuing work

## Step 5: Final Checklist

- [ ] 30-second summary written
- [ ] All partial work stashed or committed
- [ ] HANDOVER.md is complete with ALL sections filled
- [ ] Every modified file is listed with what changed
- [ ] Next steps are clear, prioritized, and actionable
- [ ] Rollback info is documented
- [ ] Git status is clean (committed or documented why not)
- [ ] No orphaned TODOs without documentation
- [ ] Next session can pick up without ANY guesswork

## Documentation Standards

All work must be fully documented, professionally structured, and strictly follow DRY, SOLID, KISS, YAGNI principles. Maintain a continuous self-learning document capturing all decisions, insights, and lessons learned.

## Success Criteria

- A brand new AI session with zero context can read HANDOVER.md and continue work without asking a single clarifying question
