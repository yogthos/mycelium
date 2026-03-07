# Phase 2: Extended Features

**Assumes Phase 1 is fully implemented and all 18 tests pass.**

Add 5 new subsystems on top of the existing order lifecycle. This phase adds
12 new tests (T19-T30) and modifies 2 existing test expectations (T12, T13).

**30 tests total after this phase.**

---

## New Subsystems

### 1. Multi-Currency

Supported currencies and rates (per 1 USD):

| Currency | Rate |
|----------|------|
| USD | 1.00 |
| EUR | 0.92 |
| GBP | 0.79 |
| CAD | 1.36 |

Rules:
- Order input adds `:currency` (default `"USD"`)
- ALL internal calculations happen in USD -- no conversion during computation
- Display amounts are converted at the END: `display = round2(usd_amount * rate)`
- Result adds: `:display-subtotal`, `:display-tax`, `:display-shipping`,
  `:display-total`, `:currency`
- Loyalty points always based on USD amounts
- Shipping thresholds ($75) evaluated in USD
- Refunds computed in USD, displayed in original order currency
- Return result adds: `:display-subtotal-refund`, `:display-tax-refund`,
  `:display-total-refund`
- Modification result adds: `:display-new-total`, `:display-delta`

### 2. Gift Wrapping

Items can be individually gift-wrapped.

Wrapping costs by category:
- Books, digital: $2.99 per item
- All other categories: $4.99 per item

Rules:
- Gift wrap is a **service**, taxed at flat 8% service rate regardless of state
  - Exception: OR (Oregon) = 0% on everything including services
- Gift wrap cost is NOT included in `:discounted-subtotal`
- Gift wrap cost IS included in `:total`
- Gift wrap tax is separate from product tax
- Result adds: `:gift-wrap-total`, `:gift-wrap-tax`

Return interactions:
- Defective return: gift wrap fee IS refunded
- Changed-mind return: gift wrap fee NOT refunded ($0)
- Return result adds: `:gift-wrap-refund`, `:gift-wrap-tax-refund`

### 3. Store Credit (Third Payment Method)

Payment waterfall (order of application):
1. Gift card (first, up to balance)
2. Store credit (second, up to balance)
3. Credit card (remainder)

Refund waterfall (REVERSE order):
1. Credit card first (up to original CC charge)
2. Store credit second (up to original SC charge)
3. Gift card last (remainder)

- Order input adds: `:store-credit-balance` (default 0)
- Result payment adds: `:store-credit-charged`
- Return payment adds: `:store-credit-refunded`

### 4. Restocking Fees on Returns

Changed-mind returns incur a restocking fee per item:

| Category | Restocking Rate |
|----------|----------------|
| electronics | 15% |
| clothing | 10% |
| books | 5% |
| digital | 0% |

Rules:
- Fee is on item's `:final-price` (post-discount price), rounded to 2 decimals
- Defective returns: NO restocking fee
- Fee is NOT taxed
- Fee is deducted from the subtotal refund:
  `subtotal-refund = sum(final_prices) - restocking_fee`
- Tax refund is unchanged (full tax refund regardless of restocking)
- Shipping refund is unchanged
- Total refund = adjusted subtotal + tax + shipping
- Loyalty clawback uses adjusted subtotal-refund (after restocking deduction)
- Return result adds: `:restocking-fee`

### 5. Bulk Pricing (Quantity Discounts)

Per-product quantity discounts:
- 3-4 units of same product: 5% off base price per unit
- 5+ units of same product: 10% off base price per unit

Rules:
- Applied BEFORE all other promotions (before COMBO75)
- Discount is on the original `:price`: `round2(price * rate)`
- The discounted price becomes the new `:current-price` for downstream promos
- Bulk-discounted items still count toward promotion thresholds (e.g., 3 laptops
  at 5% off still trigger ELEC10 since 3 >= 2 electronics)

---

## Updated Item Format

Items can now use two formats:
```clojure
;; Simple (no options):
{"laptop" 1}

;; With options:
{"laptop" {:qty 1 :gift-wrap true}}
```

The expand-items step must handle both formats. Non-wrapped items can use
either format.

---

## Updated Promotion Pipeline

1. Expand items (handle both simple and option formats)
2. **Bulk pricing** (NEW)
3. COMBO75
4. ELEC10
5. BUNDLE5
6. Order-level %
7. Fixed coupon
8. Loyalty redemption

---

## Updated Total Calculation

```
total = discounted-subtotal + tax + shipping + gift-wrap-total + gift-wrap-tax
```

---

## Updated Return Total

```
total-refund = subtotal-refund + tax-refund + shipping-refund
             + gift-wrap-refund + gift-wrap-tax-refund
```

---

## Updated API

Order input adds:
```clojure
:store-credit-balance 0    ;; default 0
:currency             "USD" ;; default "USD"
```

---

## Changes to Existing Tests

