"""End-to-end checks for the down-payment change ("down-payment-spread-remaining-principal") on a TEST instance.

Uses the helpers of e2e.py (FINERACT_BASE, FINERACT_CLIENT). Run on an instance prepared with setup_test_instance.py.
"""
import datetime as dt
from decimal import Decimal

import e2e
from e2e import DAY, call, check, config, d, fmt, loan, products, results, set_config, show

SETTING = "down-payment-spread-remaining-principal"


def body(sn, disb, principal):
    b = e2e.loan_body(sn, disb)
    b["principal"] = principal
    return b


def preview(sn, disb, principal=100):
    return call("POST", "loans?command=calculateLoanSchedule", body(sn, disb, principal))


def periods(schedule):
    ps = schedule["repaymentSchedule"]["periods"] if "repaymentSchedule" in schedule else schedule["periods"]
    return [p for p in ps if p.get("period")]


def regular(schedule):
    return [p for p in periods(schedule) if not p.get("downPaymentPeriod")]


def down_payment(schedule):
    return sum(Decimal(str(p["principalDue"])) for p in periods(schedule) if p.get("downPaymentPeriod"))


def total(ps, key):
    return sum(Decimal(str(p.get(key) or 0)) for p in ps)


def dates(ps):
    return [dt.date(*p["dueDate"]) for p in ps]


def amounts(ps):
    return " ".join(f"{Decimal(str(p['principalDue'])) + Decimal(str(p['interestDue'])):g}" for p in ps)


def create_and_disburse(sn, disb, principal):
    lid = call("POST", "loans", body(sn, disb, principal))["loanId"]
    app = loan(lid)
    call("POST", f"loans/{lid}?command=approve", {"approvedOnDate": fmt(disb), "expectedDisbursementDate": fmt(disb),
                                                  "dateFormat": "dd MMMM yyyy", "locale": "en"})
    call("POST", f"loans/{lid}?command=disburse", {"actualDisbursementDate": fmt(disb), "transactionAmount": principal,
                                                   "dateFormat": "dd MMMM yyyy", "locale": "en"})
    return lid, app, loan(lid)


