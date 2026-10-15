# Down-payment loans: keep every configured repayment (Fineract 1.9.0)

**Branch:** `nia/down-payment-full-schedule` → `main` (Apache Fineract 1.9.0 + Nia twice-weekly change)
**Commit:** 1 commit, 9 files, +384 / −7
**Status:** ready for review. Unit tests and end-to-end tests pass on a local 1.9.0 test instance; not deployed to production.
**Depends on:** the twice-weekly change, https://github.com/Tashinga4/nia-fineract-1.9.0/pull/1 (already in `main` here). Apply that one first.

## The bug

Nia's down-payment products (all HP products and Hilton) get **fewer repayments than configured**, and with flat interest charged per repayment, **less interest** than the product's rate. Found on the 1.9.0 test instance with Nia's 30 products, loan of 100, setting off (stock 1.9.0):

| Product | Configured | Stock 1.9.0 | Interest, stock | Interest, fixed |
|---|---|---|---|---|
| 2HB4 20% HP 4 Weeks (Bi-Weekly) | 8 | **6** | 15.00 | 20.00 |
| 2H8W 20% HP 8 Weeks (Weekly) | 8 | **6** | 15.00 | 20.00 |
| HBW4 20% 4 Weeks (Bi_Weekly) Hilton | 8 | **6** | 15.00 | 20.00 |
| HP4B 15% HP 4 Weeks (bi-weekly) | 8 | **7** | 13.16 | 15.00 |
| 15H8 15% HP 8 Weeks (Weekly) | 8 | **7** | 13.16 | 15.00 |
| 2H6W 20% HP 6 Weeks (Weekly) | 6 | **5** | 16.65 | 20.00 |
| H6BW 20 % HP 6 Weeks (Bi Weekly) | 12 | **10** | 16.70 | 20.00 |
| 2H8B 20% HP 8 Weeks (Bi-Weekly), per month | 16 | **14** | 27.58 | 31.56 |
| 5H8B 15% HP 8 Weeks (Bi-Weekly) | 16 | **14** | 13.16 | 15.00 |
| 2H4W 20% HP 4 Weeks (Weekly) | 4 | 4 | 20.00 | 20.00 |
| 15HW 15% HP 4 Weeks (Weekly) | 4 | 4 | 15.00 | 15.00 |

2H4W and 15HW have the right number of repayments but still the wrong amounts: stock gives 25 principal three times and 5 in the last repayment; fixed gives 20 four times.

It happens with the twice-weekly setting on and off, and on weekly products, so it is stock Fineract behaviour, not the twice-weekly change.

## Cause

For flat-interest loans, `LoanApplicationTerms.calculateTotalPrincipalPerPeriodWithoutGrace` sizes each repayment's principal as **loan principal ÷ number of repayments**, using the **full** principal, although the down payment has already been taken off the outstanding balance at disbursement. Example 2H8W, 100 with 20% down payment, 8 repayments: principal per repayment 100 ÷ 8 = 12.50 instead of 80 ÷ 8 = 10.00, so the 80 left is paid off after 6.4 repayments and the schedule stops.

A second, smaller cause: with "installments in multiples of" set, Fineract rounds principal and interest **separately**, both up (6.67 → 7 and 1.67 → 2), so each repayment is too big again. That alone made a 12-repayment product end after 11 in the first version of this fix.

## The change

New global configuration **`down-payment-spread-remaining-principal`**, **off by default**. When it is on, for loans with a down payment (not multi-disbursement loans, which already handle it):

- the principal **left after the down payment** is spread over **all** the configured repayments;
- the **whole repayment** (principal + interest) is rounded to the "installments in multiples of" setting. If even that would pay the loan off early, it is rounded down and the last repayment takes the remainder.

**Interest is not changed**: flat interest on the **full loan amount**, as the product's rate says (Nia's decision: 2H8W on 100 → 20.00). Customers on down-payment products pay the full contractual interest again, which is more than stock 1.9.0 charges today.

