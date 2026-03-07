# Phase 1: Core Order Lifecycle

Build an order lifecycle system in Clojure with three workflows: **order
placement**, **return processing**, and **order modification**.

**18 tests, 6 subsystems.**

Use `round2` (HALF_UP to 2 decimal places) for all monetary calculations.
Use `floor` for loyalty point calculations.

```clojure
(defn round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))
```

---

## Data Fixtures

### Product Catalog

| ID | Name | Category | Price | Weight | Warehouse |
|----|------|----------|-------|--------|-----------|
| laptop | Laptop | electronics | 999.99 | 5.0 | west |
| shirt | T-Shirt | clothing | 29.99 | 0.5 | east |
| novel | Novel | books | 14.99 | 0.8 | east |
| headphones | Headphones | electronics | 79.99 | 0.3 | west |
| ebook | E-Book | digital | 9.99 | 0.0 | digital |

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

Inventory is an atom. Reserve (decrement) on placement, restore on return,
rollback on payment decline or fraud reject.

### Loyalty Tiers

| Lifetime Spend | Tier | Point Multiplier |
|---------------|------|-----------------|
| $0 - $499 | bronze | 1.0x |
| $500 - $1999 | silver | 1.5x |
| $2000+ | gold | 2.0x |

Points earned = `floor(discounted_subtotal * tier_multiplier)`.

---

## Item Format

Simple quantity map:
```clojure
[{"laptop" 1} {"shirt" 2}]
```

---

## Promotion Pipeline (applied in order, all stackable)

1. **COMBO75** -- If product IDs "laptop", "headphones", AND "novel" are ALL
   present in the order, apply $75 flat discount distributed proportionally
   by current price across all items of those three product types. Remainder
   adjustment on last item.

2. **ELEC10** -- If 2+ electronics items, 10% off each electronics item's
   current price. Per-item discount: `round2(current_price * 0.10)`.

3. **BUNDLE5** -- If 1+ electronics AND 1+ books, 5% off each item in BOTH
   categories at current price. Per-item: `round2(current_price * 0.05)`.

4. **Order-level percentage** -- Highest of coupon % (SAVE10=10%, SAVE20=20%)
   and tiered % (subtotal >= $500 -> 5%, >= $1000 -> 10%) wins. Applied to
   running subtotal after category promos. Distributed proportionally across
   items, remainder-adjusted.

5. **Fixed coupon** -- FLAT15: $15 off subtotal after percentage discount.
   Distributed proportionally.

6. **Loyalty redemption** -- 100 points = $5.00. Applied last. Cannot exceed
   subtotal. Distributed proportionally.

---

## Tax

Per-item tax: `round2(item_discounted_price * applicable_rate)`.
Order tax = sum of per-item taxes (no further rounding).

Apply state-specific exemptions per the tax rates table. The CA electronics
surcharge adds +1.5% to the base rate for electronics items only.

---

## Shipping

Group items by warehouse. Per group:
- Base cost: `round2($5.99 + $0.50 * total_weight_lbs)`
- Free if group's post-discount subtotal >= $75
- Digital items (warehouse "digital"): no shipping
- Gold/Platinum membership: free all shipping
- Per-group cost rounded to 2 decimals

Order shipping = sum of group costs.

---

## Payment

Two-method waterfall:
1. Gift card (up to balance)
2. Credit card (remainder)

Card validation: first char '4' = approve, '5' = decline.

Order input includes `:gift-card-balance` (default 0).

---

## Fraud Check

- total > $5000 -> reject (return error, rollback inventory)
- total > $2000 -> review
- otherwise -> approve

---

## Loyalty Points

`points = floor(discounted_subtotal * tier_multiplier)`

Loyalty redemption reduces the subtotal before point calculation.

---

## Return Processing

Input: original order result + returned items + reason (`:defective` or `:changed-mind`).

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

---

## Order Modification

Input: original order + changes (new item list).

1. Recompute entire pricing pipeline with new items
2. Delta = |new_total - original_total|
3. delta-action = `:charge` if new > original, `:refund` if new < original
4. Recalculate loyalty points, compute points-delta

---

## API Contract

```clojure
;; Resources map (passed to all functions)
{:catalog   catalog-map
 :coupons   coupons-map
 :tax-rates tax-rates-map
 :inventory inventory-atom}

;; Placement
(place-order order-input resources) -> result

;; order-input:
{:items             [{"laptop" 1} ...]
 :coupon            "SAVE10"        ;; or nil
 :membership        :bronze         ;; :bronze :silver :gold :platinum
 :state             "CA"
 :card              "4111..."
 :gift-card-balance 0
 :loyalty-points    0
 :new-customer      false}

;; placement result:
{:status              :success
 :discounted-subtotal  949.99
 :tax                  83.12
 :shipping             0.0
 :total                1033.11
 :items-detail         [{:product-id "laptop"
                         :original-price 999.99
                         :final-price 949.99
                         :tax-amount 83.12
                         :warehouse "west"}]
 :shipping-groups      {"west" {:cost 0.0}}
 :loyalty              {:points-earned 949
                        :redemption-amount 0.0}
 :payment              {:gift-card-charged 0.0
                        :credit-card-charged 1033.11}}

;; Returns
(process-return original-order return-input resources) -> result

;; return-input:
{:returned-items [{"laptop" 1}]
 :reason         :defective}    ;; or :changed-mind

;; return result:
{:status           :success
 :subtotal-refund  949.99
 :tax-refund       83.12
 :shipping-refund  0.0
 :total-refund     1033.11
 :loyalty-clawback 949
 :payment          {:credit-card-refunded 1033.11
                    :gift-card-refunded 0.0}}

;; Modification
(modify-order original-order modification resources) -> result

;; modification:
{:changes [{"laptop" 2}]}

;; modification result:
{:status       :success
 :new-subtotal 1619.98
 :new-tax      141.74
 :new-shipping 0.0
 :new-total    1761.72
 :delta        728.61
 :delta-action :charge
 :new-points   1619
 :points-delta 670}
```

---

## Test Cases

### Placement Tests

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

### Return Tests

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

### Modification Tests

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

### COMBO75 Verification Tests

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

---

## Acceptance Criteria

- All 18 tests pass
- `place-order`, `process-return`, `modify-order` all work correctly
- Inventory correctly reserved, rolled back on errors, and restored on returns
- Payment waterfall correct for split payments (gift card first, credit card second)
- Fraud check rejects orders > $5000
- All promotions stack correctly: COMBO75 -> ELEC10 -> BUNDLE5 -> tiered/coupon -> fixed -> loyalty
- Tax exemptions work per state rules
- Multi-warehouse shipping groups calculate correctly
