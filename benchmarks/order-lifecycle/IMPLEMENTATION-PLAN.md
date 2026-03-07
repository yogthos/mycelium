# Order Lifecycle -- Agent Implementation Plan

Build an order lifecycle system in Clojure across three progressive phases. Each
phase adds features on top of the previous one. The system has three workflows:
**order placement**, **return processing**, and **order modification**.

Implement all three phases sequentially. Each phase's test suite must pass before
moving to the next. Use `round2` (HALF_UP to 2 decimal places) for all monetary
calculations. Use `floor` for loyalty point calculations.

```clojure
(defn round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))
```

---

## Shared Fixtures

### Product Catalog

| ID | Name | Category | Price | Weight | Warehouse |
|----|------|----------|-------|--------|-----------|
| laptop | Laptop | electronics | 999.99 | 5.0 | west |
| shirt | T-Shirt | clothing | 29.99 | 0.5 | east |
| novel | Novel | books | 14.99 | 0.8 | east |
| headphones | Headphones | electronics | 79.99 | 0.3 | west |
| ebook | E-Book | digital | 9.99 | 0.0 | digital |

Phase 3 adds:

| ID | Name | Category | Price | Weight | Warehouse | Components |
|----|------|----------|-------|--------|-----------|------------|
| gaming-bundle | Gaming Bundle | bundle | 999.00 | 5.3 | west | laptop x1, headphones x1 |

### Coupons

| Code | Type | Value | Min Order |
|------|------|-------|-----------|
| SAVE10 | percentage | 10% | none |
| SAVE20 | percentage | 20% | $100 |
| FLAT15 | fixed | $15 | none |

### Tax Rates (by state)

| State | Base Rate | Special Rules |
|-------|-----------|---------------|
| CA | 7.25% | Electronics surcharge +1.5% (total 8.75%) |
| NY | 8.875% | Clothing exempt if item price < $110; Books exempt |
| OR | 0% | Everything exempt |
| TX | 6.25% | Digital exempt |

### Default Inventory

```clojure
{"laptop" 100 "headphones" 50 "shirt" 200 "novel" 150 "ebook" 999}
```

### Loyalty Tiers

| Lifetime Spend | Tier | Point Multiplier |
|---------------|------|-----------------|
| $0 - $499 | bronze | 1.0x |
| $500 - $1999 | silver | 1.5x |
| $2000+ | gold | 2.0x |

Points earned = `floor(discounted_subtotal * tier_multiplier)`.

---

## Phase 1: Core Order Lifecycle

**18 tests, 3 workflows, 6 subsystems.**

Implement `place-order`, `process-return`, and `modify-order`.

### Item Format

Simple quantity map:
```clojure
[{"laptop" 1} {"shirt" 2}]
```

### Promotion Pipeline (applied in order, all stackable)

1. **COMBO75** -- If product IDs "laptop", "headphones", AND "novel" are ALL
   present, apply $75 flat discount distributed proportionally by current price.
   Remainder adjustment on last item.

2. **ELEC10** -- If 2+ electronics items, 10% off each electronics item's
   current price. Per-item: `round2(price * 0.10)`.

3. **BUNDLE5** -- If 1+ electronics AND 1+ books, 5% off each item in BOTH
   categories at current price. Per-item: `round2(price * 0.05)`.

4. **Order-level percentage** -- Highest of coupon % (SAVE10=10%, SAVE20=20%)
   and tiered % (subtotal >= $500 -> 5%, >= $1000 -> 10%). Applied to running
   subtotal after category promos. Distributed proportionally across items,
   remainder-adjusted.

5. **Fixed coupon** -- FLAT15: $15 off subtotal after percentage discount.
   Distributed proportionally.

6. **Loyalty redemption** -- 100 points = $5.00. Applied last. Cannot exceed
   subtotal. Distributed proportionally.

### Tax

Per-item tax: `round2(item_discounted_price * applicable_rate)`.
Order tax = sum of per-item taxes.

Apply state-specific exemptions (see tax rates table). The CA electronics
surcharge adds +1.5% to the base rate for electronics items only.

### Shipping

Group items by warehouse. Per group:
- Base cost: `round2($5.99 + $0.50 * total_weight_lbs)`
- Free if group's post-discount subtotal >= $75
- Digital items (warehouse "digital"): no shipping
- Gold/Platinum membership: free all shipping

Order shipping = sum of group costs.

### Payment

Two-method waterfall:
1. Gift card (up to balance)
2. Credit card (remainder)

Card validation: first char '4' = approve, '5' = decline.

### Fraud Check

- total > $5000 -> reject (return error, rollback inventory)
- total > $2000 -> review
- otherwise -> approve

### Loyalty Points

`points = floor(discounted_subtotal * tier_multiplier)`

Loyalty redemption reduces the subtotal before point calculation.

### Inventory

Reserve on placement (decrement atom). Rollback on payment decline or fraud
reject. Restore on return.

### Return Processing

Input: original order result + returned items + reason (:defective or :changed-mind).

