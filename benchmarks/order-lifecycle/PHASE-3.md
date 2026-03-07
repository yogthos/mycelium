# Phase 3: Maximum Complexity

**Assumes Phase 2 is fully implemented and all 30 tests pass.**

Add 7 new subsystems. This phase adds 22 new tests (T31-T44, T46-T53) and
changes expected values for most existing tests due to the new shipping model
and auto-upgrade loyalty feature.

**52 tests total after this phase.**

---

## New Subsystems

### 1. Subscription Pricing

Items can be marked as subscriptions (recurring monthly delivery).

```clojure
{"laptop" {:qty 1 :subscription true}}
{"laptop" {:qty 1 :gift-wrap true :subscription true}}  ;; combinable with other options
```

Rules:
- 15% off base `:price` (before bulk pricing)
- Applied FIRST in the pipeline, before bulk pricing
- Bulk pricing then applies to the subscription-discounted price (not base price)
- Subscription items are EXCLUDED from COMBO75 eligibility check
  - If laptop is subscription but headphones and novel are not, COMBO75 does NOT
    trigger (all three IDs must be present AND non-subscription)
- Subscription items still count toward ELEC10 and BUNDLE5 thresholds
- Subscription items still count toward tiered discount thresholds
- Result adds: `:has-subscription true` if any item is subscription
- Each item in `:items-detail` includes `:subscription true/false`

Return interaction:
- Returning a subscription item adds `:subscription-cancelled true` to result
- Restocking fee still applies based on category and `:final-price`

### 2. Bundle Products

A new catalog entry: composite products that contain sub-items.

**Add to catalog:**

| ID | Name | Category | Price | Weight | Warehouse | Components |
|----|------|----------|-------|--------|-----------|------------|
| gaming-bundle | Gaming Bundle | bundle | 999.00 | 5.3 | west | laptop x1, headphones x1 |

```clojure
{"gaming-bundle" {:name "Gaming Bundle" :category :bundle :price 999.00
                  :weight 5.3 :warehouse "west"
                  :components [["laptop" 1] ["headphones" 1]]}}
```

Rules:
- Bundles expand to a single item with `:category :bundle` (NOT into sub-items)
- Bundle price replaces individual component prices ($999 vs $999.99 + $79.99)
- **Tax**: bundles are taxed at the HIGHEST component category rate
  - Gaming bundle contains electronics -> taxed as electronics (CA: 8.75%)
  - Look up component categories in the catalog to determine the rate
- **COMBO75**: bundles do NOT satisfy combo requirements
  - A gaming-bundle does NOT count as "laptop present" or "headphones present"
- **ELEC10**: bundles do NOT count toward the "2+ electronics" threshold
- **BUNDLE5**: bundles do NOT count toward category thresholds
- **Bulk pricing**: bundles count as their own product (3x gaming-bundle = 5% bulk)
- **Shipping**: bundle ships as one item at its combined weight from its warehouse
- **Gift wrap**: bundles use $4.99 rate (non-books/digital)
- **Subscription**: bundles can be subscriptions (15% off bundle price)
- **Inventory**: decrement COMPONENT inventory (laptop -1, headphones -1), not
  bundle inventory

Return interaction:
- Must return entire bundle (cannot return individual components)
- Restocking: 0% for bundles (special promotional product)
- Defective return: all components' inventory is restored
- `:items-detail` should reflect the bundle as a single item

### 3. Tiered Shipping with Surcharges

**This REPLACES the flat-rate shipping model from Phase 1/2 entirely.**

Weight tiers (per warehouse group):

| Weight Range | Cost |
|-------------|------|
| 0 - 2 lb | $5.99 |
| 2.01 - 10 lb | $8.99 |
| 10.01 - 20 lb | $12.99 |
| 20.01+ lb | $15.99 + $0.25/lb over 20 |

Surcharges:
- **Hazmat**: $3.00 per electronics item (lithium batteries)
  - Bundles count as 1 hazmat item (even if they contain 2 electronics components)
- **Oversized**: $5.00 if any single item in the group weighs > 4.0 lb
  - Once per group, not per item

Free shipping rules (updated):
- Group subtotal >= $75: waives base tier cost ONLY (surcharges remain)
- Gold membership: waives base tier cost ONLY (surcharges remain)
- Platinum membership: waives EVERYTHING (base + all surcharges)
- Digital: no shipping, no surcharges (unchanged)

