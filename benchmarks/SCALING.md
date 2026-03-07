# Mycelium Benchmark Scaling Analysis

How schema-enforced cells change the reliability equation as system complexity grows.

## The Four Benchmarks

We ran four progressively complex benchmarks, each building on the previous one.
The traditional approach achieved 100% test passage through V1 and V2, but the
V1 shipping bug finally surfaced in V3 — causing 17 test failures after being
latent for two full rounds of development.

| Benchmark | Subsystems | Tests | Traditional | Mycelium | Traditional LOC | Mycelium LOC |
|-----------|-----------|-------|-------------|----------|----------------|-------------|
| Checkout Pipeline | 3 | 8 / 39 assertions | 39/39 pass | 39/39 pass | ~130 | ~230 + manifest |
| Order Lifecycle V1 | 6 | 18 / 136 assertions | 136/136 pass | 136/136 pass | ~540 | ~590 + ~130 manifest |
| Order Lifecycle V2 | 11 | 30 / 235 assertions | 235/235 pass | 235/235 pass | ~722 | ~900 + ~360 manifest |
| Order Lifecycle V3 | 15 | 52 / 383 assertions | **366/383 pass** | 383/383 pass | ~920 | ~1146 + ~440 manifest |

---

## Benchmark 1: Checkout Pipeline

**Scale**: ~130 lines, 3 subsystems (discounts, tax/shipping, payment).

**What it tests**: A single linear pipeline -- items in, total out.

### Traditional Approach
- 37/39 assertions passed on first attempt
- 1 bug: floating-point rounding in tax calculation (`50.0 * 0.0725 = 3.6249...` rounds to 3.62 instead of 3.63)
- Fixed in 1 iteration

### Mycelium Approach
- 39/39 assertions passed on first logic execution (zero logic bugs)
- 4 iterations of compiler-guided structural fixes before first run:
  missing `:on-error`, undeclared data flow keys, dead-end graph route
- Each fix was guided by a clear error message

### Verdict at This Scale

Both approaches work fine. The problem is small enough that an AI agent can hold
the entire system in context. The traditional approach is simpler and faster to
implement. Mycelium's overhead (~100 extra lines + manifest) is proportionally
high (~75%) and hard to justify for a problem this small.

**Mycelium advantage**: The structural validators caught 3 issues that would have
been silent in the traditional approach (missing error handling, undeclared data
flow, dead-end route). But at this scale, these issues are easy to catch through
testing or code review.

**Latent bugs**: Traditional 0, Mycelium 0.

---

## Benchmark 2: Order Lifecycle V1

**Scale**: ~540 lines, 6 subsystems (item expansion, promotions with 5 stacking
types, per-item tax with state exemptions, multi-warehouse shipping, split payment,
loyalty points with tiered earning).

**What it tests**: Three interacting workflows (placement, returns, modification)
that share data contracts. Returns must correctly reverse the forward calculation,
including proportional discount distribution, per-item tax, and split payment
reversal.

### How It Was Built

The traditional approach was built by 4 separate AI subagents:
1. Agent 1: Order placement (~342 lines)
2. Agent 2: Returns processing (~136 lines) -- given spec + tests, no placement source
3. Agent 3: Order modification (~58 lines) -- given spec + tests, no other source
4. Agent 4: Added COMBO75 feature by modifying code it didn't write

### Traditional Approach
- 18/18 tests passing, 136 assertions
- **2 latent bugs discovered**:
  1. **`:shipping-detail` vs `:shipping-groups`** -- Returns code destructures
     `:shipping-detail` but placement outputs `:shipping-groups`. Gets `nil`,
     silently produces $0 shipping refund for all defective returns
  2. **Double inventory reservation** -- `modify-order` calls `place-order` which
     re-reserves inventory without releasing the original reservation

### Mycelium Approach
- 18/18 tests passing on first attempt, 136 assertions
- **0 latent bugs**
- Returns manifest explicitly requires `:shipping-groups :any` in its input schema,
  making the key mismatch impossible
- Modification workflow uses the same placement workflow, so inventory semantics
  are consistent

### Verdict at This Scale

This is the tipping point. The traditional approach has crossed the threshold where
implicit contracts between components fail silently. Two independently competent
AI agents (placement and returns) produced code that connects incorrectly through
a key name mismatch. All tests pass because no test exercises the specific path
(defective return of an item with non-zero shipping cost).

**The key insight**: The bug is not in any single agent's work. Each agent's code
is internally correct. The bug is in the *contract between* agents -- a contract
that exists only implicitly in the traditional approach and explicitly in the
mycelium manifest.