- **Item refund** = item's `:final-price` from original order (already discounted)
- **Tax refund** = item's `:tax-amount` from original order
- **Shipping refund**:
  - Defective: proportional share of warehouse group shipping cost
    `round2(item_final_price / warehouse_group_subtotal * warehouse_shipping_cost)`
  - Changed-mind: $0
- **Total refund** = subtotal-refund + tax-refund + shipping-refund
- **Loyalty clawback** = `floor(subtotal_refund / original_subtotal * original_points)`
- **Payment refund** (reverse order): credit card first (up to original CC charge),
  then gift card

### Order Modification

Input: original order + changes (new item list).

1. Recompute entire pricing pipeline with new items
2. Delta = |new_total - original_total|
3. delta-action = :charge if new > original, :refund if new < original
4. Recalculate loyalty points, compute points-delta

### API Contract

```clojure
;; Placement
(place-order order-input resources) -> result

;; order-input keys:
;;   :items, :coupon, :membership, :state, :card,
;;   :gift-card-balance, :loyalty-points, :new-customer

;; result keys:
;;   :status, :discounted-subtotal, :tax, :shipping, :total,
;;   :items-detail [{:product-id :original-price :final-price :tax-amount :warehouse}]
;;   :shipping-groups {"west" {:cost X} ...}  ;; V3 adds this, V1 can omit
;;   :loyalty {:points-earned N :redemption-amount X}
;;   :payment {:gift-card-charged X :credit-card-charged Y}

;; Returns
(process-return original-order return-input resources) -> result

;; return-input keys:
;;   :returned-items, :reason

;; result keys:
;;   :status, :subtotal-refund, :tax-refund, :shipping-refund, :total-refund,
;;   :loyalty-clawback,
;;   :payment {:credit-card-refunded X :gift-card-refunded Y}

;; Modification
(modify-order original-order modification resources) -> result

;; modification keys:
;;   :changes [item-maps]

;; result keys:
;;   :status, :new-subtotal, :new-tax, :new-shipping, :new-total,
;;   :delta, :delta-action, :display-new-total, :display-delta,
;;   :new-points, :points-delta
```

### Phase 1 Test Cases

#### Placement Tests

**T1**: 1x laptop | CA | bronze | card 4xxx
```
subtotal=949.99 tax=83.12 shipping=0.0 total=1033.11 points=949 inv:laptop=99
```

**T2**: 1x laptop + 1x headphones | CA | bronze | card 4xxx
```
subtotal=923.38 tax=80.79 shipping=0.0 total=1004.17 points=923
```

**T3**: 1x headphones + 1x novel | CA | bronze | card 4xxx
```
subtotal=90.23 tax=7.68 shipping=6.39 total=104.30 points=90
```

**T4**: 1x laptop + 1x headphones + 1x novel | CA | bronze | card 4xxx
```
subtotal=829.73 tax=72.41 shipping=6.39 total=908.53 points=829
  laptop.final-price=756.61 headphones.final-price=60.52 novel.final-price=12.60
```

**T5**: 1x shirt + 1x novel | NY | coupon SAVE10 | bronze | card 4xxx
```
subtotal=40.48 tax=0.0 shipping=6.64 total=47.12 points=40
```

**T6**: 1x laptop | OR | gold | 500 loyalty-points | card 4xxx
```
subtotal=924.99 tax=0.0 shipping=0.0 total=924.99 redemption=25.0 points=1849
```

**T7**: 1x laptop + 1x shirt | CA | gift-card $200 | card 4xxx
```
subtotal=926.98 tax=80.71 shipping=6.24 total=1013.93
  gc-charged=200.0 cc-charged=813.93 points=926
```

**T8**: 1x laptop + 1x shirt + 1x novel + 1x ebook | TX | bronze | card 4xxx
```
subtotal=903.79 tax=55.93 shipping=6.64 total=966.36 points=903
```

**T9**: 10x laptop | TX | bronze | card 4xxx
```
status=:error error="Order rejected: fraud check failed" inventory=unchanged
```

**T10**: 1x laptop | CA | bronze | card 5xxx (decline)
```
status=:error error="Payment declined" inventory=unchanged
```

#### Return Tests

**T11**: Place T1-order, return 1x laptop, defective
```
sub-refund=949.99 tax-refund=83.12 ship-refund=0.0 total-refund=1033.11
  clawback=949 cc-refund=1033.11 inv:laptop=100
```

**T12**: Place T4-order, return 1x headphones, changed-mind
```
sub-refund=60.52 tax-refund=5.30 ship-refund=0.0 total-refund=65.82
  clawback=60 cc-refund=65.82 inv:headphones=50
```

**T13**: Place T7-order, return 1x shirt, changed-mind
```
sub-refund=26.99 tax-refund=1.96 ship-refund=0.0 total-refund=28.95
  clawback=26 cc-refund=28.95 gc-refund=0.0
```

#### Modification Tests

**T14**: Place T1-order, modify to 2x laptop
```
new-subtotal=1619.98 new-tax=141.74 new-shipping=0.0 new-total=1761.72
  delta=728.61 action=:charge new-points=1619 points-delta=670
```