def main():
    disb = d("2026-10-12")  # Monday
    # start without the test holidays of the twice-weekly runs
    for h in call("GET", "holidays?officeId=1"):
        if h["name"].startswith("Test holiday") and h["status"]["value"] != "Deleted":
            call("DELETE", f"holidays/{h['id']}")
            print(f"       removed {h['name']}")
    c = config(SETTING)
    check(f"Setting '{SETTING}' exists and is off after installation", not c["enabled"], c.get("description", ""))
    set_config("repayment-steps-in-working-days", True)

    ps = products()
    info = {sn: call("GET", f"loanproducts/{p['id']}") for sn, p in ps.items()}
    dp = sorted(sn for sn, q in info.items() if q.get("enableDownPayment"))
    no_dp = sorted(sn for sn in info if sn not in dp)
    print(f"       {len(dp)} down-payment products: {' '.join(dp)}")

    # 1. setting off: stock behaviour, and remember every schedule for the comparison below
    set_config(SETTING, False)
    off = {sn: preview(sn, disb) for sn in info}
    short = [sn for sn in dp if len(regular(off[sn])) < info[sn]["numberOfRepayments"]]
    check("Setting OFF: stock schedules, down-payment products still short (the bug)", len(short) > 0,
          ", ".join(f"{sn} {len(regular(off[sn]))}/{info[sn]['numberOfRepayments']}" for sn in short))

    # 2. setting on: every down-payment product gets all its repayments
    set_config(SETTING, True)
    on = {sn: preview(sn, disb) for sn in info}
    for sn in dp:
        q, s = info[sn], on[sn]
        reg = regular(s)
        whole_term = q["interestRateFrequencyType"]["value"] == "Whole term"
        expected_interest = Decimal(str(q["interestRatePerPeriod"])) if whole_term else None
        interest = total(reg, "interestDue")
        ok = (len(reg) == q["numberOfRepayments"] and down_payment(s) == Decimal("20")
              and total(reg, "principalDue") == Decimal("80")
              and (expected_interest is None or abs(interest - expected_interest) <= Decimal("0.01") * len(reg)))
        check(f"{sn} {q['name']}: {len(reg)}/{q['numberOfRepayments']} repayments, down payment {down_payment(s):g}, "
              f"principal {total(reg, 'principalDue'):g}, interest {interest:g}"
              + (f" (expected {expected_interest:g})" if expected_interest is not None else " (per-month rate)"),
              ok, f"was {len(regular(off[sn]))} repayments, interest {total(regular(off[sn]), 'interestDue'):g} | installments {amounts(reg)}")

    # 3. products without a down payment: identical schedules with the setting on and off
    same = [sn for sn in no_dp if [(p["dueDate"], p["principalDue"], p["interestDue"]) for p in periods(on[sn])]
            == [(p["dueDate"], p["principalDue"], p["interestDue"]) for p in periods(off[sn])]]
    check(f"The {len(no_dp)} products without down payment are unchanged", len(same) == len(no_dp),
          f"different: {sorted(set(no_dp) - set(same))}")

    # 4. other loan amounts, within each product's limits
    for sn, n_text in (("H6BW", "12"), ("2H8B", "16"), ("2H8W", "8"), ("15H8", "8")):
        q = info[sn]
        lo, hi = Decimal(str(q.get("minPrincipal") or 1)), Decimal(str(q.get("maxPrincipal") or 100000))
        for amount in (Decimal("55"), Decimal("250"), Decimal("1000"), Decimal("1234")):
            if not lo <= amount <= hi:
                continue
            s = preview(sn, disb, float(amount))
            reg = regular(s)
            check(f"{sn} with {amount:g}: {len(reg)}/{n_text} repayments, principal adds up",
                  len(reg) == q["numberOfRepayments"] and total(reg, "principalDue") + down_payment(s) == amount,
                  f"down payment {down_payment(s):g}, installments {amounts(reg)}")

    # 5. real loans: application schedule = disbursed schedule, down payment collected at disbursement
    for sn in ("2H8W", "2HB4", "HBW4", "H6BW"):
        lid, app, after = create_and_disburse(sn, disb, 100)
        reg_app, reg_after = regular(app), regular(after)
        same_schedule = ([(p["dueDate"], p["principalDue"], p["interestDue"]) for p in reg_app]
                         == [(p["dueDate"], p["principalDue"], p["interestDue"]) for p in reg_after])
        txs = after.get("transactions") or call("GET", f"loans/{lid}?associations=transactions").get("transactions", [])
        dp_paid = sum(Decimal(str(t["amount"])) for t in txs if t["type"].get("downPayment") or "down" in t["type"]["value"].lower())
        check(f"{sn} loan {lid} disbursed Mon 12 Oct: {len(reg_after)}/{info[sn]['numberOfRepayments']} repayments, "
              f"application = disbursed, down payment {dp_paid:g} collected",
              same_schedule and len(reg_after) == info[sn]["numberOfRepayments"] and dp_paid == Decimal("20"),
              show(dates(reg_after)) + " | " + amounts(reg_after))
        if sn == "2HB4":
            pairs = all(x.weekday() == (3 if i % 2 == 0 else 0) for i, x in enumerate(dates(reg_after)))
            check("2HB4 with both Nia settings: regular repayments alternate Thu/Mon", pairs, show(dates(reg_after)))
            first = reg_after[0]
            before = [(p["dueDate"], p["principalDue"]) for p in regular(loan(lid))]
            call("POST", f"loans/{lid}/transactions?command=repayment",
                 {"transactionDate": fmt(dt.date(*first["dueDate"])), "transactionAmount": first["totalDueForPeriod"],
                  "dateFormat": "dd MMMM yyyy", "locale": "en"})
            check("A repayment on the 2HB4 loan leaves the schedule unchanged",
                  before == [(p["dueDate"], p["principalDue"]) for p in regular(loan(lid))])
            keep_lid = lid

    # 6. switching the setting off again: new schedules short again, existing loans keep their schedule
    set_config(SETTING, False)
    again = preview("2H8W", disb)
    existing = regular(loan(keep_lid))
    set_config(SETTING, True)
    check("Setting OFF again: new 2H8W schedule back to stock (6 repayments), existing 2HB4 loan keeps 8",
          len(regular(again)) == 6 and len(existing) == 8, f"new {len(regular(again))}, existing {len(existing)}")

    passed = sum(ok for _, ok in results)
    print(f"\n{passed}/{len(results)} checks passed")


if __name__ == "__main__":
    main()
