"""End-to-end checks for the twice-weekly (working-day) repayment change on a test instance (FINERACT_BASE)."""
import base64, datetime as dt, json, ssl, sys, time, urllib.error, urllib.request

import os
BASE = os.environ.get("FINERACT_BASE", "https://localhost:8444/fineract-provider/api/v1")
CTX = ssl._create_unverified_context()
AUTH = base64.b64encode(b"mifos:password").decode()
CLIENT = int(os.environ.get("FINERACT_CLIENT", "2"))
BIWEEKLY = ["HP4B", "25wb", "W6WB", "H6BW", "WC4B", "2W5B", "G4WB", "G5WB", "SFBW", "2W6B", "5G5B", "2HB4", "2H8B", "5H8B"]
PARTNER = {0: 3, 1: 4, 2: 5, 3: 0, 4: 1, 5: 2}
DAY = "Mon Tue Wed Thu Fri Sat Sun".split()
results = []


def call(method, path, body=None):
    req = urllib.request.Request(f"{BASE}/{path}", method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Basic " + AUTH, "Fineract-Platform-TenantId": "default",
                                          "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, context=CTX, timeout=120) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            msgs = [x.get("defaultUserMessage") for x in json.loads(raw).get("errors", [])] or [json.loads(raw).get("defaultUserMessage")]
        except Exception:
            msgs = [raw[:300]]
        raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {msgs}")


def fmt(d): return d.strftime("%d %B %Y")
def d(s): return dt.date.fromisoformat(s)
def show(ds): return " ".join(f"{DAY[x.weekday()]} {x.day:02d}/{x.month:02d}" for x in ds)


def check(name, ok, detail=""):
    results.append((name, ok))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"\n       {detail}" if detail else ""))


def products():
    return {p["shortName"]: p for p in call("GET", "loanproducts")}


def config(name):
    return next(c for c in call("GET", "configurations")["globalConfiguration"] if c["name"] == name)


def set_config(name, enabled):
    call("PUT", f"configurations/{config(name)['id']}", {"enabled": enabled})


def loan_body(sn, disb, expected_first=None):
    p = call("GET", f"loanproducts/{products()[sn]['id']}")
    every, n = p["repaymentEvery"], p["numberOfRepayments"]
    body = {"clientId": CLIENT, "productId": p["id"], "loanType": "individual", "principal": 100,
            "loanTermFrequency": every * n, "loanTermFrequencyType": p["repaymentFrequencyType"]["id"], "numberOfRepayments": n,
            "repaymentEvery": every, "repaymentFrequencyType": p["repaymentFrequencyType"]["id"],
            "interestRatePerPeriod": p["interestRatePerPeriod"], "amortizationType": 1, "interestType": 1,
            "interestCalculationPeriodType": 1, "transactionProcessingStrategyCode": p["transactionProcessingStrategyCode"],
            "expectedDisbursementDate": fmt(disb), "submittedOnDate": fmt(disb), "dateFormat": "dd MMMM yyyy", "locale": "en"}
    if expected_first:
        body["repaymentsStartingFromDate"] = fmt(expected_first)
    return body


def due_dates(loan_or_preview):
    periods = loan_or_preview["repaymentSchedule"]["periods"] if "repaymentSchedule" in loan_or_preview else loan_or_preview["periods"]
    # the down payment is its own period on the disbursement date; only regular repayments follow the pair
    return [dt.date(*p["dueDate"]) for p in periods if p.get("period") and not p.get("downPaymentPeriod")]


def preview(sn, disb):
    return call("POST", "loans?command=calculateLoanSchedule", loan_body(sn, disb))


def loan(loan_id):
    return call("GET", f"loans/{loan_id}?associations=repaymentSchedule")


def pairs_ok(disb, ds, first_weekday=None):
    """Alternating pair, 3-4 day gaps after the first repayment, never Sunday."""
    expected = PARTNER[disb.weekday()] if first_weekday is None else first_weekday
    prev = disb
    for i, x in enumerate(ds):
        if x.weekday() != expected or (i > 0 and (x - prev).days not in (3, 4)):
            return False
        expected, prev = PARTNER[expected], x
    return True


