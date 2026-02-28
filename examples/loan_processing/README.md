# Loan Processing Example

A pure-workflow example (no DB, no HTTP, no templates) that exercises Mycelium's
core features through a realistic loan application domain.

## Running

```bash
cd examples/loan_processing
clj -M:test
```

## Domain

Three workflows process loan applications through validation, credit assessment,
eligibility routing, and notifications — all with in-memory data.

### Test Applicants

| Name  | Credit Profile | Typical Outcome |
|-------|---------------|-----------------|
| Alice | 5 accounts, 10yr history, clean record | Low risk (score ~850) |
| Bob   | 3 accounts, 5yr history, 1 late payment | Medium risk (score ~650) |
| Carol | 1 account, 2yr history, 5 late + 1 bankruptcy | High risk (score 300) |

### Decision Rules

- **Low risk + amount <= $50k** → auto-approve
- **High risk** → auto-reject
- **Everything else** (medium risk, or low risk + large amount) → manual review

## Mycelium Features Exercised

### Subworkflow Composition

The credit assessment pipeline is defined as a standalone workflow, then
registered as a cell with `compose/register-workflow-cell!`:

```
credit-bureau-lookup → calculate-score → classify-risk
```

The main loan workflow embeds it as `:loan/credit-assessment` — a single cell
that internally runs the full pipeline. On success it adds `:credit-score` and
`:risk-level` to the data; on failure the parent routes via `:mycelium/error`.

See `workflows.clj`.

### Multi-Way Branching

The `eligibility-decision` cell produces a 3-way dispatch:

```
eligibility-decision
  ├── :approve → auto-approve
  ├── :reject  → auto-reject
  └── :review  → queue-for-review
```

All three branches converge back to `audit-log → send-notification → end`.
Dispatches use predicate functions that check the `:decision` key in the data.

### Per-Transition Output Schemas

Several cells declare different output schemas per dispatch path:

- `validate-application` — `:valid` produces `{:validation-status :valid}`,
  `:invalid` produces `{:validation-status :invalid, :validation-errors [...]}`
- `eligibility-decision` — three schemas, one per branch
- `fetch-application` — `:found` vs `:not-found`

This lets the schema chain validator verify that each downstream cell receives
the exact keys it needs on each specific path through the workflow.

### Cell Reuse Across Workflows

Cells are registered once in the global registry and referenced by ID in
multiple workflows:

- `:loan/audit-log` — used in both the loan application workflow and the
  status check workflow
- `:loan/send-notification` — shared across the approve, reject, and review
  paths within the main workflow (same cell, three different edge convergences)
- `:loan/fetch-application` — used in the status check workflow, reads from
  the same `app-store` atom that the loan workflow writes to

### Schema Chain Validation

At compile time, Mycelium walks all paths from `:start` to `:end` and verifies
that each cell's input keys are available from upstream outputs. Composed cells
automatically infer their output schema from the child workflow's end-reaching
cells, so the chain validator can see the produced keys without manual overrides.

### Early Exit on Validation Failure

The `:invalid` path from `validate-application` routes directly to `:end`,
skipping credit assessment and everything downstream. The integration test
verifies that `:credit-score` and `:notification` are nil on this path.

### Execution Traces

Every workflow run produces a `:mycelium/trace` vector recording which cells
executed, their IDs, and duration. The integration tests verify that:

- The approve path trace contains `validate-application`, `credit-assessment`,
  `eligibility-decision`, `auto-approve`, `audit-log`, `send-notification`
- The reject path trace contains `auto-reject` but not `auto-approve` or
  `queue-for-review`

### Pre-Compilation

Both the loan application and status check workflows are pre-compiled at
namespace load time with `myc/pre-compile`, so repeated calls to
`run-compiled` do zero compilation work per invocation.

### Dev Testing Utilities

Cell unit tests use `dev/test-cell` for isolated single-path testing and
`dev/test-transitions` for multi-path testing in a single call. Both perform
full schema validation (input + output) and dispatch matching. Cells without
dispatch predicates (like `classify-risk`) can also use `test-transitions` for
output-only validation across multiple input scenarios.

## File Structure

```
src/loan/
  cells.clj         12 cell registrations (defmethod cell/cell-spec)
  workflows.clj     3 workflow definitions + composed cell registration

test/loan/
  cells_test.clj    22 unit tests — each cell tested in isolation
  workflows_test.clj 14 integration tests — every path through each workflow
```

## Workflows

### 1. Credit Assessment (subworkflow)

```
credit-bureau-lookup → calculate-score → classify-risk → end
```

Registered as the `:loan/credit-assessment` cell for embedding in other
workflows. Also runnable standalone via `myc/run-workflow`.

### 2. Loan Application (main)

```
validate-application ──┬── (valid) → credit-assessment ──┬── (success) → eligibility-decision
                       │                                  │                ├── (approve) → auto-approve  ──┐
                       │                                  │                ├── (reject)  → auto-reject   ──┤
                       │                                  │                └── (review)  → queue-review  ──┤
                       │                                  │                                                │
                       │                                  └── (failure) → end                              │
                       │                                                                                   │
                       └── (invalid) → end                     audit-log ← ────────────────────────────────┘
                                                                  │
                                                           send-notification → end
```

### 3. Application Status Check

```
fetch-application ──┬── (found)     → format-status → audit-log → end
                    └── (not-found) → end
```

Reuses `:loan/fetch-application` and `:loan/audit-log` from the same cell registry.

## Cells

| Cell | Purpose |
|------|---------|
| `:loan/validate-application` | Check required fields, produce `:valid`/`:invalid` |
| `:loan/credit-bureau-lookup` | Look up credit history from in-memory DB |
| `:loan/calculate-score` | Compute credit score (300-850) from history |
| `:loan/classify-risk` | Map score to `:low`/`:medium`/`:high` |
| `:loan/eligibility-decision` | Route based on risk + amount (3-way) |
| `:loan/auto-approve` | Set approved status + interest rate |
| `:loan/auto-reject` | Set rejected status + reason |
| `:loan/queue-for-review` | Set pending status + queue assignment |
| `:loan/send-notification` | Generate notification from current status |
| `:loan/audit-log` | Append entry to audit trail vector |
| `:loan/fetch-application` | Look up stored application |
| `:loan/format-status` | Format human-readable status report |