**T15**: Place T4-order, modify to 1x laptop + 1x headphones (remove novel)
```
new-subtotal=923.38 new-tax=80.79 new-shipping=0.0 new-total=1004.17
  delta=95.64 action=:charge new-points=923 points-delta=94
```

#### COMBO75 Verification Tests

**T16**: 1x laptop + 1x headphones + 1x novel | CA | bronze | card 4xxx
(Same scenario as T4 -- verifies COMBO75 stacking)
```
subtotal=829.73 tax=72.41 shipping=6.39 total=908.53 points=829
  laptop.final-price=756.61 headphones.final-price=60.52 novel.final-price=12.60
```

**T17**: Place T16-order, return 1x headphones, defective
```
sub-refund=60.52 tax-refund=5.30 ship-refund=0.0 total-refund=65.82
  clawback=60 cc-refund=65.82 inv:headphones=50
```

**T18**: Place T16-order, modify to 1x laptop + 1x novel (remove headphones)
```
new-subtotal=916.02 new-tax=79.95 new-shipping=6.39 new-total=1002.36
  delta=93.83 action=:charge new-points=916 points-delta=87
```

### Phase 1 Acceptance Criteria

- 18 tests pass
- `place-order`, `process-return`, `modify-order` all work
- Inventory correctly reserved, rolled back, and restored
- Payment waterfall correct for split payments
- Fraud check rejects orders > $5000

---

## Phase 2: Extended Features

**30 tests total (12 new), 5 new subsystems added to Phase 1.**

### New Subsystems

#### 1. Multi-Currency

Supported currencies: USD (1.00), EUR (0.92), GBP (0.79), CAD (1.36).

- Order input includes `:currency` (default "USD")
- ALL internal calculations in USD
- Display amounts converted at the END: `display = round2(usd * rate)`
- Result adds: `:display-subtotal`, `:display-tax`, `:display-shipping`,
  `:display-total`, `:currency`
- Loyalty points always based on USD
- Shipping thresholds ($75) evaluated in USD
- Refunds include display amounts: `:display-subtotal-refund`, etc.

#### 2. Gift Wrapping

Items can be individually gift-wrapped using an options map.

- Books/digital: $2.99 per item
- All other categories: $4.99 per item
- Gift wrap is a service, taxed at flat 8% (OR = 0%)
- Gift wrap cost NOT in `:discounted-subtotal`, IS in `:total`
- Result adds: `:gift-wrap-total`, `:gift-wrap-tax`
- Return: defective = wrap refunded, changed-mind = $0 wrap refund
- Return result adds: `:gift-wrap-refund`, `:gift-wrap-tax-refund`

#### 3. Store Credit (Third Payment Method)

Payment waterfall (order of application):
1. Gift card (first)
2. Store credit (second)
3. Credit card (remainder)

Refund waterfall (REVERSE order):
1. Credit card first (up to original CC charge)
2. Store credit second (up to original SC charge)
3. Gift card last

Order input adds: `:store-credit-balance`.
Result payment adds: `:store-credit-charged` / `:store-credit-refunded`.

#### 4. Restocking Fees on Returns

Changed-mind returns incur restocking fees:
- Electronics: 15% of `:final-price`
- Clothing: 10%
- Books: 5%
- Digital: 0%
- Defective returns: NO restocking fee

Fee is per-item, rounded to 2 decimals. Deducted from subtotal refund.
Tax refund is unchanged (full). Shipping refund is unchanged.

Result adds: `:restocking-fee`.
`subtotal-refund = sum(final_prices) - restocking_fee`

**Impact on existing tests**: T12 and T13 change because they are changed-mind
returns that now incur restocking fees.

#### 5. Bulk Pricing

Quantity discounts on the same product:
- 3-4 units: 5% off base price per unit
- 5+ units: 10% off base price per unit

Applied BEFORE all other promotions (before COMBO75). Sets `:current-price`
which flows into the promotion pipeline.

### Updated Item Format

```clojure
;; Simple (no options):
{"laptop" 1}
;; With options:
{"laptop" {:qty 1 :gift-wrap true}}
```

The expand-items step handles both formats.

### Updated Promotion Pipeline

1. Expand items (handle both formats)
2. **Bulk pricing** (NEW)
3. COMBO75
4. ELEC10
5. BUNDLE5
6. Order-level %
7. Fixed coupon
8. Loyalty redemption

### Updated API

Order input adds: `:store-credit-balance`, `:currency`.
Make-order defaults: `store-credit-balance=0`, `currency="USD"`.

### Phase 2 Changes to Existing Tests

**T12** (changed-mind return now has restocking fee):
```
restocking=9.08 sub-refund=51.44 tax-refund=5.30 ship-refund=0.0
  total-refund=56.74 clawback=51 cc-refund=56.74 inv:headphones=50
```

**T13** (changed-mind return now has restocking fee):
```
restocking=2.70 sub-refund=24.29 tax-refund=1.96 ship-refund=0.0
  total-refund=26.25 clawback=24 cc-refund=26.25 gc-refund=0.0
```

