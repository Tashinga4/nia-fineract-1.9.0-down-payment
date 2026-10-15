# Nia Fineract 1.9.0-nia.2: down-payment loans get all their repayments

**Status:** implemented and tested on a Fineract 1.9.0 test instance. Not deployed to production yet.
**Base:** Nia Fineract 1.9.0-nia.1 (twice-weekly repayments). This is an internal Nia Financial Services change, not an Apache Software Foundation release.
**Date:** 15 October 2026

---

Dear Nia team,

This update fixes loans on products with a **down payment** (the HP products and Hilton).

## What was wrong

These loans got **fewer repayments than the product says**, and so **less interest** than the product's rate. On a loan of 100:

| Product | Should have | Had | Interest had | Interest now |
|---|---|---|---|---|
| 2HB4, 2H8W, Hilton HBW4 | 8 repayments | 6 | 15.00 | 20.00 |
| HP4B, 15H8 | 8 repayments | 7 | 13.16 | 15.00 |
| 2H6W | 6 repayments | 5 | 16.65 | 20.00 |
| H6BW | 12 repayments | 10 | 16.70 | 20.00 |
| 2H8B | 16 repayments | 14 | 27.58 | 31.56 |
| 5H8B | 16 repayments | 14 | 13.16 | 15.00 |

2H4W and 15HW had the right number of repayments, but the last one was much smaller than the others. Now they are equal.

## What changes

- The down payment is still collected **on the day of disbursement**.
- The rest of the loan is shared over **all** the repayments the product has.
- Each repayment is a whole amount; only the **last** one may be a little different, to make the total exact.
- **Interest follows the product's rate** on the full loan amount. Customers on these products pay **more than before** (2H8W on 100: 20.00 instead of 15.00).
- Products **without** a down payment do not change.

## For existing loans

Loans already running **keep their current schedule**. Only new loans use the new rule.

## On/off

The change has its own setting, **`down-payment-spread-remaining-principal`**, which is **off** when the update is installed. Nia IT switches it on after a test loan. Switching it off restores the old behaviour for new loans immediately.
