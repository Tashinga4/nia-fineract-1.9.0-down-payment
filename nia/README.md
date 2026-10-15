# Nia Fineract 1.9.0: down-payment loans keep every repayment

Internal Nia Financial Services work on Apache Fineract 1.9.0, the version the live server runs.

**Status:** implemented and tested on a Fineract 1.9.0 test instance (unit tests and end-to-end tests pass). **Not deployed to production.**

## The problem

Nia's **down-payment products** (all HP products and Hilton) get **fewer repayments than configured**: for example 2HB4, 2H8W and the Hilton product HBW4 get 6 of 8, H6BW 10 of 12, 2H8B and 5H8B 14 of 16. Because flat interest is charged per repayment, customers are also charged **less interest than the product's rate** (2H8W on 100: 15.00 instead of 20.00).

Cause: Fineract sizes each repayment as if the **whole** loan still had to be repaid, ignoring the down payment taken at disbursement, so the money left runs out early.

## The fix

A new global setting, **`down-payment-spread-remaining-principal`** (off by default). When it is on, the amount left after the down payment is spread over **all** the configured repayments, and each repayment is rounded as a whole so the count is kept. Interest stays flat on the full loan amount, as the product's rate says.

Details, numbers for every product and deployment steps: [PULL_REQUEST.md](PULL_REQUEST.md).

## Tests

- **Unit tests:** 22 new tests and all 710 unit tests in `fineract-core`, `fineract-loan` and `fineract-provider` pass.
- **End-to-end** on a 1.9.0 test instance with Nia's 30 products and real loans: **37/37 checks pass** (`e2e/e2e-190-down-payment.log`), and the twice-weekly checks still pass **20/20** (`e2e/e2e-190-run3-twice-weekly.log`).
- `e2e/setup_test_instance.py` prepares an empty test instance. It needs Nia's product import script next to it and refuses anything other than a localhost URL. The scripts use Fineract's default test login. Don't point them at production.

## Repository layout

| Branch | Contents |
|---|---|
| `main` | Apache Fineract 1.9.0 source (tag `1.9.0`, without upstream history), the Nia twice-weekly change from [nia-fineract-1.9.0](https://github.com/Tashinga4/nia-fineract-1.9.0), and this `nia/` folder |
| `nia/down-payment-full-schedule` | `main` + the down-payment fix (pull request into `main`) |

The twice-weekly change is in `main` because both changes touch the same settings code. Apply the twice-weekly pull request first, then this one.

## Related repositories

- [nia-fineract-1.9.0](https://github.com/Tashinga4/nia-fineract-1.9.0): twice-weekly repayments, Fineract 1.9.0
- [nia-fineract-1.10.1](https://github.com/Tashinga4/nia-fineract-1.10.1): twice-weekly repayments, Fineract 1.10.1