All other Phase 1 tests (T1-T11, T14-T18) keep identical expected values.

### Phase 2 New Test Cases

#### Multi-Currency

**T19**: 1x laptop + 1x headphones | CA | EUR | bronze | card 4xxx
```
subtotal=923.38 tax=80.79 shipping=0.0 total=1004.17
  display-subtotal=849.51 display-tax=74.33 display-shipping=0.0
  display-total=923.84 points=923 currency="EUR"
```

#### Gift Wrapping

**T20**: 1x laptop(gift-wrap) + 1x shirt | CA | bronze | card 4xxx
```
subtotal=926.98 tax=80.71 shipping=6.24
  gift-wrap-total=4.99 gift-wrap-tax=0.40 total=1019.32
```

**T21**: 1x novel(gift-wrap) | OR | gold | card 4xxx
```
subtotal=14.99 tax=0.0 shipping=0.0
  gift-wrap-total=2.99 gift-wrap-tax=0.0 total=17.98
```

#### Store Credit

**T22**: 1x laptop + 1x shirt | CA | gc=$200 sc=$300 | card 4xxx
```
total=1013.93 gc-charged=200.0 sc-charged=300.0 cc-charged=513.93
```

#### Restocking Fees

**T23**: Place T2-order, return 1x headphones, changed-mind
```
restocking=10.26 sub-refund=58.13 tax-refund=5.98 ship-refund=0.0
  total-refund=64.11 clawback=58 cc-refund=64.11
```

#### Gift Wrap + Returns

**T24**: Place laptop(gift-wrap) order, return laptop, defective
```
sub-refund=949.99 tax-refund=83.12 ship-refund=0.0
  gift-wrap-refund=4.99 gift-wrap-tax-refund=0.40 total-refund=1038.50
```

**T25**: Place laptop(gift-wrap) order, return laptop, changed-mind
```
restocking=142.50 sub-refund=807.49 tax-refund=83.12 ship-refund=0.0
  gift-wrap-refund=0.0 gift-wrap-tax-refund=0.0 total-refund=890.61
```

#### Bulk Pricing

**T26**: 3x laptop | CA | bronze | card 4xxx
```
subtotal=2308.47 tax=201.99 shipping=0.0 total=2510.46 points=2308 inv:laptop=97
```

**T27**: 3x laptop + 1x headphones + 1x novel | CA | bronze | card 4xxx
```
subtotal=2209.67 tax=193.16 shipping=6.39 total=2409.22 points=2209
```

#### Multi-Currency Return

**T28**: Place T19-order (EUR), return 1x headphones, defective
```
sub-refund=68.39 tax-refund=5.98 ship-refund=0.0 total-refund=74.37
  display-sub-refund=62.92 display-tax-refund=5.50 display-total-refund=68.42
```

#### Store Credit Return

**T29**: Place T22-order (3-way split), return 1x shirt, changed-mind
```
restocking=2.70 sub-refund=24.29 tax-refund=1.96 total-refund=26.25
  cc-refund=26.25 sc-refund=0.0 gc-refund=0.0
```

#### Bulk Modification

**T30**: Place 5x laptop order, modify to 2x laptop
```
new-subtotal=1619.98 new-tax=141.74 new-shipping=0.0 new-total=1761.72
  delta=2202.18 action=:refund new-points=1619 points-delta=2025
```

### Phase 2 Acceptance Criteria

- 30 tests pass (T1-T30)
- T1-T11, T14-T18 unchanged from Phase 1
- T12, T13 updated with restocking fees
- All 5 new subsystems working
- Multi-currency display amounts correct
- 3-way payment waterfall and reverse waterfall correct

---

## Phase 3: Maximum Complexity

**52 tests total (22 new), 7 new subsystems. This phase REPLACES the shipping
model and adds auto-upgrade, which changes expected values for many existing
tests.**

### New Subsystems

#### 1. Subscription Pricing

Items can be marked as subscriptions (recurring monthly delivery).

```clojure
{"laptop" {:qty 1 :subscription true}}
{"laptop" {:qty 1 :gift-wrap true :subscription true}}  ;; combinable
```

- 15% off base `:price` (before bulk pricing)
- Applied FIRST in the pipeline, before bulk pricing
- Bulk pricing then applies to the subscription-discounted price
- Subscription items are EXCLUDED from COMBO75 eligibility
  - If laptop is subscription, COMBO75 does NOT trigger even if headphones+novel present
- Subscription items still count toward ELEC10 and BUNDLE5 thresholds
- Result adds: `:has-subscription true` if any item is subscription
- Each item in `:items-detail` includes `:subscription true/false`
- Return: adds `:subscription-cancelled true` if subscription item returned
- Restocking fee still applies (based on `:final-price`, category rules unchanged)

#### 2. Bundle Products

Composite products that expand as a single item, NOT into sub-items.