def create_and_disburse(sn, disb, actual=None):
    lid = call("POST", "loans", loan_body(sn, disb))["loanId"]
    at_application = due_dates(loan(lid))
    call("POST", f"loans/{lid}?command=approve", {"approvedOnDate": fmt(disb), "expectedDisbursementDate": fmt(disb),
                                                  "dateFormat": "dd MMMM yyyy", "locale": "en"})
    call("POST", f"loans/{lid}?command=disburse", {"actualDisbursementDate": fmt(actual or disb), "transactionAmount": 100,
                                                   "dateFormat": "dd MMMM yyyy", "locale": "en"})
    return lid, at_application, due_dates(loan(lid))


def run_job(display_name):
    job = next(j for j in call("GET", "jobs") if j["displayName"] == display_name)
    before = (job.get("lastRunHistory") or {}).get("version", 0)
    call("POST", f"jobs/{job['jobId']}?command=executeJob", {})
    for _ in range(60):
        time.sleep(2)
        hist = call("GET", f"jobs/{job['jobId']}").get("lastRunHistory") or {}
        if hist.get("version", 0) > before:
            return hist.get("status")
    return "timeout"


def main():
    # 1. setting
    set_config("repayment-steps-in-working-days", True)
    c = config("repayment-steps-in-working-days")
    check("Setting 'repayment-steps-in-working-days' exists and is enabled", c["enabled"], c.get("description", ""))
    wd = call("GET", "workingdays")
    check("Working days are Mon-Sat, move to next working day", wd["recurrence"].endswith("BYDAY=MO,TU,WE,TH,FR,SA")
          and wd["repaymentRescheduleType"]["id"] == 2, wd["recurrence"])

    # 2. convert the 14 bi-weekly products (not Hilton)
    ps = products()
    for sn in BIWEEKLY:
        call("PUT", f"loanproducts/{ps[sn]['id']}", {"repaymentFrequencyType": 0, "repaymentEvery": 3, "locale": "en"})
    ps = products()
    converted = [sn for sn in BIWEEKLY if ps[sn]["repaymentFrequencyType"]["id"] == 0 and ps[sn]["repaymentEvery"] == 3]
    check("14 bi-weekly products converted to every 3 days", len(converted) == 14, f"{len(converted)}: {converted}")
    others = [sn for sn in ps if sn not in BIWEEKLY]
    check("Hilton and the 15 weekly products still repay in weeks",
          all(ps[sn]["repaymentFrequencyType"]["id"] == 1 for sn in others) and ps["HBW4"]["repaymentEvery"] == 2,
          f"{len(others)} products, HBW4 every {ps['HBW4']['repaymentEvery']} weeks")

    # 3. real loans for every disbursement day, disbursed on the expected date (the case where Fineract keeps the
    #    application schedule) - application schedule must equal the disbursed one and follow the pair
    loans_by_day = {}
    for i in range(6):
        disb = d("2026-10-05") + dt.timedelta(days=i)
        lid, app, after = create_and_disburse("2HB4", disb)
        loans_by_day[disb.weekday()] = lid
        check(f"2HB4 disbursed {DAY[disb.weekday()]} {disb:%d %b}: application = disbursed schedule, alternating pair",
              app == after and pairs_ok(disb, after) and len(after) == 8, show(after))

    # 4. minimum 7 days before the first repayment
    lid, app, after = create_and_disburse("G4WB", d("2026-10-05"))
    check("G4WB (7-day minimum) disbursed Mon 05 Oct: first repayment Mon 12 Oct, then Mon/Thu",
          after[0] == d("2026-10-12") and pairs_ok(d("2026-10-05"), after, first_weekday=0) and app == after, show(after))

    # 5. disbursed a day later than expected: Fineract regenerates the schedule from the actual date
    lid, app, after = create_and_disburse("2HB4", d("2026-10-08"), actual=d("2026-10-09"))
    check("2HB4 expected Thu 08 Oct, disbursed Fri 09 Oct: regenerated as Tue/Fri", pairs_ok(d("2026-10-09"), after), show(after))

    # 6. a repayment does not move the schedule
    mon_loan = loans_by_day[0]
    before = due_dates(loan(mon_loan))
    first_due = loan(mon_loan)["repaymentSchedule"]["periods"][1]
    call("POST", f"loans/{mon_loan}/transactions?command=repayment",
         {"transactionDate": fmt(dt.date(*first_due["dueDate"])), "transactionAmount": first_due["totalDueForPeriod"],
          "dateFormat": "dd MMMM yyyy", "locale": "en"})
    after = due_dates(loan(mon_loan))
    check("Repayment on Thu 08 Oct leaves all due dates unchanged", before == after, show(after))

    # 7. Sunday disbursement is rejected
    try:
        preview("2HB4", d("2026-10-11"))
        check("Disbursement on Sunday 11 Oct is rejected", False, "preview was accepted")
    except RuntimeError as e:
        check("Disbursement on Sunday 11 Oct is rejected", "non working day" in str(e).lower() or "working" in str(e).lower(), str(e))

    # 8. products that must not change
    wk = due_dates(preview("WC4W", d("2026-10-05")))
    check("Weekly product WC4W unchanged: every Monday", all(x.weekday() == 0 for x in wk) and
          all((b - a).days == 7 for a, b in zip(wk, wk[1:])), show(wk))
    hil = due_dates(preview("HBW4", d("2026-10-05")))
    check("Hilton HBW4 unchanged: every 2 weeks", all((b - a).days == 14 for a, b in zip(hil, hil[1:])), show(hil))

    # 8b. switching the setting off restores stock behaviour, switching it on again restores the pairs
    set_config("repayment-steps-in-working-days", False)
    off = due_dates(preview("2HB4", d("2026-10-12")))
    set_config("repayment-steps-in-working-days", True)
    on = due_dates(preview("2HB4", d("2026-10-12")))
    check("Setting OFF: stock behaviour (dates cycle through the week)", not pairs_ok(d("2026-10-12"), off), show(off))
    check("Setting ON again: Mon/Thu pairs", pairs_ok(d("2026-10-12"), on), show(on))

    # 9. holidays (reschedule to next repayment date), Thursday 22 Oct 2026, all offices
    hid = call("POST", "holidays", {"name": "Test holiday Thu 22 Oct", "description": "twice-weekly test", "fromDate": "22 October 2026",
                                    "toDate": "22 October 2026", "reschedulingType": 1,
                                    "offices": [{"officeId": o["id"]} for o in call("GET", "offices")],
                                    "dateFormat": "dd MMMM yyyy", "locale": "en"})["resourceId"]
    call("POST", f"holidays/{hid}?command=activate", {})
    pv = due_dates(preview("2HB4", d("2026-10-12")))
    check("New loan Mon 12 Oct with holiday Thu 22 Oct: that payment moves to Mon 26, pair kept",
          pv == [d(x) for x in ("2026-10-15", "2026-10-19", "2026-10-26", "2026-10-29", "2026-11-02", "2026-11-05", "2026-11-09",
                                "2026-11-12")], show(pv))
    status = run_job("Apply Holidays To Loans")
    mon_after_job = due_dates(loan(mon_loan))
    check(f"Existing Mon 05 Oct loan after the holiday job ({status}): Thu 22 Oct moved to Mon 26, pair kept",
          mon_after_job == [d(x) for x in ("2026-10-08", "2026-10-12", "2026-10-15", "2026-10-19", "2026-10-26", "2026-10-29",
                                           "2026-11-02", "2026-11-05")], show(mon_after_job))
    for wdx, lid in sorted(loans_by_day.items()):
        ds = due_dates(loan(lid))
        check(f"Existing {DAY[wdx]}-disbursed loan after holiday job: still alternating, no date twice, nothing on 22 Oct",
              len(set(ds)) == len(ds) and d("2026-10-22") not in ds and all(x.weekday() != 6 for x in ds), show(ds))

    # 10. interest on the converted products
    for sn in ("2HB4", "WC4B", "2H8B"):
        p = preview(sn, d("2026-10-12"))
        print(f"       interest {sn}: total interest {p['totalInterestCharged']}, total repayment {p['totalRepaymentExpected']}, "
              f"{len(due_dates(p))} repayments, last {show(due_dates(p)[-1:])}")

    passed = sum(ok for _, ok in results)
    print(f"\n{passed}/{len(results)} checks passed")


if __name__ == "__main__":
    main()
