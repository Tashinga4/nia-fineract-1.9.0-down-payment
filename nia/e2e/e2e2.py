"""End-to-end run 2: regular repayments only (down-payment rows excluded), plus holiday-job ordering checks."""
import datetime as dt
import sys

sys.path.insert(0, ".")
import e2e
from e2e import DAY, call, check, d, fmt, loan, pairs_ok, results, set_config, show


def regular(loan_or_preview):
    periods = loan_or_preview["repaymentSchedule"]["periods"] if "repaymentSchedule" in loan_or_preview else loan_or_preview["periods"]
    return [dt.date(*p["dueDate"]) for p in periods if p.get("period") and not p.get("downPaymentPeriod")]


e2e.due_dates = regular  # create_and_disburse uses it


def preview(sn, disb):
    return regular(e2e.preview(sn, disb))


def holiday(name, day):
    hid = call("POST", "holidays", {"name": name, "description": "twice-weekly e2e", "fromDate": fmt(day), "toDate": fmt(day),
                                    "reschedulingType": 1, "offices": [{"officeId": o["id"]} for o in call("GET", "offices")],
                                    "dateFormat": "dd MMMM yyyy", "locale": "en"})["resourceId"]
    call("POST", f"holidays/{hid}?command=activate", {})
    return hid


def main():
    set_config("repayment-steps-in-working-days", True)
    # remove run-1 test holidays so run 2 starts without holidays in October
    for h in call("GET", "holidays?officeId=1"):
        if h["name"].startswith("Test holiday") and h["status"]["value"] != "Deleted":
            call("DELETE", f"holidays/{h['id']}")
            print(f"       removed run-1 holiday {h['name']}")

    # A. every disbursement day, disbursed on the expected date (Fineract keeps the application schedule)
    by_day = {}
    for i in range(6):
        disb = d("2026-10-05") + dt.timedelta(days=i)
        lid, app, after = e2e.create_and_disburse("2W5B", disb)
        by_day[disb.weekday()] = lid
        check(f"2W5B disbursed {DAY[disb.weekday()]} {disb:%d %b}: 10 repayments, application = disbursed, alternating pair",
              len(after) == 10 and app == after and pairs_ok(disb, after), show(after))

    # B. down-payment product: down payment on disbursement day, then the pair
    lid, app, after = e2e.create_and_disburse("2HB4", d("2026-10-08"))
    check("2HB4 (down payment) disbursed Thu 08 Oct: regular repayments Mon/Thu", app == after and pairs_ok(d("2026-10-08"), after),
          show(after))

    # C. minimum 7 days
    lid, app, after = e2e.create_and_disburse("G4WB", d("2026-10-07"))
    check("G4WB (7-day minimum) disbursed Wed 07 Oct: first repayment Wed 14 Oct, then Sat/Wed",
          after[0] == d("2026-10-14") and pairs_ok(d("2026-10-07"), after, first_weekday=2) and app == after, show(after))

    # D. disbursed later than expected -> schedule regenerated from the actual date
    lid, app, after = e2e.create_and_disburse("2W5B", d("2026-10-08"), actual=d("2026-10-09"))
    check("2W5B expected Thu 08 Oct, disbursed Fri 09 Oct: regenerated as Tue/Fri", pairs_ok(d("2026-10-09"), after), show(after))

    # E. repayment does not move dates
    mon = by_day[0]
    before = regular(loan(mon))
    first = next(p for p in loan(mon)["repaymentSchedule"]["periods"] if p.get("period"))
    call("POST", f"loans/{mon}/transactions?command=repayment",
         {"transactionDate": fmt(dt.date(*first["dueDate"])), "transactionAmount": first["totalDueForPeriod"],
          "dateFormat": "dd MMMM yyyy", "locale": "en"})
    check("Repayment on Thu 08 Oct leaves every due date unchanged", regular(loan(mon)) == before, show(regular(loan(mon))))

    # F. setting off / on
    set_config("repayment-steps-in-working-days", False)
    off = preview("2W5B", d("2026-10-12"))
    set_config("repayment-steps-in-working-days", True)
    on = preview("2W5B", d("2026-10-12"))
    check("Setting OFF: stock behaviour, dates cycle through the week", not pairs_ok(d("2026-10-12"), off), show(off))
    check("Setting ON: Mon/Thu pairs", pairs_ok(d("2026-10-12"), on), show(on))

    # G. holiday Thu 22 Oct created AFTER the loans above exist, then the nightly holiday job runs
    holiday("Test holiday Thu 22 Oct run 2", d("2026-10-22"))
    pv = preview("2W5B", d("2026-10-12"))
    check("Preview Mon 12 Oct with holiday Thu 22 Oct: Thu 22 -> Mon 26, pair kept",
          pv[:4] == [d("2026-10-15"), d("2026-10-19"), d("2026-10-26"), d("2026-10-29")] and d("2026-10-22") not in pv, show(pv))
    # loans created after the holiday is active but before the job has processed it
    late_twice, _, late_twice_dates = e2e.create_and_disburse("2W5B", d("2026-10-12"))
    late_weekly, _, late_weekly_dates = e2e.create_and_disburse("WC4W", d("2026-10-08"))
    print(f"       before job: twice-weekly Mon 12 Oct loan {show(late_twice_dates)}")
    print(f"       before job: weekly WC4W Thu 08 Oct loan  {show(late_weekly_dates)}")
    status = e2e.run_job("Apply Holidays To Loans")
    mon_dates = regular(loan(mon))
    check(f"Holiday job ({status}) on existing Mon 05 Oct loan: Thu 22 -> Mon 26, pair kept, nothing doubled",
          d("2026-10-22") not in mon_dates and d("2026-10-26") in mon_dates and len(set(mon_dates)) == len(mon_dates)
          and all((b - a).days in (3, 4, 7) for a, b in zip(mon_dates, mon_dates[1:])), show(mon_dates))
    for wd, lid in sorted(by_day.items()):
        ds = regular(loan(lid))
        check(f"After job: {DAY[wd]}-disbursed loan has no payment on 22 Oct, no duplicates, no Sunday",
              d("2026-10-22") not in ds and len(set(ds)) == len(ds) and all(x.weekday() != 6 for x in ds), show(ds))
    tw, wk = regular(loan(late_twice)), regular(loan(late_weekly))
    print(f"[INFO] Loan created after holiday, before job - twice-weekly: {show(tw)}  on holiday: {d('2026-10-22') in tw}")
    print(f"[INFO] Loan created after holiday, before job - weekly WC4W: {show(wk)}  on holiday: {d('2026-10-22') in wk}")

    passed = sum(ok for _, ok in results)
    print(f"\n{passed}/{len(results)} checks passed")


if __name__ == "__main__":
    main()