- Bundles use category `:bundle`
- Tax: use HIGHEST component category rate (gaming-bundle -> electronics rate)
- COMBO75: bundles do NOT count (gaming-bundle does NOT satisfy "laptop present")
- ELEC10: bundles do NOT count toward "2+ electronics"
- BUNDLE5: bundles do NOT count toward category thresholds
- Bulk pricing: bundles count as their own product (3x gaming-bundle = bulk)
- Shipping: ships as one item at bundle weight from bundle warehouse
- Gift wrap: $4.99 rate (non-books/digital)
- Subscription: bundles can be subscriptions
- Restocking: 0% for bundles (special promotional product)
- Return: must return entire bundle; component inventory restored on return
- Inventory: decrement COMPONENT inventory on placement (laptop -1, headphones -1)

#### 3. Tiered Shipping with Surcharges (REPLACES Phase 1/2 Shipping)

**This replaces the flat-rate shipping model entirely.**

Weight tiers (per warehouse group):

| Weight Range | Cost |
|-------------|------|
| 0 - 2 lb | $5.99 |
| 2.01 - 10 lb | $8.99 |
| 10.01 - 20 lb | $12.99 |
| 20.01+ lb | $15.99 + $0.25/lb over 20 |

Surcharges:
- **Hazmat**: $3.00 per electronics item (lithium batteries). Bundles count as 1.
- **Oversized**: $5.00 if any single item in the group weighs > 4.0 lb.
  Once per group, not per item.

Free shipping rules:
- Group subtotal >= $75: waives base tier cost ONLY (surcharges remain)
- Gold membership: waives base tier cost ONLY (surcharges remain)
- Platinum membership: waives EVERYTHING (base + all surcharges)
- Digital: no shipping (unchanged)

Result must include `:shipping-groups` map:
```clojure
{"west" {:cost 8.0} "east" {:cost 5.99}}
```

Return shipping refund (defective):
- Proportional share of base tier cost (same formula as Phase 1/2)
- Full hazmat refund per returned electronics item ($3.00 each)
- Proportional share of oversized surcharge
- Changed-mind: $0

#### 4. Warranty Add-ons

Per-item optional extended warranty.

| Category | Cost | Term |
|----------|------|------|
| electronics | $49.99 | 2 years |
| clothing | $9.99 | 1 year |
| books | N/A | N/A |
| digital | N/A | N/A |
| bundle | $59.99 | 2 years |

- Warranty is a separate line (like gift wrap)
- NOT in `:discounted-subtotal`, IS in `:total`
- Taxed as service: 8% flat (OR = 0%)
- Does NOT affect loyalty points, discount thresholds, or shipping
- Result adds: `:warranty-total`, `:warranty-tax`
- Items include `:warranty true/false`

Return rules (**different from gift wrap**):
- Defective: FULL warranty refund
- Changed-mind: **50%** warranty refund
  - `warranty-refund = round2(warranty_cost * 0.50)`
  - `warranty-tax-refund = round2(warranty_tax * 0.50)`
- Result adds: `:warranty-refund`, `:warranty-tax-refund`

Total refund (V3):
```
total-refund = subtotal-refund + tax-refund + shipping-refund
             + gift-wrap-refund + gift-wrap-tax-refund
             + warranty-refund + warranty-tax-refund
```

#### 5. Auto-Upgrade Loyalty Tier

If the current order pushes lifetime spend past a tier boundary, upgrade
and recompute shipping + loyalty.

- Order input adds: `:lifetime-spend` (default 0)
- Check: `lifetime_spend + discounted_subtotal >= next_tier_threshold`
- Thresholds: bronze->silver at $500, silver->gold at $2000
- If triggered: recompute shipping with new tier's benefits, recompute loyalty
  points with new multiplier
- Discounts/tax/gift-wrap/warranty do NOT change on upgrade
- Only one upgrade per order (no looping)
- Result adds: `:tier-upgraded true`, `:membership :silver/:gold`
- The check uses `:discounted-subtotal`, NOT `:total`

#### 6. County-Level Tax

Add county surcharges on top of state rates.

County tax data:

| State | County | Surcharge | Override Rules |
|-------|--------|-----------|----------------|
| CA | Los Angeles | +2.25% | None |
| CA | San Francisco | +1.25% | Digital exempt from county surcharge |
| NY | New York City | +4.5% | Same exemptions as state |
| NY | Buffalo | +4.0% | Clothing NOT exempt (overrides state) |
| OR | Portland | +0% | Still 0% |
| TX | Houston | +2.0% | Same exemptions as state |
| TX | Austin | +2.0% | Digital NOT exempt (overrides state) |

Order input adds: `:county` (optional, default nil = state rate only).

Exemption override logic:
1. If state exempts item AND county doesn't override -> rate = 0%
2. If county overrides a state exemption -> full combined rate applies
3. If county adds its own exemption -> county surcharge = 0%, state rate applies

Examples:
- Laptop in CA/LA: 7.25% + 1.5% (state electronics) + 2.25% (county) = 11.0%
- Shirt in NY/Buffalo: state normally exempts clothing, but Buffalo overrides ->
  8.875% + 4.0% = 12.875%