Phase 2 adds restocking fees, which changes two existing changed-mind return
tests. All other Phase 1 tests keep identical expected values.

**T12** (was: return headphones changed-mind, no restocking):
Now has 15% electronics restocking fee.
```
restocking=9.08 sub-refund=51.44 tax-refund=5.30 ship-refund=0.0
  total-refund=56.74 clawback=51 cc-refund=56.74 inv:headphones=50
```

**T13** (was: return shirt changed-mind from split payment, no restocking):
Now has 10% clothing restocking fee.
```
restocking=2.70 sub-refund=24.29 tax-refund=1.96 ship-refund=0.0
  total-refund=26.25 clawback=24 cc-refund=26.25 gc-refund=0.0
```

---

## New Test Cases

### Multi-Currency

**T19**: 1x laptop + 1x headphones | CA | EUR | bronze | card 4xxx
Same USD calculation as T2 (ELEC10 triggers), plus display amounts in EUR.
```
subtotal=923.38 tax=80.79 shipping=0.0 total=1004.17
  display-subtotal=849.51 display-tax=74.33 display-shipping=0.0
  display-total=923.84 points=923 currency="EUR"
```

### Gift Wrapping

**T20**: 1x laptop(gift-wrap) + 1x shirt | CA | bronze | card 4xxx
```
subtotal=926.98 tax=80.71 shipping=6.24
  gift-wrap-total=4.99 gift-wrap-tax=0.40 total=1019.32
```

**T21**: 1x novel(gift-wrap) | OR | gold | card 4xxx
Oregon: no tax on products or services.
```
subtotal=14.99 tax=0.0 shipping=0.0
  gift-wrap-total=2.99 gift-wrap-tax=0.0 total=17.98
```

### Store Credit

**T22**: 1x laptop + 1x shirt | CA | gc=$200 sc=$300 | card 4xxx
```
total=1013.93 gc-charged=200.0 sc-charged=300.0 cc-charged=513.93
```

### Restocking Fees

**T23**: Place T2-order (laptop+headphones), return 1x headphones, changed-mind
Electronics 15% restocking on headphones final-price.
```
restocking=10.26 sub-refund=58.13 tax-refund=5.98 ship-refund=0.0
  total-refund=64.11 clawback=58 cc-refund=64.11
```

### Gift Wrap + Returns

**T24**: Place laptop(gift-wrap) order (CA), return laptop, defective
Wrap refunded (defective).
```
sub-refund=949.99 tax-refund=83.12 ship-refund=0.0
  gift-wrap-refund=4.99 gift-wrap-tax-refund=0.40 total-refund=1038.50
```

**T25**: Place laptop(gift-wrap) order (CA), return laptop, changed-mind
Wrap NOT refunded. Restocking applies.
```
restocking=142.50 sub-refund=807.49 tax-refund=83.12 ship-refund=0.0
  gift-wrap-refund=0.0 gift-wrap-tax-refund=0.0 total-refund=890.61
```

### Bulk Pricing

**T26**: 3x laptop | CA | bronze | card 4xxx
5% bulk + ELEC10 + tiered 10%.
```
subtotal=2308.47 tax=201.99 shipping=0.0 total=2510.46
  points=2308 inv:laptop=97
```

**T27**: 3x laptop + 1x headphones + 1x novel | CA | bronze | card 4xxx
Bulk + COMBO75 + ELEC10 + BUNDLE5 + tiered.
```
subtotal=2209.67 tax=193.16 shipping=6.39 total=2409.22 points=2209
```

### Multi-Currency Return

**T28**: Place T19-order (EUR), return 1x headphones, defective
Refund in USD, display in EUR.
```
sub-refund=68.39 tax-refund=5.98 ship-refund=0.0 total-refund=74.37
  display-sub-refund=62.92 display-tax-refund=5.50 display-total-refund=68.42
```

### Store Credit Return

**T29**: Place T22-order (3-way split), return 1x shirt, changed-mind
Refund follows reverse waterfall: CC first, then SC, then GC.
```
restocking=2.70 sub-refund=24.29 tax-refund=1.96 total-refund=26.25
  cc-refund=26.25 sc-refund=0.0 gc-refund=0.0
```

### Bulk Modification

**T30**: Place 5x laptop order (10% bulk), modify to 2x laptop (no bulk)
```
new-subtotal=1619.98 new-tax=141.74 new-shipping=0.0 new-total=1761.72
  delta=2202.18 action=:refund new-points=1619 points-delta=2025
```

---

## Acceptance Criteria

- All 30 tests pass (T1-T30)
- T1-T11, T14-T18 unchanged from Phase 1
- T12, T13 updated with restocking fees
- Multi-currency: display amounts correct, internal calculations stay in USD
- Gift wrapping: separate tax rate (8% service), correct refund rules
- Store credit: 3-way payment waterfall and reverse waterfall correct
- Restocking fees: per-category rates, only on changed-mind returns
- Bulk pricing: applied before COMBO75, affects downstream promo calculations
