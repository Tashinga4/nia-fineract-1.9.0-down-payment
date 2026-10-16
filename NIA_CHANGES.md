# What Nia changed in Fineract 1.9.0

This repository is **Apache Fineract 1.9.0** (the version on Nia's live server) with a few changes made by Nia IT. This page explains each change in plain words and lists every file that was touched.

## Summary

| # | Change | What it fixes | Turned on by | Status |
|---|---|---|---|---|
| 1 | Twice-weekly repayments | Repayments on bi-weekly products drift through the week instead of staying on fixed days (Mon/Thu, Tue/Fri, Wed/Sat) | Setting `repayment-steps-in-working-days` | In `main`, tested, not in production |
| 2 | Down-payment loans | Loans on HP and Hilton products get fewer repayments, and less interest, than the product says | Setting `down-payment-spread-remaining-principal` | Pull request into `main`, tested, not in production |
| 3 | First due date fix | The first due date can differ between loan application and disbursement | Always on (official Apache fix) | In `main` |
| 4 | Build fix | The project would not build because a documentation tool can no longer be downloaded | Always on | In `main` |

**Safe to install:** both settings are **off** after installation, so nothing changes for customers until Nia IT switches them on. Switching a setting off again restores the old behaviour for new loans. Loans already running keep their schedule.

---

## 1. Twice-weekly repayments

**What was wrong:** Nia's bi-weekly products repay every 3 days, with Sunday as a day off. Fineract moved a payment that fell on a Sunday to Monday, but then counted the next 3 days from the Sunday. Over a few weeks the payments wandered through every day of the week.

**What we changed:** with the setting on, "every 3 days" counts **working days only** (Monday to Saturday). The payments then stay on fixed pairs: Mon/Thu, Tue/Fri or Wed/Sat. Weekly and monthly products are not affected.

**Files changed** (commit `8d971bf`):

| File | Module | What changed |
|---|---|---|
| `WorkingDaysUtil.java` | fineract-core | Added functions that move a date forward or back by a number of working days, skipping days off |
| `ConfigurationDomainService.java` | fineract-core | Added a check for the new setting |
| `ConfigurationDomainServiceJpa.java` | fineract-provider | Reads the new setting from the database |
| `HolidayDetailDTO.java` | fineract-loan | Carries the setting to the schedule calculation |
| `DefaultScheduledDateGenerator.java` | fineract-provider | **The main fix:** calculates each due date in working days when the setting is on |
| `LoanScheduleAssembler.java` | fineract-provider | Uses the same rule for the first repayment, so the schedule at application matches the one at disbursement |
| `LoanUtilService.java` | fineract-provider | Passes the setting on when a schedule is recalculated later |
| `changelog-tenant.xml` | fineract-provider | Registers the new database update |
| `0141_add_configuration_repayment_steps_in_working_days.xml` | fineract-provider | **New.** Database update that adds the setting, switched off |
| `DefaultScheduledDateGeneratorWorkingDayStepsTest.java` | fineract-provider | **New.** 20 automated tests |

## 2. Down-payment loans keep every repayment

**What was wrong:** on products with a down payment (all HP products and Hilton), Fineract worked out each repayment as if the **whole** loan still had to be paid, forgetting the down payment already taken. The repayments were too big, so the loan was paid off early. Customers got fewer repayments than the product says and, because interest is charged per repayment, **less interest** too.

| Product | Repayments it should have | Repayments it had | Interest on 100, before → after |
|---|---|---|---|
| 2HB4, 2H8W, Hilton HBW4 | 8 | 6 | 15.00 → 20.00 |
| HP4B, 15H8 | 8 | 7 | 13.16 → 15.00 |
| 2H6W | 6 | 5 | 16.65 → 20.00 |
| H6BW | 12 | 10 | 16.70 → 20.00 |
| 2H8B | 16 | 14 | 27.58 → 31.56 |
| 5H8B | 16 | 14 | 13.16 → 15.00 |

**What we changed:** with the setting on, the down payment is still collected on the day of disbursement, and **what is left** is shared over **all** the repayments the product has. Each repayment is a round amount, and only the last one may differ slightly so the total is exact. Products without a down payment are not affected.

**Files changed** (commit `f9f1159`, branch `nia/down-payment-full-schedule`):

| File | Module | What changed |
|---|---|---|
| `LoanApplicationTerms.java` | fineract-loan | **The main fix:** takes off the down payment before dividing the loan into repayments, and rounds each repayment as a whole |
| `ConfigurationDomainService.java` | fineract-core | Added a check for the new setting |
| `ConfigurationDomainServiceJpa.java` | fineract-provider | Reads the new setting from the database |
| `HolidayDetailDTO.java` | fineract-loan | Carries the setting to the schedule calculation |
| `LoanScheduleAssembler.java` | fineract-provider | Passes the setting on when a loan is applied for |
| `LoanUtilService.java` | fineract-provider | Passes the setting on when a schedule is recalculated, so application and disbursement agree |
| `changelog-tenant.xml` | fineract-provider | Registers the new database update |
| `0142_add_configuration_down_payment_spread_remaining_principal.xml` | fineract-provider | **New.** Database update that adds the setting, switched off |
| `DownPaymentSpreadRemainingPrincipalTest.java` | fineract-provider | **New.** Automated tests for Nia's down-payment products |

## 3. First due date fix

**What was wrong:** when a loan was submitted, Fineract could calculate the first due date from the wrong starting date, so the schedule shown at application did not match the one after disbursement.

**What we changed:** copied the official Apache Fineract fix (FINERACT-1971) from a newer version into 1.9.0.

**Files changed** (commit `b3906ee`):

| File | Module | What changed |
|---|---|---|
| `LoanApplicationTerms.java` | fineract-loan | Uses the correct starting date for the schedule |
| `LoanScheduleAssembler.java` | fineract-provider | Calculates the first repayment date the same way as the official fix |

## 4. Build fix

**What was wrong:** the project would not build, because a tool used only to produce the documentation can no longer be downloaded.

**What we changed:** switched off the documentation build, as Fineract 1.10.1 also does. The application itself is not affected.

**Files changed** (commit `555a23e`):

| File | What changed |
|---|---|
| `build.gradle` | Documentation plugins switched off |
| `settings.gradle` | Documentation module switched off |

## Documentation and test scripts

| File | Contents |
|---|---|
| `README.md` | A note at the top pointing to the Nia documentation. The rest is the Apache Fineract README. |
| `NIA_CHANGES.md` | This page |
| `nia/README.md` | Overview of the down-payment work |
| `nia/PULL_REQUEST.md` | Full technical description of the down-payment fix and how to deploy it |
| `nia/release-notes.md` | Release notes for the Nia team |
| `nia/e2e/*.py` | Scripts that create test loans on a local test server and check their schedules |
| `nia/e2e/*.log` | Test results: down payment 37 of 37 checks pass, twice-weekly 20 of 20 pass |

## How to switch the changes on

In the Mifos web app go to **System → Configurations → Global Configurations** and enable:

- `repayment-steps-in-working-days` for twice-weekly repayments (Sunday must be set as a non-working day under Working Days)
- `down-payment-spread-remaining-principal` for the down-payment fix

Test with one loan first. Disabling a setting restores the old behaviour for new loans straight away.

---

<details>
<summary>Full paths of the changed source files</summary>

```
fineract-core/src/main/java/org/apache/fineract/infrastructure/configuration/domain/ConfigurationDomainService.java
fineract-core/src/main/java/org/apache/fineract/organisation/workingdays/service/WorkingDaysUtil.java
fineract-loan/src/main/java/org/apache/fineract/portfolio/loanaccount/data/HolidayDetailDTO.java
fineract-loan/src/main/java/org/apache/fineract/portfolio/loanaccount/loanschedule/domain/LoanApplicationTerms.java
fineract-provider/src/main/java/org/apache/fineract/infrastructure/configuration/domain/ConfigurationDomainServiceJpa.java
fineract-provider/src/main/java/org/apache/fineract/portfolio/loanaccount/loanschedule/domain/DefaultScheduledDateGenerator.java
fineract-provider/src/main/java/org/apache/fineract/portfolio/loanaccount/loanschedule/service/LoanScheduleAssembler.java
fineract-provider/src/main/java/org/apache/fineract/portfolio/loanaccount/service/LoanUtilService.java
fineract-provider/src/main/resources/db/changelog/tenant/changelog-tenant.xml
fineract-provider/src/main/resources/db/changelog/tenant/parts/0141_add_configuration_repayment_steps_in_working_days.xml
fineract-provider/src/main/resources/db/changelog/tenant/parts/0142_add_configuration_down_payment_spread_remaining_principal.xml
fineract-provider/src/test/java/org/apache/fineract/portfolio/loanaccount/loanschedule/domain/DefaultScheduledDateGeneratorWorkingDayStepsTest.java
fineract-provider/src/test/java/org/apache/fineract/portfolio/loanaccount/loanschedule/domain/DownPaymentSpreadRemainingPrincipalTest.java
build.gradle
settings.gradle
```

</details>
