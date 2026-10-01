# Risks and trade-offs

| Area | Current choice | Risk or limitation | Follow-up |
| --- | --- | --- | --- |
| Agent reasoning | Optional OpenAI-compatible model; structured JSON stage output and prior-stage context | Results vary by model/version; prompt injection and inaccurate reasoning remain possible | Pin provider/model, add adversarial evals, isolate untrusted repository content, and store provider/model lineage. |
| Offline fallback | Repository scanner and policy checks, but no fabricated implementation or repair | Runs cannot complete code generation without a provider | Keep incomplete status explicit; add a separately validated local model option when available. |
| Source changes | Full-file Java create/update/delete in isolated candidate; diff hash bound to approval | Large files make full-file review noisy; malformed code remains possible | Add line-oriented patches, stronger diff UX, and a secure patch format. |
| Candidate execution | Fixed Maven test command, timeout, output cap, isolated directory | This application-level isolation does not prevent a malicious Maven plugin or test from using host resources/network | Use disposable containers/VMs, non-root users, read-only base images, network restrictions, and resource quotas. |
| Human approval | Actor and rationale persisted for exact diff and release bundle | CLI accepts an actor string; it does not authenticate identity or enforce roles | Integrate identity, authorization, signed approvals, and separation of duties. |
| Persistence | SQLite with WAL | Single-node storage and local filesystem durability do not support multi-replica orchestration | Use managed relational storage with transactions, backups, retention, and migrations. |
| Rate limiting | In-memory sliding window for link creation | Limits reset on restart and differ across replicas | Use a shared Redis/token-bucket store and trusted proxy handling. |
| Analytics | Aggregate click count and last-access time; no visitor IP | No unique visitors, geographic breakdown, or abuse attribution | Add privacy review, retention policy, and opt-in aggregation. |
| URL safety | HTTP(S), no embedded credentials, local names and non-public IP literals rejected | Destinations may still host phishing, change DNS, or redirect elsewhere | Add abuse reporting and user warnings; avoid server-side fetching. |
| Release | Atomic local promotion after human gate | It is not a deployment pipeline and has no external environment rollback | Design deployment only after environment policy, staged rollout, health checks, and authorization are established. |
| Security checks | Static source checks and candidate tests | Not SAST, dependency audit, DAST, or penetration testing | Add pinned dependency management, SBOM, vulnerability/secret scanning, SAST, DAST, and threat modeling. |

## Change-control rules

- Only relative `.java` paths under `src/main/java` and `src/test/java` are accepted from the code-generation provider.
- Every proposed file must cite valid acceptance-criterion IDs. Every criterion must be represented by at least one generated test file before readiness.
- The reviewer approves the exact proposal hash before candidate application. Source and test inputs are fingerprinted before each approval.
- Candidate builds capture the fixed Maven command, exit status, duration, and output. Failed builds cannot pass readiness; each repair must be reapplied and retested.
- Requirements or source changes archive earlier artifacts, supersede previous approvals, and recalculate downstream work.
- A failed check blocks promotion. Release approval cannot override failed readiness.
- The original checkout remains unchanged by candidate application. Candidate failure or denial deletes the candidate; rollback restores the prior local release pointer and records whether the baseline fingerprint matches.
- No agent has production credentials or a cloud deployment tool.

## Service assumptions

- A custom alias is public and non-sensitive.
- One successful redirect is one click; browser retries may count as additional clicks.
- Expiry is optional; links without an expiry remain active.
- The demo's public stats endpoint is acceptable for aggregate counts. Production needs access control and retention requirements.
