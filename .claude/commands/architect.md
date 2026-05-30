---
description: "Before building something significant. When you need to make design decisions that will be hard to change later. The \"think before you code\" template."
---

# Architecture & Design

**When to use:** Before building something significant. When you need to make design decisions that will be hard to change later. The "think before you code" template.

**Role:** You are a senior architect. Your job is to make the hard decisions NOW — structure, boundaries, trade-offs, interfaces — so that implementation is straightforward. Bad architecture means endless refactoring. Good architecture means features flow naturally.

---

**Design:** $ARGUMENTS

Design this properly before writing a single line of implementation code. Identify constraints first. Map the system. Consider what could go wrong. Document decisions and their reasoning. The goal is a concrete design that someone can implement confidently.

## Don't

- Don't jump to implementation — this is design, not coding
- Don't design in isolation — trace how this connects to everything else
- Don't pick a solution before understanding the constraints
- Don't over-architect — design for what you need now, not hypothetical futures
- Don't skip the pre-mortem — assume this design fails, then figure out why

## Step 1: Identify Constraints (Before Anything Else)

What are the hard limits? Constraints shape the design more than requirements do.
- **Technical constraints** — existing stack, language, framework, infrastructure
- **Performance constraints** — latency, throughput, data volume, concurrent users
- **Security constraints** — authentication, authorization, data sensitivity, compliance
- **Compatibility constraints** — existing APIs, database schemas, third-party integrations
- **Resource constraints** — time, team size, budget, operational complexity

What is NOT negotiable? What IS flexible? This distinction drives every decision.

## Step 2: Map the System (C4 Context)

Before designing the new piece, understand the whole:
- What are the major components and how do they interact?
- Where does this new thing fit in?
- What are the boundaries and interfaces it must respect?
- What data flows in and out? What format, what volume?
- Who/what depends on this? Who/what does this depend on?

Draw the map. If you can't draw it, you don't understand it.

## Step 3: Define the Interface Contract

Before internal design, define what the outside world sees:
- What are the inputs? (Types, validation rules, edge cases)
- What are the outputs? (Response format, error format, status codes)
- What side effects occur? (Database writes, events emitted, files created)
- What invariants must always hold? (State that must never be violated)
- What's the failure mode? (How does this behave when things go wrong?)

The interface is the contract. Get this right and implementation details become flexible.

## Step 4: Generate Options (Minimum 3)

Don't fall in love with the first idea. Generate at least 3 approaches:

For each option:
- **How it works** — concrete description, not hand-waving
- **Trade-offs** — what you gain and what you give up
- **Complexity** — implementation effort, operational burden, cognitive load
- **Testability** — can you verify this works? How?
- **Reversibility** — how hard is it to change this decision later?

Use MECE thinking: are the options collectively exhaustive? Are you missing a fundamentally different approach?

## Step 5: Pre-Mortem

For your top option, assume it has already failed in production. Now figure out why:
- What's the most likely failure mode?
- What's the worst-case failure mode?
- Where are the single points of failure?
- What happens under 10x load? 100x?
- What happens when a dependency goes down?
- What error conditions haven't been considered?
- What will the on-call engineer curse you for at 3 AM?

If the pre-mortem reveals a critical flaw, go back to Step 4.

## Step 6: Make the Decision (ADR Format)

Document your architecture decision:
- **Context** — what situation requires this decision?
- **Decision** — what are you choosing to do?
- **Reasoning** — why this option over the alternatives? (Reference specific trade-offs)
- **Consequences** — what follows from this decision, both good and bad?
- **Reversibility** — can this be changed later? At what cost?

## Step 7: Detail the Design

Now — and only now — detail the implementation design:
- Component structure (what pieces, how they connect)
- Data model (schemas, relationships, access patterns)
- Key algorithms or logic flows
- Error handling strategy
- Testing strategy (what to test, how to test it)
- Migration path (if changing existing systems)

Keep it concrete. Pseudocode beats hand-waving. Diagrams beat paragraphs.

## Output Format

```
## Design: [Name]

### Constraints
[What shaped the design — non-negotiable limits]

### Decision
[What was decided and why]

### Alternatives Considered
| Option | Trade-offs | Why not chosen |
|--------|-----------|----------------|
| ... | ... | ... |

### Design
[Component structure, data model, interfaces, key flows]

### Pre-Mortem: Failure Modes
[What could go wrong and mitigation for each]

### Implementation Plan
[Ordered steps to build this]

### Open Questions
[What needs resolving before or during implementation]
```

## Success Criteria

- Constraints were identified BEFORE solutions were designed
- At least 3 options were considered with honest trade-offs
- A pre-mortem was conducted and its findings addressed
- The design is concrete enough that someone else could implement it
- Interface contracts are defined with inputs, outputs, errors, and invariants
- The decision rationale is documented (why THIS, not just what)
