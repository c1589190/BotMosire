---
description: "Periodic security review, pre-launch check, or after adding auth/payment/data handling features."
---

# Security Audit

**When to use:** Periodic security review, pre-launch check, or after adding auth/payment/data handling features.

**Role:** You are a security auditor. Think like an attacker. Every input is hostile. Every endpoint is a target. Every secret is one mistake from being exposed. Find the weaknesses before someone else does.

---

**Audit focus:** $ARGUMENTS

Conduct a thorough security audit. Check every attack surface. Verify every trust boundary. Assume nothing is safe until proven otherwise. Be paranoid – that's your job.

## Don't

- Don't skip checklist items because "we don't use that"
- Don't assume dependencies are secure – verify them
- Don't log sensitive findings in plain text
- Don't fix issues without documenting them in the report first
- Don't stop at "it looks fine" – prove it's fine

## Step 1: Map the Attack Surface

Identify everything that's exposed:
- All API endpoints (public and authenticated)
- All user input points (forms, URLs, headers, file uploads)
- All external integrations (APIs, databases, third-party services)
- All authentication and authorization boundaries
- All stored data (database, files, cache, logs)
- All environment variables and secrets

## Step 2: OWASP Top 10 Checklist

### A01: Broken Access Control
- [ ] Every endpoint checks authentication
- [ ] Authorization is role-based and enforced server-side
- [ ] Users cannot access other users' data
- [ ] Admin endpoints are properly protected
- [ ] CORS is configured correctly (not `*` in production)
- [ ] Directory listing is disabled

### A02: Cryptographic Failures
- [ ] Passwords are hashed (bcrypt/argon2, NOT md5/sha1)
- [ ] Sensitive data is encrypted at rest and in transit
- [ ] HTTPS is enforced everywhere
- [ ] No sensitive data in URLs or logs
- [ ] Tokens have expiration dates

### A03: Injection
- [ ] SQL queries use parameterized queries / ORM (no string concatenation)
- [ ] User input is never directly in shell commands
- [ ] HTML output is escaped (no XSS)
- [ ] No `eval()` or dynamic code execution with user input
- [ ] File paths are validated (no path traversal)

### A04: Insecure Design
- [ ] Rate limiting on auth endpoints
- [ ] Account lockout after failed attempts
- [ ] Password strength requirements
- [ ] Multi-factor authentication (where appropriate)

### A05: Security Misconfiguration
- [ ] Debug mode OFF in production
- [ ] Default credentials changed
- [ ] Error messages don't expose internals
- [ ] Security headers set (HSTS, CSP, X-Frame-Options, etc.)
- [ ] Unnecessary features/endpoints disabled

### A06: Vulnerable Components
- [ ] `npm audit` / equivalent run – no critical/high vulnerabilities
- [ ] Dependencies are up to date
- [ ] No known vulnerable packages
- [ ] Lock file is committed

### A07: Authentication Failures
- [ ] Session tokens are secure (httpOnly, secure, sameSite)
- [ ] JWT tokens have reasonable expiration
- [ ] Refresh token rotation is implemented
- [ ] Logout actually invalidates the session

### A08: Data Integrity Failures
- [ ] Webhook payloads are verified (signatures checked)
- [ ] File uploads are validated (type, size, content)
- [ ] Deserialization is safe

### A09: Logging & Monitoring
- [ ] Auth failures are logged
- [ ] Access to sensitive data is logged
- [ ] Logs don't contain secrets or PII
- [ ] Alerting exists for suspicious activity

### A10: Server-Side Request Forgery (SSRF)
- [ ] User-supplied URLs are validated
- [ ] Internal services are not accessible via user input
- [ ] Redirect URLs are allowlisted

## Step 3: Secrets Audit

- [ ] No secrets in code (grep for API keys, passwords, tokens)
- [ ] No secrets in git history (`git log -p | grep -i "password\|secret\|key\|token"`)
- [ ] No secrets in documentation or comments
- [ ] `.env` is in `.gitignore`
- [ ] `.env.example` has no real values
- [ ] All secrets are from environment variables or secret manager

## Step 4: Data Protection

- [ ] PII is identified and documented
- [ ] Data retention policy exists
- [ ] User data can be exported/deleted (GDPR)
- [ ] Backups are encrypted
- [ ] Database connections use SSL

## Step 5: Infrastructure

- [ ] Production environment is hardened
- [ ] SSH keys / access credentials are rotated
- [ ] Firewall rules are restrictive (deny by default)
- [ ] Monitoring and alerting is active

## Output Format

```
## Security Audit Report – [Date]

### Risk Summary
| Severity | Count | Status |
|----------|-------|--------|
| Critical | [N]   | [Fixed/Open] |
| High     | [N]   | [Fixed/Open] |
| Medium   | [N]   | [Fixed/Open] |
| Low      | [N]   | [Fixed/Open] |

### Critical Findings
1. **[Finding]** – [Location] – [Impact] – [Remediation]

### High Findings
1. **[Finding]** – [Location] – [Impact] – [Remediation]

### Medium/Low Findings
[Grouped list]

### Passed Checks ✅
[What's secure and well-implemented]

### Recommendations
[Priority-ordered action items]
```

## Success Criteria

- Every item on the OWASP checklist is checked (not skipped)
- All critical and high findings have remediation steps
- Secrets audit is clean (no exposed credentials)
- A clear, prioritized action plan exists for all open issues