- E-book in TX/Austin: state normally exempts digital, but Austin overrides ->
  6.25% + 2.0% = 8.25%
- E-book in CA/SF: state rate 7.25%, SF exempts digital from county surcharge ->
  7.25% + 0% = 7.25%

County surcharges apply to PRODUCTS only, not to service taxes (gift wrap, warranty
always use flat 8%).

#### 7. Partial Fulfillment / Backorders

Items can be out of stock. Partially available items split.

- If `available < requested`: split into fulfilled + backordered
- Discounts: apply to FULL order (both fulfilled and backordered)
- Tax: apply to FULL order
- Shipping: ONLY for fulfilled items
- Gift wrap / Warranty: apply to ALL items (charged immediately)
- Payment: charge fulfilled portion, authorize backorder portion
  - `fulfilled-charge = fulfilled_subtotal + tax_on_fulfilled + shipping + gift_wrap + warranty`
  - `backorder-hold = backordered_subtotal + tax_on_backordered`
- Fraud check: on FULL order total
- Loyalty points: based on FULL order subtotal

Result adds:
```clojure
:fulfillment {:status :partial  ;; or :full
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

### Updated Item Format

```clojure
{"laptop" {:qty 1 :subscription true :warranty true :gift-wrap true}}
{"gaming-bundle" 1}   ;; simple format still works
```

### Updated Promotion Pipeline

1. Expand items (handle subscription, bundle, gift-wrap, warranty flags)
2. **Subscription pricing** (15% off base for subscription items)
3. Bulk pricing (on subscription-adjusted prices)
4. COMBO75 (exclude subscription items and bundles)
5. ELEC10 (exclude bundles from count)
6. BUNDLE5 (exclude bundles from count)
7. Order-level %
8. Fixed coupon
9. Loyalty redemption
10. **[Parallel]** Tax (with county), Shipping (tiered), Gift wrap, Warranty
11. Total
12. **Auto-upgrade** (may recompute shipping + loyalty)
13. Fraud check
14. Inventory (partial fulfillment split)
15. Payment (3-way waterfall, split fulfilled/backorder)
16. Loyalty points
17. Finalize (currency conversion)

### Updated API

Order input adds: `:county`, `:lifetime-spend`.
Make-order defaults: `county=nil`, `lifetime-spend=0`.

### Phase 3 Changes to Existing Tests

The tiered shipping model and auto-upgrade loyalty change expected values for
most existing tests. Below are ALL changes from Phase 2 values.

**Why tests change:**
- Shipping changes from flat-rate to tiered + surcharges
- Auto-upgrade triggers when `lifetime_spend + subtotal >= 500` (bronze->silver)
  or >= 2000 (silver->gold), changing loyalty points
- Some tests gain shipping refunds that were $0 before

#### Updated Placement Tests

**T1** (auto-upgrade triggers: 0+949.99 >= 500 -> silver):
```
shipping: 0.0 -> 8.0        (hazmat $3 + oversized $5, base free)
total: 1033.11 -> 1041.11
points: 949 -> 1424          (silver 1.5x: floor(949.99*1.5))
NEW: tier-upgraded=true membership=:silver
```

**T2** (auto-upgrade: 0+923.38 >= 500 -> silver):
```
shipping: 0.0 -> 11.0       (hazmat 2x$3 + oversized $5, base free)
total: 1004.17 -> 1015.17
points: 923 -> 1385          (silver 1.5x: floor(923.38*1.5))
```

**T3** (no auto-upgrade: 0+90.23 < 500):
```
shipping: 6.39 -> 8.99      (east 0-2lb tier $5.99; west hazmat $3 only... wait)
```
Actually: headphones(west, 0.3lb) + novel(east, 0.8lb).
West: 0.3lb, tier 0-2lb=$5.99, subtotal < $75 -> paid base, hazmat $3.00 = $8.99.
East: 0.8lb, tier 0-2lb=$5.99, subtotal < $75 -> paid base = $5.99.
Wait, but T3 expected shipping=8.99. Let me check.

Actually in V3, T3 shipping is $8.99. The west group has headphones $76.19 after BUNDLE5 discount... let me check. T3 items are headphones + novel with BUNDLE5:
- headphones: 79.99 * 0.95 = 75.99 -> wait, BUNDLE5 is 5% off: round2(79.99*0.05) = 4.00, so 75.99
- novel: 14.99 * 0.95 = round2(14.99*0.05) = 0.75, so 14.24
- subtotal = 90.23

West group (headphones only): subtotal = 75.99 >= $75 -> free base, hazmat $3.00 = $3.00
East group (novel only): subtotal = 14.24 < $75 -> $5.99 base
Total: $3.00 + $5.99 = $8.99. Yes, matches.

```
shipping: 6.39 -> 8.99
total: 104.30 -> 106.90
points unchanged (90, no auto-upgrade)
```

**T4** (auto-upgrade: 0+829.73 >= 500 -> silver):
```
shipping: 6.39 -> 16.99
total: 908.53 -> 919.13
points: 829 -> 1244          (silver 1.5x: floor(829.73*1.5))
```

**T5** (no auto-upgrade: 0+40.48 < 500):
```
shipping: 6.64 -> 5.99      (east only, 1.3lb, 0-2lb tier)
total: 47.12 -> 46.47
```

**T6** (gold, no auto-upgrade):
```
shipping: 0.0 -> 8.0        (gold waives base, surcharges remain: hazmat $3 + oversized $5)
total: 924.99 -> 932.99
points unchanged (1849)
```

**T7** (auto-upgrade: 0+926.98 >= 500 -> silver):
```
shipping: 6.24 -> 13.99
total: 1013.93 -> 1021.68
cc-charged: 813.93 -> 821.68
points: 926 -> 1390          (silver 1.5x: floor(926.98*1.5))
```

**T8** (auto-upgrade: 0+903.79 >= 500 -> silver):
```
shipping: 6.64 -> 13.99
total: 966.36 -> 973.71
points: 903 -> 1355          (silver 1.5x: floor(903.79*1.5))
```

**T9**: unchanged (error case)
**T10**: unchanged (error case)

#### Updated Return Tests

**T11** (defective return of T1 order -- T1 now has shipping):
```
ship-refund: 0.0 -> 8.0
total-refund: 1033.11 -> 1041.11
clawback: 949 -> 1424
cc-refund: 1033.11 -> 1041.11
```

**T12** (changed-mind, no shipping refund -- clawback changes due to auto-upgrade):
```
clawback: 51 -> 77
(all other values unchanged)
```

**T13** (changed-mind, split payment -- clawback changes):
```
clawback: 24 -> 36
(all other values unchanged)
```

#### Updated Modification Tests

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

**T16** (same scenario as T4):
```
shipping: 6.39 -> 16.99
total: 908.53 -> 919.13
points: 829 -> 1244
```

**T17** (defective return from T16 -- now has shipping refund):
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

#### Updated V2 Tests

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

**T22** (3-way split, total changes):
```
total: 1013.93 -> 1021.68
cc-charged: 513.93 -> 521.68
```

**T23** (restocking return, clawback changes):
```
clawback: 58 -> 87
```

**T24** (defective return, now has shipping refund):
```
ship-refund: 0.0 -> 8.0
total-refund: 1038.50 -> 1046.50
```

**T25**: unchanged (changed-mind, $0 shipping refund)

**T26** (3x laptop, shipping changes):
```
shipping: 0.0 -> 14.0       (base free, hazmat 3x$3=$9 + oversized $5)
total: 2510.46 -> 2524.46
points: 2308 -> 3462        (gold 2.0x: auto-upgrade at $2000)
```

**T27** (bulk+COMBO75, shipping changes):
```
shipping: 6.39 -> 22.99
total: 2409.22 -> 2425.82
points: 2209 -> 3314
```

**T28** (EUR return, shipping refund changes):
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

### Phase 3 New Test Cases

#### Subscription Pricing

**T31**: 1x laptop(subscription) | CA | bronze | card 4xxx
```
subtotal=807.49 tax=70.66 shipping=8.0 total=886.15
  points=1211 has-subscription=true
