# Risks and trade-offs

| Area | Current choice | Risk or limitation | Production follow-up |
| --- | --- | --- | --- |
| Agent behavior | Deterministic local agents; pluggable `AgentBackend` boundary | Does not demonstrate live model quality or prompt-injection resistance | Add a provider adapter with structured outputs, adversarial evals, timeout budgets, and model/version lineage. |
| Code changes | Implementation agent emits a map and validates checked-in modules; no arbitrary writes | The workflow validates the prototype rather than autonomously generating a patch | Add isolated worktrees, path allowlists, signed diff review, test sandbox, and human merge approval. |
| Persistence | SQLite with WAL | Single-node storage and local filesystem durability are not suitable for multi-replica orchestration | Use managed relational storage with transactions, backup, retention, and migrations. |
| Rate limiting | In-memory sliding window on link creation | Limits reset on restart and differ across service replicas | Use a shared Redis/token-bucket store and trusted proxy identity handling. |
| Analytics | Aggregate click count and last-access time; no visitor IP | No unique visitors, geographic breakdown, or abuse attribution | Add privacy review, retention policy, consent requirements, and opt-in aggregation. |
| URL safety | HTTP(S), no embedded credentials, local names and non-public IP literals rejected | Redirect destinations can still host phishing, change DNS later, or redirect elsewhere | Add abuse reporting, domain intelligence, user warnings, and policy review; avoid server-side fetching. |
| Authentication | Public create, redirect, and stats APIs | Anyone can create links and read per-link aggregate stats | Add identity, ownership, authorization, quotas, abuse controls, and administrative audit. |
| Orchestrator trust | Agents cannot deploy or write arbitrary source; outputs are size/path checked | Local process and database access are trusted; no multi-tenant isolation | Run workers in isolated containers with resource limits, OS-level filesystem policy, and signed artifact provenance. |
| Release | Atomic local artifact promotion with manual gate | Not a deployment pipeline and has no external environment rollback | Integrate deployment only after environment policy, staged rollout, health checks, and human authorization are designed. |
| Security checks | Small source policy scan and automated tests | Not SAST, dependency audit, DAST, or penetration testing | Add pinned dependency lock, SBOM, vulnerability scanning, secret scanning, SAST, DAST, and threat modeling. |

## Security and change-control rules in this prototype

- Only HTTP and HTTPS redirect destinations are accepted; embedded credentials and obvious local targets are rejected.
- User-controlled values are passed as SQLite parameters, not interpolated into SQL.
- Click updates run transactionally; redirect responses are not cacheable.
- The audit store records decisions and output hashes. It never stores the input request as an executable command.
- Agent outputs have a size cap. Documentation output is restricted to a single approved artifact path.
- Release readiness checks tests, source policy results, implementation presence, and requirement-gate disposition.
- A failed check blocks promotion. Approval does not override a failed release-readiness result.
- No agent has a production credential or cloud deployment tool.

## Assumptions

- A custom alias is public and non-sensitive.
- One redirect is one click; retries by a browser may count as additional clicks.
- Expiry is optional; links without an expiry remain active.
- The demo's public stats endpoint is acceptable for aggregate counts. Production access control and retention need product requirements.