**Latent bugs**: Traditional 2, Mycelium 0.

---

## Benchmark 3: Order Lifecycle V2

**Scale**: ~722 lines, 11 subsystems. Five new features added by 5 sequential
AI subagents, each modifying the existing V1 codebase:
1. Bulk pricing (quantity discounts before all other promos)
2. Store credit (third payment method in 3-way waterfall)
3. Gift wrapping (per-item, separate tax rate, refund rules)
4. Restocking fees (category-dependent on changed-mind returns)
5. Multi-currency (display-time conversion with separate display fields)

### Traditional Approach
- 30/30 tests passing, 241 assertions
- **4 latent bugs** (2 carried forward from V1 + 2 new):
  1. `:shipping-detail` vs `:shipping-groups` -- **still present** after 5 more agents touched the code
  2. Double inventory reservation -- **still present**
  3. `currency-rates` duplicated in 3 files (placement, returns, modification)
  4. `gift-wrap-cost-per-item` duplicated in 2 files (placement, returns)

### Mycelium Approach
- 30/30 tests passing on first attempt, 235 assertions
- **0 latent bugs**
- 3-way parallel join for tax + shipping + gift-wrap declared in manifest
- Each new feature maps to 1-2 new cells with explicit schemas

### The Shipping Bug: Experimentally Confirmed

We ran a targeted scenario to prove the traditional bug produces wrong results:

```
Scenario: Return novel (defective) from headphones+novel order
East warehouse shipping cost: $6.39

Traditional shipping-refund: $0.00  (WRONG)
Mycelium shipping-refund:    $6.39  (CORRECT)
```

The traditional approach silently loses $6.39 per affected refund. This is not
a theoretical concern -- it's a financial calculation error that would affect
real transactions.

### V1 Bugs Persist Through V2 Development

The most striking finding: **5 additional AI agents worked on the traditional
codebase and none detected or fixed the V1 bugs**. Each agent:
- Read the existing source code
- Added their feature successfully
- Made their tests pass
- Left the existing bugs untouched

This happens because each agent only reads enough context to complete its assigned
task. The `:shipping-detail` bug is invisible unless you specifically compare the
returns code's destructuring against the placement code's output keys -- a
cross-file analysis that agents skip when focused on adding a feature.

**Latent bugs**: Traditional 4, Mycelium 0.

### Verdict at This Scale

The overhead ratio has shifted. Mycelium's manifest and structural code is ~360
lines on top of ~900 lines of implementation -- about 40% overhead. But the value
delivered has grown faster:

- Schema validation prevents an entire class of cross-module key mismatches
- Manifest serves as machine-readable architecture documentation
- Parallel joins are declared, not manually implemented
- Each cell can be implemented with only its schema as context
- New features map to isolated cells without touching existing cell logic

---

## Benchmark 4: Order Lifecycle V3

**Scale**: ~920 lines traditional, ~1146 lines mycelium, 15 subsystems. Seven new
cross-cutting features designed to create maximum interaction pressure:

1. **Subscription pricing** -- 15% off base price, excludes COMBO75 eligibility
2. **Bundle products** -- composite items, opaque for promotions, component-level inventory
3. **Tiered shipping** -- weight tiers + $3/hazmat + $5/oversized, differentiated free-shipping rules
4. **Warranty add-ons** -- per-category cost, 8% service tax, 50% refund on changed-mind (vs gift wrap's $0)
5. **Auto-upgrade loyalty tier** -- bronze->silver at $500, recomputes shipping and loyalty only
6. **County-level tax** -- surcharges with exemption overrides (Buffalo overrides clothing, Austin overrides digital)
7. **Partial fulfillment** -- fulfilled/backordered split, shipping only for fulfilled items

These 7 features create a 48-cell interaction matrix of cross-cutting concerns.

### Traditional Approach
- **35/52 tests passing, 366/383 assertions**
- **17 test failures** -- all from the V1 `:shipping-detail` bug
- 5 latent bugs (2 from V1, 3 new duplications)

### Mycelium Approach
- **52/52 tests passing, 383/383 assertions** -- first attempt
- **0 latent bugs**
- 4-way parallel join for tax + shipping + gift-wrap + warranty
- 18 placement cells, 10 returns cells, all schema-bounded

### The V1 Bug Finally Explodes

The `:shipping-detail` vs `:shipping-groups` bug was introduced in V1, survived
V2 untouched, and **finally causes test failures in V3**. Here's why:

In V1 and V2, shipping was simple: flat rate or free (gold/platinum, subtotal >= $75).
Most tested scenarios had free shipping, so the returns code's nil shipping-detail
produced the accidentally-correct $0.00 refund. V3's tiered shipping with hazmat
and oversized surcharges means most orders now have non-zero shipping costs.
Defective returns must refund that shipping, and the nil lookup silently returns $0.

**One root cause, 17 failures.** The single wrong key name cascades:
- shipping-refund: $0 instead of $3-8 (8 tests)
- total-refund: wrong by the missing shipping amount (8 tests)
- payment-refund / display amounts: cascading errors (1 test)

| Test | Expected | Actual | Missing |
|------|----------|--------|---------|
| T11 (laptop defective) | $1041.11 total refund | $1033.11 | -$8.00 |
| T17 (headphones defective) | $69.19 | $65.82 | -$3.37 |
| T24 (gift-wrapped laptop) | $1046.50 | $1038.50 | -$8.00 |
| T42 (warranty laptop) | $1095.10 | $1087.10 | -$8.00 |
| T49 (sub+warranty+wrap) | $945.53 | $937.53 | -$8.00 |
| T52 (bundle return) | $1040.09 | $1032.09 | -$8.00 |

In a production system, every defective return would silently under-refund
the customer's shipping cost.

### All New Features Implemented Correctly

The traditional approach correctly implements all 7 V3 features for placement:
- Subscription pricing properly excludes COMBO75 (T32 passes)
- Bundles don't trigger ELEC10/BUNDLE5/COMBO75 (T33-34 pass)
- County tax overrides work correctly (T35-37 pass)
- Tiered shipping computes correctly (T38-40 pass)
- Warranty with 50% changed-mind refund (T43 passes)
- Auto-upgrade triggers correctly (T44 passes)
- Partial fulfillment splits correctly (T46-47 placement assertions pass)

The failures are not from incompetence. They're from the structural impossibility
of tracking key names across module boundaries without a contract system.

### Verdict at This Scale

The V2 analysis predicted 8-12 latent bugs at 15 subsystems. The reality was
more interesting: rather than just accumulating more latent bugs, the existing
V1 bug **detonated**. V3's tiered shipping created enough non-zero shipping
scenarios that the `:shipping-detail` bug went from latent to catastrophic.

This is the **time-bomb pattern**: a bug introduced in round 1 of development
explodes in round 3, when new features create paths through the code that
previous tests never exercised. The codebase grew from 540 to 920 lines, 12
features were added across 2 rounds, and the bug was invisible to every test
suite, code review, and AI agent that touched the code -- until it wasn't.

---

## The Scaling Curve

### Bug Growth

| Benchmark | Subsystems | Traditional Bugs | Test Failures | Mycelium Bugs |
|-----------|-----------|-----------------|---------------|--------------|
| Checkout | 3 | 0 | 0 | 0 |
| V1 | 6 | 2 latent | 0 | 0 |
| V2 | 11 | 4 latent | 0 | 0 |
| V3 | 15 | 5 (1 surfaced) | **17** | 0 |

The traditional approach doesn't just accumulate bugs linearly -- the bugs
interact with new features to create cascading failures. A latent bug that
was harmless at 6 subsystems becomes a 17-assertion catastrophe at 15
subsystems because the new features create triggering conditions.

Mycelium stays at zero because every cross-component contract is explicit in
the manifest. A bug like `:shipping-detail` vs `:shipping-groups` cannot exist
when the manifest declares exactly which keys flow between cells.

### Overhead Amortization

| Benchmark | Traditional LOC | Mycelium Total LOC | Overhead % | Bugs Prevented |
|-----------|----------------|-------------------|------------|----------------|
| Checkout | 130 | 230 | 77% | 0 |
| V1 | 540 | 720 | 33% | 2 |
| V2 | 722 | 1260 | 74%* | 4 |
| V3 | 920 | 1586 | 72% | 5 + 17 test failures |

*Overhead percentage stabilizes around 70-75% at scale, but the value delivered
grows superlinearly. At V3 scale, ~440 lines of manifest prevent 5 latent bugs
and 17 test failures that would silently produce wrong financial calculations.

The overhead percentage isn't the right metric. The right metric is **bugs
prevented per line of manifest**. At V3 scale, the manifest prevented the
single bug that cascaded into 17 assertion failures across 8 test cases.

### Context Requirements

| Benchmark | Lines per module | Can agent hold full context? | Result |
|-----------|-----------------|---------------------------|--------|
| Checkout | 130 (1 file) | Yes | Both approaches work |
| V1 | 180 avg (3 files) | Mostly | Traditional: 2 cross-file bugs |
| V2 | 240 avg (3 files) | Strained | Traditional: 4 bugs, 0 fixed |
| V3 | 307 avg (3 files) | **No** | Traditional: 17 test failures |

The traditional approach degrades because agents must hold the full system in
context to avoid cross-module mismatches. As the system grows, the agent's
effective context becomes a shrinking fraction of the total codebase.

Mycelium cells are independently implementable. An agent implementing
`:return/calc-restocking` needs only:
- Input schema: `[:returned-detail, :reason]`
- Output schema: `[:restocking-fee]`
- Business rules for restocking fee calculation

It does not need to read placement code, other returns cells, or understand the
full data flow. The schema is the complete specification for that unit of work.

---

## Why Tests Don't Catch These Bugs

All benchmarks through V2 achieve 100% test passage in both approaches. The
latent bugs survive because:

1. **Tests verify expected behavior, not contract compliance.** A test for "return
   headphones, changed-mind" checks the refund amount. It doesn't check that the
   returns code reads the correct key from the placement output.

2. **Test scenarios have accidental coverage gaps.** Every defective-return test
   in V1 and V2 happens to involve items with free shipping (>$75 warehouse
   subtotal). No test returns a defective item where shipping was actually charged.

3. **Agents write tests that match their implementation.** When Agent 2 writes
   returns code that uses `:shipping-detail`, it also writes tests that don't
   exercise the `:shipping-detail` code path with non-zero shipping. The bug and
   the test gap are correlated because they come from the same incomplete
   understanding.

4. **Passing tests create false confidence.** After 30 tests pass, the natural
   conclusion is "the code is correct." The latent bugs only appear when you
   specifically probe for them with targeted scenarios.

5. **New features can detonate old bugs.** V3 proves that 100% test passage is
   a snapshot in time. V1's bug was harmless through V2 and catastrophic in V3.
   The bug didn't change -- the surrounding code did.

Schema validation is orthogonal to testing. It doesn't check business logic
correctness -- tests do that. It checks structural correctness: "does the data
flowing between components have the right shape?" This is precisely the category
of bug that tests miss and that grows with system complexity.

---

## Manifests as Persistent Context

Beyond preventing bugs, mycelium manifests address a practical problem in
AI-assisted development: **context compaction**.

When an AI agent's conversation grows too long, earlier context gets compressed
or dropped. The agent must rebuild its understanding by re-reading source files.
In a traditional codebase, this means re-tracing data flow through hundreds of
lines of imperative code to reconstruct which keys connect which modules, what
the data shapes are, and how subsystems interact.

Mycelium manifests externalize this knowledge:

1. **The manifest is a persistent context map.** Reading `placement.edn` (~100
   lines) gives the full DAG, every cell's input/output schema, and all
   dependencies. That's the entire system architecture in one structured file.
   In the traditional approach, reconstructing the same understanding requires
   reading 920 lines across 3 files.

2. **Schemas are contracts that survive compaction.** The `:shipping-groups` key
   name exists only in the agent's working memory in the traditional approach.
   Once that memory is compacted, the contract is lost. In mycelium, it's
   written in the manifest and survives any number of compactions.

3. **Bounded context means less to rebuild.** After compaction, an agent working
   on `:return/calc-warranty-refund` reads the cell schema (5 lines) and knows
   exactly what it receives and must produce. It doesn't need to re-read
   placement code, shipping code, or understand the full pipeline.

4. **The 48-cell interaction matrix is externalized.** V3's 15 subsystems create
   48 cross-cutting interactions. No agent can hold all 48 in context
   simultaneously. Mycelium doesn't require it -- each cell only needs its own
   schema boundary. The manifest encodes the global structure so the agent
   doesn't have to.

Context compaction puts the agent in the same position as a new developer
joining the project. Mycelium manifests serve as onboarding documentation that
is also executable contracts. The traditional approach has no equivalent.

---

## The Fundamental Asymmetry

Traditional codebases require **global knowledge** to avoid cross-module bugs.
As the system grows, maintaining global knowledge becomes impossible for any
single agent (or human). The V3 benchmark proves this: 15 subsystems and 48
interactions exceed what any agent can hold in context, and the result is a
V1 bug that explodes into 17 test failures.

Mycelium requires only **local knowledge** (the cell's schema) to implement
each component correctly. As the system grows, local knowledge stays constant
per cell. The manifests encode the global structure, and the framework validates
it automatically.

This is the same asymmetry that makes type systems valuable in large codebases:
not because they help with small programs, but because they prevent the class of
errors that grows fastest with system size.

The V3 results confirm the projection from V2: as complexity grows, the
traditional approach's bug surface area grows combinatorially while mycelium's
stays at zero. The only question was whether those bugs would remain latent or
surface -- and V3 answered definitively.