```

**T32**: 1x laptop(subscription) + 1x headphones + 1x novel | CA | bronze | card 4xxx
COMBO75 must NOT trigger (laptop is subscription).
```
subtotal=768.90 tax=67.07 shipping=16.99 total=852.96
  points=1153 has-subscription=true laptop.final-price=690.40
```

#### Bundle Products

**T33**: 1x gaming-bundle | CA | bronze | card 4xxx
```
subtotal=949.05 tax=83.04 shipping=8.0 total=1040.09
  points=1423 inv:laptop=99 inv:headphones=49
```

**T34**: 1x gaming-bundle + 1x novel | CA | bronze | card 4xxx
Bundle does NOT satisfy COMBO75/ELEC10/BUNDLE5.
```
subtotal=912.59 tax=79.65 shipping=13.99 total=1006.23 points=1368
```

#### County-Level Tax

**T35**: 1x laptop | CA | county "Los Angeles" | bronze | card 4xxx
Tax: 7.25% + 1.5% + 2.25% = 11.0%
```
subtotal=949.99 tax=104.50 shipping=8.0 total=1062.49
```

**T36**: 1x shirt | NY | county "Buffalo" | bronze | card 4xxx
Buffalo overrides NY clothing exemption.
```
subtotal=29.99 tax=3.86 shipping=5.99 total=39.84
```

**T37**: 1x ebook | TX | county "Austin" | bronze | card 4xxx
Austin overrides TX digital exemption.
```
subtotal=9.99 tax=0.82 shipping=0.0 total=10.81
```

#### Tiered Shipping

**T38**: 1x laptop + 1x shirt + 1x novel | CA | bronze | card 4xxx
West: free base (subtotal>$75) + hazmat $3 + oversized $5 = $8.
East: $5.99 base (subtotal<$75, 0-2lb) = $5.99. Total: $13.99.
```
shipping=13.99 shipping-groups: west=8.0 east=5.99
```

**T39**: 1x laptop + 1x headphones | CA | gold | card 4xxx
Gold waives base, surcharges remain: hazmat 2x$3 + oversized $5 = $11.
```
shipping=11.0 subtotal=923.38 points=1846
```

**T40**: 1x laptop + 1x headphones | CA | platinum | card 4xxx
Platinum waives everything.
```
shipping=0.0 total=1004.17
```

#### Warranty

**T41**: 1x laptop(warranty) | CA | bronze | card 4xxx
```
subtotal=949.99 tax=83.12 warranty-total=49.99 warranty-tax=4.0
  total=1095.10
