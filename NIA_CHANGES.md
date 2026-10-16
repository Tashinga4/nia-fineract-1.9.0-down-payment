# Nia changes to Apache Fineract 1.9.0

This page lists every file Nia changed on top of the Apache Fineract 1.9.0 source (tag `1.9.0`, commit `2624eea`), and what was changed in each one.

For the background, the numbers per product and the test results, see [nia/README.md](nia/README.md).

There are four changes:

| # | Change | Where | Commit |
|---|---|---|---|
| 1 | FINERACT-1971 backport: first due date when a loan is submitted | `main` | `b3906ee` |
| 2 | Twice-weekly repayments (count daily repayment steps in working days) | `main` | `8d971bf` |
| 3 | Disable the documentation build so the project builds | `main` | `555a23e` |
| 4 | Down-payment loans keep every configured repayment | branch `nia/down-payment-full-schedule` (pull request into `main`, not merged yet) | `f9f1159` |

Plus the `nia/` folder with documentation and test scripts (commit `3eca406`).

Both new behaviours (2 and 4) are behind global settings that are **off** when the update is installed. With both off, Fineract behaves like stock 1.9.0 plus the FINERACT-1971 fix.

All Java paths below start with `src/main/java/org/apache/fineract/` inside the named module, unless a full path is given.

---

## 1. FINERACT-1971 backport (commit `b3906ee`)

An upstream Apache Fineract fix (from commit `039bca4`, by Adam Saghy), copied back to 1.9.0. Without it, the first due date of a loan is calculated from the wrong date when the loan is submitted, so the schedule at application can differ from the one after disbursement. Only the application code was copied. The original integration tests were left out because they need test helpers that don't exist in 1.9.0.

| File | Change |
|---|---|
| `fineract-loan/.../portfolio/loanaccount/loanschedule/domain/LoanApplicationTerms.java` | The schedule's start ("seed") date is the disbursement date or the submitted-on date, depending on the product's repayment start date type. It only uses a different date when "repayments starting from" was entered on the loan. |
| `fineract-provider/.../portfolio/loanaccount/loanschedule/service/LoanScheduleAssembler.java` | `deriveFirstRepaymentDate` reworked to match the upstream fix. |

## 2. Twice-weekly repayments (commit `8d971bf`)

**Problem:** Nia's bi-weekly products must repay twice a week on fixed day pairs (Mon/Thu, Tue/Fri, Wed/Sat), with Sunday off. In stock Fineract a loan repaying "every 3 days" moves a payment that lands on Sunday, but counts the next date from the unmoved date. The payments then drift through the whole week.

**Fix:** a new global setting, **`repayment-steps-in-working-days`** (off by default). When it's on, loans repaying every N days without a meeting calendar count those N days in **working days**. With Monday to Saturday as working days, every 3 days gives fixed pairs. Weekly and monthly products, and loans with a calendar, don't change.

This was first made for Nia's Fineract 1.10.1 build and then moved to 1.9.0.

| File | Change |
|---|---|
| `fineract-core/.../organisation/workingdays/service/WorkingDaysUtil.java` | New helpers `plusWorkingDays`, `minusWorkingDays` and `nextWorkingDayOnOrAfter`. They skip non-working days and stop with an error if no day of the week is a working day. |
| `fineract-core/.../infrastructure/configuration/domain/ConfigurationDomainService.java` | New method `isRepaymentStepsInWorkingDaysEnabled()`. |
| `fineract-provider/.../infrastructure/configuration/domain/ConfigurationDomainServiceJpa.java` | Implements it by reading the `repayment-steps-in-working-days` setting. |
| `fineract-loan/.../portfolio/loanaccount/data/HolidayDetailDTO.java` | Carries the new flag (new constructor and `isRepaymentStepsInWorkingDays()`). The flag travels here because the date generator is also created outside Spring. |
| `fineract-provider/.../portfolio/loanaccount/loanschedule/domain/DefaultScheduledDateGenerator.java` | When the setting applies, the next due date and the ideal disbursement date step in working days. New method `isRepaymentStepInWorkingDays(...)` decides when it applies. |
| `fineract-provider/.../portfolio/loanaccount/loanschedule/service/LoanScheduleAssembler.java` | Sets the flag on `HolidayDetailDTO`. The first repayment date uses the same working-day rule, and the minimum-days date is moved onto a working day, so the schedule at application matches the one after disbursement. |
| `fineract-provider/.../portfolio/loanaccount/service/LoanUtilService.java` | Sets the flag on `HolidayDetailDTO` for schedules regenerated later (disbursement, reschedule). |
| `fineract-provider/src/main/resources/db/changelog/tenant/changelog-tenant.xml` | Includes the new changeset 0141. |
| `fineract-provider/src/main/resources/db/changelog/tenant/parts/0141_add_configuration_repayment_steps_in_working_days.xml` | **New.** Adds the `repayment-steps-in-working-days` global setting, disabled. The name matches the 1.10.1 build, so a later upgrade sees it as already applied. |
| `fineract-provider/src/test/java/.../portfolio/loanaccount/loanschedule/domain/DefaultScheduledDateGeneratorWorkingDayStepsTest.java` | **New.** 20 unit tests: every disbursement day, application vs regenerated schedule, both holiday types, setting off, 7-day week, weekly products, ideal disbursement date and the helpers. |