Files:
- `LoanApplicationTerms`: `principalToSpreadOverInstallments()`, `roundInstallmentKeepingRepaymentCount()`, `isDownPaymentSpreadOverRemainingInstallments()`.
- `HolidayDetailDTO`: carries the flag, next to the twice-weekly one (set in `LoanScheduleAssembler` and `LoanUtilService`), so the schedule at application and the one regenerated at disbursement use the same rule.
- `ConfigurationDomainService(Jpa)`: `isDownPaymentSpreadRemainingPrincipalEnabled()`.
- Liquibase `0142_add_configuration_down_payment_spread_remaining_principal.xml`: adds the setting, disabled.

**Not affected:** loans without a down payment, declining-balance loans, multi-disbursement loans, and everything when the setting is off.

## Tests

**Unit tests** (JDK 17, Gradle 8.6), new `DownPaymentSpreadRemainingPrincipalTest`, 22 tests, generating full schedules with the real flat-interest generator:
- setting off reproduces the bug (6 repayments, interest 15);
- setting on: all repayments for 4, 6, 8, 12 and 16 repayments, principal 80, interest 20;
- 12 loan amounts from 37 to 2,500 with 4 to 16 repayments: repayment count kept, principal adds up to the loan, interest 20% of the loan to the cent, every repayment but the last a whole amount;
- without rounding: exactly 10.00 principal + 2.50 interest each time;
- 2HB4 with both Nia settings: 8 repayments alternating Thu/Mon;
- loans without a down payment: identical schedules with the setting on and off.

All unit tests in `fineract-core`, `fineract-loan` and `fineract-provider`: **710 tests, 0 failures**. Spotless check passes.

**End-to-end on a 1.9.0 test instance** (image built from this branch, empty database, Nia's 30 products, the 14 bi-weekly ones on every 3 days). Logs in [`e2e/`](e2e/):
- `e2e-190-down-payment.log`: **37/37 checks pass**: the setting is off after installation; off reproduces the bug; on, all 11 down-payment products get all their repayments with the right principal and interest; the 19 other products are unchanged; other loan amounts (55 to 1,234) keep the count; real loans of 2H8W, 2HB4, HBW4 and H6BW: application schedule = disbursed schedule, down payment of 20 collected at disbursement, 2HB4 still alternating Thu/Mon, a repayment changes nothing; switching off again affects only new schedules.
- `e2e-190-run3-twice-weekly.log`: the twice-weekly checks with this build, **20/20**.

## How to apply

After the twice-weekly change (https://github.com/Tashinga4/nia-fineract-1.9.0/pull/1):

```bash
git fetch https://github.com/Tashinga4/nia-fineract-1.9.0-down-payment.git nia/down-payment-full-schedule
git cherry-pick FETCH_HEAD
```

Build with **JDK 17**.

## Deployment

1. Back up the database and keep the current build/image.
2. Deploy the build. The setting is off, so nothing changes.
3. Enable **Admin → System → Configurations → `down-payment-spread-remaining-principal`**.
4. Preview or book one test loan on a down-payment product (for example 2H8W) and check: all repayments there, down payment collected at disbursement, interest as the product's rate.
5. No product needs changing.

**Existing loans keep their stored schedules.** Only new loans (and schedules regenerated at disbursement) use the new rule. Whether to correct active short loans is a separate business decision.

**Rollback:** disable the setting; new schedules go back to stock behaviour immediately.

## Reviewer notes

- **Customers pay more than today** on down-payment products: the full contractual interest (2H8W on 100: 20.00 instead of 15.00). Communicate before enabling.
- **2H8B is a "per month" product**: with all 16 repayments its loan runs longer, so its interest rises from 27.58 to 31.56 on 100.
- The last repayment absorbs rounding, as elsewhere in Fineract: it can be a few units larger or smaller than the others (2H8B on 250: 15 × 17.00 and 23.90).
- Products whose "installments in multiples of" is not set (H6BW) get cent amounts (8.34 × 11 and 8.26).
