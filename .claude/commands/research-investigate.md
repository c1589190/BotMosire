---
description: "When you need to deeply understand something – a problem, architecture, technology, or decision. Before jumping to solutions."
---

# Research & Investigate

**When to use:** When you need to deeply understand something – a problem, architecture, technology, or decision. Before jumping to solutions.

**Role:** You are an investigator. Your job is to understand everything, look under every stone, and get to the bottom of this. No assumptions. No shortcuts. No surface-level answers.

---

**Investigate:** $ARGUMENTS

Analyze, research, think, brainstorm, reevaluate, and produce a detailed plan with all context. Systematically investigate. Understand everything and anything. Look under every stone. Get to the bottom of this. Leave nothing assumed or unexplored.

## Don't

- Don't start implementing – this is research only
- Don't settle for the first answer you find
- Don't skip verifying your assumptions against actual code
- Don't present opinions as facts – separate findings from analysis
- Don't produce a vague recommendation – be concrete and actionable

## Step 1: Map Your Assumptions

Before investigating, write down:
- What do you THINK you know about this? (These are assumptions to verify)
- What do you KNOW you don't know? (These are your investigation targets)
- What constraints apply? (Time, tech debt, compatibility, team skills)
- What would make approach A better than approach B? (Decision criteria – define these BEFORE researching)

## Step 2: Map the Landscape

Get the full picture before diving deep:
- What are ALL the moving parts involved?
- What connects to what? What are the dependencies?
- What are the boundaries and interfaces?
- Draw the complete map – don't start investigating details until you see the whole

## Step 3: Deep Dive

For each component/aspect:
- Read the actual code, not just docs about it
- Trace the full flow end-to-end
- Understand not just WHAT it does, but WHY it was built this way
- Identify assumptions baked into the current design
- Find the edge cases, the gotchas, the things that smell off

## Step 4: Research External Context

- What are best practices for this?
- What do the official docs say?
- Are there known issues, gotchas, or breaking changes?
- What have others done in similar situations?
- What are the trade-offs nobody talks about?

## Step 5: Brainstorm Options

Generate at least 3 different approaches. For each one:
- **How it works** – concrete description, not hand-waving
- **Pros** – what's good about it
- **Cons** – what's bad about it
- **Risks** – what could go wrong
- **Effort** – rough complexity (small/medium/large)
- **Maintainability** – will future-you hate this?
- **Testability** – can you verify it works?

## Step 6: Verify Your Understanding

Challenge yourself:
- Can you explain this simply to someone with no context?
- Are there contradictions in what you found?
- Did you verify your top assumptions against reality (code, tests, running the system)?
- What questions remain unanswered?
- What are the unknowns you CAN'T resolve right now?

## Step 7: Plan in Detail

- **Recommended approach** with clear reasoning (why THIS one over the others)
- **Step-by-step implementation plan** – concrete, ordered, each step small enough to verify
- **Risk mitigation** for each identified risk
- **Success criteria** – how do we know it worked?
- **Rollback plan** – what if it doesn't work? How do we undo?

## Output Format

Produce a structured document:

```
## Summary
[One paragraph TL;DR – the answer, not just "I researched things"]

## Findings
[What you discovered, organized by topic. Facts, not opinions.]

## Assumptions Verified
[Which assumptions were confirmed/busted and how]

## Analysis
[Your interpretation and reasoning. Opinions go here.]

## Recommendation
[What to do, why, and what the trade-offs are]

## Implementation Plan
[Concrete, ordered steps]

## Open Questions
[What couldn't be resolved and what's needed to answer them]

## Sources
[Where you got your information – files read, docs checked, tests run]
```

## Success Criteria

- Every assumption has been verified or explicitly flagged as unverified
- At least 3 approaches were considered with honest trade-off analysis
- The recommendation is backed by evidence, not gut feeling
- The implementation plan is concrete enough that someone else could execute it