Result must include `:shipping-groups` map:
```clojure
{"west" {:cost 8.0} "east" {:cost 5.99}}
```

Return shipping refund (defective only, changed-mind = $0):
- Proportional share of base tier cost (same formula as Phase 1/2)
- Full hazmat surcharge refund for each returned electronics item ($3.00 each)
- Proportional share of oversized surcharge (if item was in an oversized group)

### 4. Warranty Add-ons

Per-item optional extended warranty.

| Category | Warranty Cost |
|----------|-------------|
| electronics | $49.99 |
| clothing | $9.99 |
| books | N/A (silently ignored) |
| digital | N/A (silently ignored) |
| bundle | $59.99 |

```clojure
{"laptop" {:qty 1 :warranty true}}
```

Rules:
- Warranty is a separate line item (like gift wrap)
- NOT included in `:discounted-subtotal`, IS included in `:total`
- Taxed as a service: 8% flat rate (same as gift wrap tax rule)
  - OR = 0% (same as gift wrap)
- Does NOT affect loyalty points, discount thresholds, shipping, or promo eligibility
- Result adds: `:warranty-total`, `:warranty-tax`
- Each item in `:items-detail` includes `:warranty true/false`

Return rules (**different from gift wrap**):
- Defective: FULL warranty refund (warranty-refund + warranty-tax-refund)
- Changed-mind: **50%** warranty refund
  - `warranty-refund = round2(warranty_cost * 0.50)`
  - `warranty-tax-refund = round2(warranty_tax * 0.50)`
- Return result adds: `:warranty-refund`, `:warranty-tax-refund`

Updated total refund:
```
total-refund = subtotal-refund + tax-refund + shipping-refund
             + gift-wrap-refund + gift-wrap-tax-refund
             + warranty-refund + warranty-tax-refund
```

### 5. Auto-Upgrade Loyalty Tier

If the current order pushes the customer's lifetime spend past a tier boundary,
upgrade and recompute shipping + loyalty.

- Order input adds: `:lifetime-spend` (default 0, represents pre-order total)
- Check after computing discounted subtotal:
  `lifetime_spend + discounted_subtotal >= next_tier_threshold`
- Tier thresholds: bronze->silver at $500, silver->gold at $2000
- If triggered:
  1. Upgrade the tier
  2. Recompute shipping with new tier's shipping benefits
  3. Recompute loyalty points with new tier's multiplier
  4. Recalculate total (only shipping changes)
- What does NOT change on upgrade: discounts, tax, gift wrap, warranty, payment waterfall
- Only one upgrade per order (no looping)
- Result adds: `:tier-upgraded true`, `:membership :silver` (or `:gold`)
- The check uses `:discounted-subtotal`, NOT `:total`

**Important**: Most Phase 1/2 tests have `lifetime-spend` defaulting to 0.
With subtotals > $500, many orders now auto-upgrade from bronze to silver,
changing loyalty points. This is why so many existing tests change.

### 6. County-Level Tax

Add county surcharges on top of state rates.

Order input adds: `:county` (optional, default nil = state rate only).

**County tax data:**

| State | County | Surcharge | Override Rules |
|-------|--------|-----------|----------------|
| CA | Los Angeles | +2.25% | None |
| CA | San Francisco | +1.25% | Digital exempt from county surcharge |
| NY | New York City | +4.5% | Same exemptions as state |
| NY | Buffalo | +4.0% | Clothing NOT exempt (overrides state exemption) |
| OR | Portland | +0% | Still 0% |
| TX | Houston | +2.0% | Same exemptions as state |
| TX | Austin | +2.0% | Digital NOT exempt (overrides state exemption) |

Exemption override logic:
1. If state exempts item AND county doesn't override -> rate = 0%
2. If county overrides a state exemption -> full combined rate applies
   (both state base + county surcharge)
3. If county adds its own exemption -> county surcharge = 0%, state rate
   still applies

Examples:
- Laptop in CA/LA: 7.25% + 1.5% (state electronics surcharge) + 2.25% (county) = 11.0%
- Shirt in NY/Buffalo: state normally exempts clothing < $110, but Buffalo
  overrides -> 8.875% + 4.0% = 12.875%
