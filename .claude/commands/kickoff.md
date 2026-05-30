---
description: "Start of a new Claude Code session. Before you touch ANYTHING."
---

# Session Kickoff

**When to use:** Start of a new Claude Code session. Before you touch ANYTHING.

**Role:** You are the project's navigator. Your job is to fully understand the terrain before taking a single step.

---

**Focus for this session:** $ARGUMENTS

This is a new session. You know NOTHING yet. Do NOT assume, do NOT guess, do NOT start coding. Read first. Understand everything. Then confirm alignment before any work begins.

## Don't

- Don't start coding before reading ALL documentation
- Don't assume you know the project from the file names
- Don't skip reading HANDOVER.md – it has critical context from the last session
- Don't make changes until the user confirms your understanding
- Don't ignore failing tests or build errors – report them first

## Step 1: Read Everything

Read ALL of these in full – no skimming, no shortcuts:
- `CLAUDE.md` – project rules, overview, architecture (if exists)
- `README.md` – what this project is
- `HANDOVER.md` – what the last session left behind (if exists)
- Every `.md` file in root and `docs/`
- `package.json` / `pyproject.toml` / equivalent – dependencies, scripts
- Recent git log – what happened recently

Do NOT proceed until you've read them all.

## Step 2: Understand the Project

Answer these questions for yourself:
- What does this app do? Who uses it?
- What's the tech stack and why?
- What's the folder structure? What lives where?
- What are the key entry points and hot paths?
- What patterns and conventions does this codebase follow?
- What's the current state – working? broken? half-finished?

## Step 3: Understand Current State

- What was the last work done? Is it complete?
- Are there uncommitted changes? Understand what they are.
- Are there open TODOs, known bugs, or blocked tasks?
- What were the planned next steps from the last session?
- Is anything in a half-finished, fragile state?

## Step 4: Verify the Environment

Actually run these – don't assume they work:
- Can the project build? (`npm run build` / equivalent)
- Can it start? (`npm run dev` / equivalent)
- Do tests pass? (`npm test` / equivalent)
- Is the git state clean?
- Report ANY issues found.

## Step 5: Plan Your Context Budget

This is critical – context is finite:
- Estimate how much context this session has
- If the task is large, plan for a handover before context runs out
- If the task needs more than one session, create a `DECISIONS.md` to log choices made
- Always keep enough reserve to do a clean handover (~15%)

## Step 6: Confirm Alignment

Before ANY work:
- Summarize what you understand about the project (2-3 sentences)
- State the current priorities as you understand them
- Ask clarifying questions if ANYTHING is unclear
- Do NOT start work until the user confirms your understanding

## Rules for This Session

- Follow all principles in PRINCIPLES.md (DRY, SOLID, KISS, YAGNI)
- Document every decision and its reasoning
- Commit regularly with clear, conventional messages
- If context gets low (~15%), STOP and switch to handover protocol immediately
- No heroics – if something is unclear, ask

## Success Criteria

- You can explain the project to someone in 30 seconds
- You know exactly what needs to be done and in what order
- The environment works and you've verified it
- User has confirmed your understanding before work begins