```

**T42**: Place T41-order, return laptop, defective (full warranty refund)
```
sub-refund=949.99 tax-refund=83.12 warranty-refund=49.99
  warranty-tax-refund=4.0 total-refund=1095.10
```

**T43**: Place T41-order, return laptop, changed-mind (50% warranty)
```
restocking=142.50 sub-refund=807.49 warranty-refund=25.0
  warranty-tax-refund=2.0 total-refund=917.61
```

#### Auto-Upgrade

**T44**: 1x laptop | CA | bronze | lifetime-spend=0 | card 4xxx
Subtotal $949.99 + lifetime $0 = $949.99 >= $500 -> upgrade to silver.
```
tier-upgraded=true membership=:silver points=1424
```

#### Partial Fulfillment

**T46**: 1x laptop + 1x headphones | CA | bronze | card 4xxx
Inventory: laptop=0, headphones=50.
```
fulfillment-status=:partial fulfilled-count=1 backordered-count=1
  fulfilled-charge=83.36 backorder-hold=929.80
  shipping=8.99 inv:laptop=0 inv:headphones=49
```

**T47**: From T46, return laptop (backordered) -> $0 refund.
Return headphones (fulfilled, defective) -> normal refund.
```
laptop-return: total-refund=0.0
headphones-return: sub-refund=68.39 tax-refund=5.98 ship-refund=3.44
  total-refund=77.81
```

#### Cross-Feature: Subscription + Warranty + Gift Wrap

**T48**: 1x laptop(subscription, warranty, gift-wrap) | CA | bronze | card 4xxx
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
Restocking, $0 gift wrap, 50% warranty. Subscription cancelled.
```
restocking=121.12 sub-refund=686.37
  gift-wrap-refund=0.0 warranty-refund=25.0 warranty-tax-refund=2.0
  total-refund=784.03 subscription-cancelled=true
```

#### Cross-Feature: County Tax + Currency + Gift Wrap + Warranty

**T51**: 1x laptop(gift-wrap, warranty) | CA | county "Los Angeles" | EUR | card 4xxx
County tax on products (11%), but service tax stays 8%.
```
subtotal=949.99 tax=104.50 gift-wrap-total=4.99 gift-wrap-tax=0.40
  warranty-total=49.99 warranty-tax=4.0 total=1121.87
  display-subtotal=873.99 display-tax=96.14 display-total=1032.12
```

#### Bundle Return

**T52**: Place gaming-bundle order, return defective
0% restocking. Component inventory restored.
```
restocking=0.0 sub-refund=949.05 tax-refund=83.04 total-refund=1040.09
  inv:laptop=100 inv:headphones=50
```

#### Subscription Modification

**T53**: Place 3x laptop(subscription), modify to 1x laptop(subscription)
Loses bulk + ELEC10 + tiered.
```
new-subtotal=807.49 new-tax=70.66 new-shipping=8.0 new-total=886.15
  delta=1261.74 action=:refund new-points=1211
```

### Phase 3 Acceptance Criteria

- 52 tests pass (T1-T44, T46-T53)
- All 7 new subsystems working
- Tiered shipping replaces flat-rate shipping
- Auto-upgrade correctly recomputes shipping + loyalty (not discounts/tax)
- County tax overrides work correctly
- Warranty 50% refund different from gift wrap $0 refund on changed-mind
- Partial fulfillment splits items and charges correctly
- Bundle products opaque for promotion purposes
- Subscription items excluded from COMBO75

---

## Summary

| Phase | Tests | Subsystems | Key Complexity |
|-------|-------|------------|----------------|
| 1 | 18 | 6 | Stacking promotions, split payment, returns reversing discounts |
| 2 | 30 | 11 | Multi-currency, gift wrap tax, 3-way payment, restocking fees |
| 3 | 52 | 18 | Tiered shipping, county tax overrides, warranty vs gift wrap refund rules, auto-upgrade two-pass, partial fulfillment, subscription/bundle promo exclusions |

The system must be implemented as three source files minimum:
- `placement.clj` -- order placement logic
- `returns.clj` -- return processing logic
- `modification.clj` -- order modification logic (can delegate to placement)

Plus a `workflow.clj` wrapper exposing `place-order`, `process-return`, and
`modify-order` as the public API.

Data structures (catalog, coupons, tax-rates, county-taxes, inventory) are
passed in as a `resources` map, not hardcoded.