- E-book in TX/Austin: state normally exempts digital, but Austin overrides ->
  6.25% + 2.0% = 8.25%
- E-book in CA/SF: state rate 7.25%, SF exempts digital from county surcharge ->
  7.25% + 0% = 7.25%

County surcharges apply to PRODUCTS only. Service taxes (gift wrap, warranty)
always use the flat 8% rate regardless of county.

### 7. Partial Fulfillment / Backorders

Items can be out of stock. Partially available items split into fulfilled and
backordered portions.

Rules:
- During inventory reservation, check available stock
- If `available < requested`: split into fulfilled (available qty) + backordered
  (remainder)
- **Discounts**: apply to FULL order (both fulfilled and backordered)
- **Tax**: apply to FULL order
- **Shipping**: ONLY for fulfilled items (backordered ship later)
- **Gift wrap / Warranty**: apply to ALL items (charged immediately)
- **Payment**: charge fulfilled portion, authorize backorder portion
  - `fulfilled-charge = fulfilled_subtotal + tax_on_fulfilled + shipping + gift_wrap + warranty`
  - `backorder-hold = backordered_subtotal + tax_on_backordered`
- **Fraud check**: on FULL order total
- **Loyalty points**: based on FULL order subtotal

Result adds:
```clojure
:fulfillment {:status :partial  ;; or :full if everything in stock
              :fulfilled-items [...]
              :backordered-items [...]
              :fulfilled-subtotal X
              :backordered-subtotal Y
              :fulfilled-charge Z
              :backorder-hold W}
```

Return rules:
- Can ONLY return fulfilled items (not backordered)
- Attempting to return a backordered item -> $0 refund (empty return)
- Backordered items can be cancelled separately (not via the return workflow)

---

## Updated Item Format

All option flags are combinable:
```clojure
{"laptop" {:qty 1 :subscription true :warranty true :gift-wrap true}}
{"gaming-bundle" 1}   ;; simple format still works
```

---

## Updated Promotion Pipeline

1. Expand items (handle subscription, bundle, gift-wrap, warranty flags)
2. **Subscription pricing** (15% off base for subscription items)
3. Bulk pricing (on subscription-adjusted prices)
4. COMBO75 (exclude subscription items and bundles from eligibility)
5. ELEC10 (exclude bundles from count)
6. BUNDLE5 (exclude bundles from count)
7. Order-level %
8. Fixed coupon
9. Loyalty redemption
10. Compute discounted subtotal
11. **[Parallel]** Tax (with county), Shipping (tiered), Gift wrap, Warranty
12. Total
13. **Auto-upgrade** (may recompute shipping + loyalty)
14. Fraud check
15. Inventory (partial fulfillment split)
16. Payment (3-way waterfall, split fulfilled/backorder)
17. Loyalty points (with possibly-upgraded tier multiplier)
18. Finalize (currency conversion)

---

## Updated API

Order input adds:
```clojure
:county        nil    ;; optional, e.g. "Los Angeles"
:lifetime-spend 0     ;; pre-order lifetime spend for tier upgrade check
```

---

## Changes to Existing Tests

The tiered shipping model and auto-upgrade loyalty change expected values for
most existing tests. Below are ALL changes from Phase 2 values.

**Root causes of changes:**
1. **Shipping**: flat-rate replaced by tiered + surcharges (electronics get hazmat
   $3/item, items > 4lb get oversized $5/group)
2. **Auto-upgrade**: orders with subtotal >= $500 (and default lifetime-spend=0)
   auto-upgrade bronze->silver, earning 1.5x loyalty points

### Updated Placement Tests

**T1** (auto-upgrade triggers: 0+949.99 >= 500 -> silver):
```
shipping: 0.0 -> 8.0         (hazmat $3 + oversized $5, base waived by subtotal>=$75)
total: 1033.11 -> 1041.11
points: 949 -> 1424           (silver 1.5x)
NEW: tier-upgraded=true membership=:silver
```

**T2** (auto-upgrade -> silver):
```
shipping: 0.0 -> 11.0        (hazmat 2x$3 + oversized $5)
total: 1004.17 -> 1015.17
points: 923 -> 1385
```