## 3. Disable the documentation build (commit `555a23e`)

The documentation (asciidoctor) build plugin needs a library that can no longer be downloaded, so the whole project failed to build. Fineract 1.10.1 does the same thing. The application itself isn't affected.

| File | Change |
|---|---|
| `build.gradle` | The six `org.asciidoctor.*` plugins are commented out. |
| `settings.gradle` | `include ':fineract-doc'` is commented out. |

## 4. Down-payment loans keep every repayment (commit `f9f1159`, branch `nia/down-payment-full-schedule`)

**Problem:** on products with a down payment (all HP products and Hilton), loans got **fewer repayments than configured**. For example, 2HB4, 2H8W and HBW4 got 6 of 8, H6BW got 10 of 12, and 2H8B and 5H8B got 14 of 16. Interest is flat per repayment, so customers were also charged less interest than the product's rate (2H8W on 100: 15.00 instead of 20.00). The cause: each repayment was sized for the **full** loan, although the down payment had already been taken off. On top of that, principal and interest were each rounded up separately.

**Fix:** a new global setting, **`down-payment-spread-remaining-principal`** (off by default). When it's on, for loans with a down payment (not multi-disbursement):
- the amount left after the down payment is spread over **all** the configured repayments;
- the whole repayment (principal + interest) is rounded to the product's "installment in multiples of" amount. If that would still pay the loan off early, it's rounded down and the last repayment takes the remainder.

Interest stays flat on the full loan amount. Loans without a down payment, declining-balance loans and loans already running don't change.

| File | Change |
|---|---|
| `fineract-loan/.../portfolio/loanaccount/loanschedule/domain/LoanApplicationTerms.java` | The main fix. New `principalToSpreadOverInstallments()` takes the down payment off before dividing. New `roundInstallmentKeepingRepaymentCount(...)` rounds the whole repayment. New `isDownPaymentSpreadOverRemainingInstallments()` decides when it applies. |
| `fineract-core/.../infrastructure/configuration/domain/ConfigurationDomainService.java` | New method `isDownPaymentSpreadRemainingPrincipalEnabled()`. |
| `fineract-provider/.../infrastructure/configuration/domain/ConfigurationDomainServiceJpa.java` | Implements it by reading the `down-payment-spread-remaining-principal` setting. |
| `fineract-loan/.../portfolio/loanaccount/data/HolidayDetailDTO.java` | Carries the new flag next to the twice-weekly one (new constructor and `isDownPaymentSpreadRemainingPrincipal()`). |
| `fineract-provider/.../portfolio/loanaccount/loanschedule/service/LoanScheduleAssembler.java` | Passes the setting into `HolidayDetailDTO` for the schedule at application. |
| `fineract-provider/.../portfolio/loanaccount/service/LoanUtilService.java` | Passes the setting into `HolidayDetailDTO` for schedules regenerated later, so application and disbursement give the same schedule. |
| `fineract-provider/src/main/resources/db/changelog/tenant/changelog-tenant.xml` | Includes the new changeset 0142. |
| `fineract-provider/src/main/resources/db/changelog/tenant/parts/0142_add_configuration_down_payment_spread_remaining_principal.xml` | **New.** Adds the `down-payment-spread-remaining-principal` global setting, disabled. |
| `fineract-provider/src/test/java/.../portfolio/loanaccount/loanschedule/domain/DownPaymentSpreadRemainingPrincipalTest.java` | **New.** Unit tests for the Nia down-payment products, setting on and off. |

## Documentation and tests (commit `3eca406`)

| File | Contents |
|---|---|
| `README.md` | A short note at the top pointing to the Nia documentation. The rest is the Apache Fineract README. |
| `nia/README.md` | Project overview: the problem, the fix, tests and branches. |
| `nia/PULL_REQUEST.md` | Full description of the down-payment fix, with numbers for every product and deployment steps. |
| `nia/release-notes.md` | Release notes for the Nia team. |
| `nia/e2e/setup_test_instance.py` | Prepares an empty local 1.9.0 test instance. It only accepts a localhost URL. |
| `nia/e2e/e2e.py`, `nia/e2e/e2e2.py` | End-to-end checks of the twice-weekly schedules. |
| `nia/e2e/e2e_down_payment.py` | End-to-end checks of the down-payment schedules. |
| `nia/e2e/e2e-190-down-payment.log` | Down-payment run: 37/37 checks pass. |
| `nia/e2e/e2e-190-run3-twice-weekly.log` | Twice-weekly run: 20/20 checks pass. |
| `NIA_CHANGES.md` | This page. |

## Switching the changes on

Both settings are under **System → Configurations → Global Configurations** in the Mifos web app, or through `PUT /fineract-provider/api/v1/configurations/{id}`:

- `repayment-steps-in-working-days`: twice-weekly repayments (needs Sunday set as a non-working day under Working Days)
- `down-payment-spread-remaining-principal`: down-payment fix

Switching a setting off restores the old behaviour for new loans straight away. Loans already running keep their current schedule.
