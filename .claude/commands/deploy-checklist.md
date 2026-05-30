---
description: "Before deploying to production. Every time. No exceptions."
---

# Deploy Checklist

**When to use:** Before deploying to production. Every time. No exceptions.

**Role:** You are a deployment engineer. Your job is to make sure this deploy goes smoothly, nothing breaks, and if it does, we can roll back instantly.

---

**Deploying:** $ARGUMENTS

Deploy is the moment of truth. One mistake here can break production for real users. Be methodical. Check everything. Have a rollback plan BEFORE you push.

## Don't

- Don't deploy on a Friday afternoon
- Don't deploy without testing locally first
- Don't skip the checklist because "it's a small change"
- Don't deploy with failing tests or lint errors
- Don't deploy without knowing how to roll back

## Step 1: Pre-Deploy Verification

Before touching production:
- [ ] All tests pass locally
- [ ] Lint passes clean
- [ ] Build succeeds without warnings that matter
- [ ] The feature/fix works as expected in dev environment
- [ ] No console.log debug statements left in code
- [ ] No hardcoded dev URLs, API keys, or test data
- [ ] Environment variables are set for production
- [ ] `.env.example` is up to date with any new variables

## Step 2: Database & Data

- [ ] Database migrations are ready and tested
- [ ] Migrations are backwards-compatible (can roll back without data loss)
- [ ] No destructive migrations without explicit backup
- [ ] Seed data is not included in production migrations
- [ ] If schema changed: verify ORM/query compatibility

## Step 3: Dependencies & Config

- [ ] No new dependencies with known vulnerabilities
- [ ] Lock file is committed and up to date
- [ ] Build config is correct for production (minification, source maps, etc.)
- [ ] API endpoints point to production services
- [ ] Third-party integrations configured for production (Stripe live keys, etc.)

## Step 4: Deploy

- [ ] Note the current production state (commit hash, version)
- [ ] Deploy using the standard deployment process
- [ ] Watch the deployment logs for errors
- [ ] Wait for deployment to complete fully before testing

## Step 5: Post-Deploy Smoke Test

Immediately after deploy:
- [ ] App loads without errors
- [ ] Core user flow works end-to-end
- [ ] Auth works (login, signup, logout)
- [ ] Payment flow works (if applicable – use test mode)
- [ ] API endpoints respond correctly
- [ ] No new errors in logs or monitoring
- [ ] Performance is not significantly degraded

## Step 6: Rollback Plan

If ANYTHING is wrong:
1. What is the rollback command? (Document it here)
2. How long does rollback take?
3. Will rollback affect the database? (Migrations may need reverting)
4. Who needs to be notified?

## Output

```
## Deploy Report – [Date]

### Deployed
- Version/commit: [hash]
- Environment: [production]
- Changes: [brief description]

### Smoke Test Results
- [ ] App loads: PASS/FAIL
- [ ] Core flow: PASS/FAIL
- [ ] Auth: PASS/FAIL
- [ ] Payments: PASS/FAIL

### Rollback
- Previous version: [hash]
- Rollback command: [command]
- Rollback tested: YES/NO
```

## Success Criteria

- App is live and working for real users
- No new errors in logs
- Rollback plan is documented and tested
- Team is notified of the deploy