**T3** (no auto-upgrade, subtotal 90.23 < 500):
```
shipping: 6.39 -> 8.99       (west: hazmat $3, base waived subtotal>=$75; east: $5.99 base)
total: 104.30 -> 106.90
```

**T4** (auto-upgrade -> silver):
```
shipping: 6.39 -> 16.99      (west: $3+$5=8; east: $5.99+hazmat... see T38 for breakdown)
total: 908.53 -> 919.13
points: 829 -> 1244
```

**T5** (no auto-upgrade):
```
shipping: 6.64 -> 5.99       (east only, 1.3lb, 0-2lb tier)
total: 47.12 -> 46.47
```

**T6** (already gold):
```
shipping: 0.0 -> 8.0         (gold waives base, surcharges remain: hazmat $3 + oversized $5)
total: 924.99 -> 932.99
```

**T7** (auto-upgrade -> silver):
```
shipping: 6.24 -> 13.99      (west: $8, east: $5.99)
total: 1013.93 -> 1021.68
cc-charged: 813.93 -> 821.68
points: 926 -> 1390
```

**T8** (auto-upgrade -> silver):
```
shipping: 6.64 -> 13.99
total: 966.36 -> 973.71
points: 903 -> 1355
```

**T9**: unchanged (error case)
**T10**: unchanged (error case)

### Updated Return Tests

**T11** (T1 order now has shipping):
```
ship-refund: 0.0 -> 8.0
total-refund: 1033.11 -> 1041.11
clawback: 949 -> 1424
cc-refund: 1033.11 -> 1041.11
```

**T12** (clawback changes due to auto-upgrade on placement):
```
clawback: 51 -> 77
(all other values unchanged from Phase 2)
```

**T13** (clawback changes):
```
clawback: 24 -> 36
(all other values unchanged from Phase 2)
```

### Updated Modification Tests

**T14**:
```
new-shipping: 0.0 -> 11.0
new-total: 1761.72 -> 1772.72
delta: 728.61 -> 731.61
new-points: 1619 -> 2429
points-delta: 670 -> 1005
```

**T15**:
```
new-shipping: 0.0 -> 11.0
new-total: 1004.17 -> 1015.17
delta: 95.64 -> 96.04
new-points: 923 -> 1385
points-delta: 94 -> 141
```

### Updated COMBO75 Tests

**T16**:
```
shipping: 6.39 -> 16.99
total: 908.53 -> 919.13
points: 829 -> 1244
```

**T17** (defective return now has shipping refund):
```
ship-refund: 0.0 -> 3.37
total-refund: 65.82 -> 69.19
clawback: 60 -> 90
cc-refund: 65.82 -> 69.19
```

**T18**:
```
new-shipping: 6.39 -> 13.99
new-total: 1002.36 -> 1009.96
delta: 93.83 -> 90.83
new-points: 916 -> 1374
points-delta: 87 -> 130
```

### Updated V2 Tests

**T19** (EUR, shipping changes):
```
shipping: 0.0 -> 11.0
total: 1004.17 -> 1015.17
display-shipping: 0.0 -> 10.12
display-total: 923.84 -> 933.96
points: 923 -> 1385
```

**T20** (gift wrap, shipping changes):
```
shipping: 6.24 -> 13.99
total: 1019.32 -> 1027.07
```

**T21**: unchanged (gold + OR = $0 shipping in both models)

**T22** (total changes -> CC charge changes):
```
total: 1013.93 -> 1021.68
cc-charged: 513.93 -> 521.68
```

**T23** (clawback changes due to auto-upgrade):
```
clawback: 58 -> 87
```

**T24** (defective return, now has shipping refund):
```
ship-refund: 0.0 -> 8.0
total-refund: 1038.50 -> 1046.50
```

**T25**: unchanged (changed-mind, $0 shipping refund)

**T26** (3x laptop, shipping + auto-upgrade to gold):
```
shipping: 0.0 -> 14.0        (hazmat 3x$3=$9 + oversized $5)
total: 2510.46 -> 2524.46
points: 2308 -> 3462         (gold 2.0x: auto-upgrade bronze->gold at $2000)
```

**T27** (bulk+COMBO75):
```
shipping: 6.39 -> 22.99
total: 2409.22 -> 2425.82
points: 2209 -> 3314
```

**T28** (EUR defective return, shipping refund changes):
```
ship-refund: 0.0 -> 3.37
total-refund: 74.37 -> 77.74
display-total-refund: 68.42 -> 71.52
```

**T29**: unchanged (changed-mind, $0 shipping refund)

**T30** (bulk modification):
```
new-shipping: 0.0 -> 11.0
new-total: 1761.72 -> 1772.72
delta: 2202.18 -> 2211.18
new-points: 1619 -> 2429
points-delta: 2025 -> 3038
```

---

## New Test Cases

### Subscription Pricing

**T31**: 1x laptop(subscription) | CA | bronze | card 4xxx
15% off $999.99 = $849.99. Tiered 5%: $807.49.
```
subtotal=807.49 tax=70.66 shipping=8.0 total=886.15
  points=1211 has-subscription=true
```

**T32**: 1x laptop(subscription) + 1x headphones + 1x novel | CA | bronze | card 4xxx
COMBO75 must NOT trigger (laptop is subscription).
ELEC10 triggers (2 electronics). BUNDLE5 triggers (electronics + books).
```
subtotal=768.90 tax=67.07 shipping=16.99 total=852.96
  points=1153 has-subscription=true laptop.final-price=690.40
```

### Bundle Products

**T33**: 1x gaming-bundle | CA | bronze | card 4xxx
Taxed as electronics (8.75%). Component inventory decremented.
Tiered 5%: round2(999.00 * 0.05) = 49.95 -> 949.05.
```
subtotal=949.05 tax=83.04 shipping=8.0 total=1040.09
  points=1423 inv:laptop=99 inv:headphones=49
```

**T34**: 1x gaming-bundle + 1x novel | CA | bronze | card 4xxx
Bundle does NOT satisfy COMBO75, ELEC10, or BUNDLE5.
```
subtotal=912.59 tax=79.65 shipping=13.99 total=1006.23 points=1368
```

### County-Level Tax

**T35**: 1x laptop | CA | county "Los Angeles" | bronze | card 4xxx
Tax rate: 7.25% + 1.5% + 2.25% = 11.0%.
```
subtotal=949.99 tax=104.50 shipping=8.0 total=1062.49
```

**T36**: 1x shirt | NY | county "Buffalo" | bronze | card 4xxx
Buffalo overrides NY clothing exemption. Rate: 8.875% + 4.0% = 12.875%.
```
subtotal=29.99 tax=3.86 shipping=5.99 total=39.84
```

**T37**: 1x ebook | TX | county "Austin" | bronze | card 4xxx
Austin overrides TX digital exemption. Rate: 6.25% + 2.0% = 8.25%.
```
subtotal=9.99 tax=0.82 shipping=0.0 total=10.81
```

### Tiered Shipping

**T38**: 1x laptop + 1x shirt + 1x novel | CA | bronze | card 4xxx
West: laptop 5.0lb -> base free (subtotal>$75), hazmat $3, oversized $5 = $8.
East: shirt+novel 1.3lb -> 0-2lb tier $5.99 (subtotal<$75) = $5.99.
```
shipping=13.99 shipping-groups: west=8.0 east=5.99
```

**T39**: 1x laptop + 1x headphones | CA | gold | card 4xxx
Gold waives base tier, surcharges remain: hazmat 2x$3 + oversized $5 = $11.
```
shipping=11.0 subtotal=923.38 points=1846
```

**T40**: 1x laptop + 1x headphones | CA | platinum | card 4xxx
Platinum waives everything including surcharges.
```
shipping=0.0 total=1004.17
```

### Warranty

**T41**: 1x laptop(warranty) | CA | bronze | card 4xxx
Warranty $49.99 + 8% tax = $4.00. Not in subtotal, in total.
```
subtotal=949.99 tax=83.12 warranty-total=49.99 warranty-tax=4.0
  total=1095.10
```

**T42**: Place T41-order, return laptop, defective
Full warranty refund.
```
sub-refund=949.99 tax-refund=83.12 warranty-refund=49.99
  warranty-tax-refund=4.0 total-refund=1095.10
```

**T43**: Place T41-order, return laptop, changed-mind
50% warranty refund (different from gift wrap's $0).
```
restocking=142.50 sub-refund=807.49 warranty-refund=25.0
  warranty-tax-refund=2.0 total-refund=917.61
```

### Auto-Upgrade Loyalty Tier

**T44**: 1x laptop | CA | bronze | lifetime-spend=0 | card 4xxx
Subtotal $949.99 >= $500 -> upgrade bronze to silver. Points at 1.5x.
```
tier-upgraded=true membership=:silver points=1424
```

### Partial Fulfillment

**T46**: 1x laptop + 1x headphones | CA | bronze | card 4xxx
Custom inventory: laptop=0, headphones=50.
Laptop backordered, headphones fulfilled.
```
fulfillment-status=:partial fulfilled-count=1 backordered-count=1
  fulfilled-charge=83.36 backorder-hold=929.80
  shipping=8.99 inv:laptop=0 inv:headphones=49
```

**T47**: From T46, attempt two returns:
1. Return laptop (backordered) -> $0 refund (can't return backordered items)
2. Return headphones (fulfilled, defective) -> normal refund
```
laptop-return: total-refund=0.0
headphones-return: sub-refund=68.39 tax-refund=5.98 ship-refund=3.44
  total-refund=77.81
```

### Cross-Feature: Subscription + Warranty + Gift Wrap

**T48**: 1x laptop(subscription, warranty, gift-wrap) | CA | bronze | card 4xxx
All three add-ons on one subscription item.
```
subtotal=807.49 tax=70.66 gift-wrap-total=4.99 gift-wrap-tax=0.40
  warranty-total=49.99 warranty-tax=4.0 total=945.53 has-subscription=true
```

**T49**: Place T48-order, return laptop, defective
Full product + warranty + gift wrap refund. Subscription cancelled.
```
sub-refund=807.49 tax-refund=70.66 ship-refund=8.0
  gift-wrap-refund=4.99 gift-wrap-tax-refund=0.40
  warranty-refund=49.99 warranty-tax-refund=4.0
  total-refund=945.53 subscription-cancelled=true
```

**T50**: Place T48-order, return laptop, changed-mind
Restocking 15%, $0 gift wrap, 50% warranty. Subscription cancelled.
```
restocking=121.12 sub-refund=686.37
  gift-wrap-refund=0.0 warranty-refund=25.0 warranty-tax-refund=2.0
  total-refund=784.03 subscription-cancelled=true
```

### Cross-Feature: County Tax + Currency + Gift Wrap + Warranty

**T51**: 1x laptop(gift-wrap, warranty) | CA | county "Los Angeles" | EUR | card 4xxx
County tax on products (11%), but service tax stays flat 8%.
```
subtotal=949.99 tax=104.50 gift-wrap-total=4.99 gift-wrap-tax=0.40
  warranty-total=49.99 warranty-tax=4.0 total=1121.87
  display-subtotal=873.99 display-tax=96.14 display-total=1032.12
```

### Bundle Return

**T52**: Place gaming-bundle order, return defective
0% restocking (bundle category). Component inventory restored.
```
restocking=0.0 sub-refund=949.05 tax-refund=83.04 total-refund=1040.09
  inv:laptop=100 inv:headphones=50
```

### Subscription Modification

**T53**: Place 3x laptop(subscription), modify to 1x laptop(subscription)
Loses bulk + ELEC10 + tiered. Large delta.
```
new-subtotal=807.49 new-tax=70.66 new-shipping=8.0 new-total=886.15
  delta=1261.74 action=:refund new-points=1211
```

---

## Acceptance Criteria

- All 52 tests pass (T1-T44, T46-T53)
- All 7 new subsystems working correctly
- Tiered shipping fully replaces flat-rate shipping
- Auto-upgrade correctly recomputes ONLY shipping + loyalty (not discounts/tax)
- County tax overrides work correctly (Buffalo clothing, Austin digital)
- County surcharges apply to products only, not service taxes
- Warranty changed-mind = 50% refund (different from gift wrap's $0)
- Warranty defective = full refund (same as gift wrap)
- Partial fulfillment splits items, charges fulfilled portion, authorizes backorder
- Backordered items cannot be returned
- Bundle products are opaque for all promotion checks (COMBO75/ELEC10/BUNDLE5)
- Bundle inventory operates at component level
- Subscription items excluded from COMBO75 but included in ELEC10/BUNDLE5
- Subscription discount applied before bulk pricing (sequential, not independent)
