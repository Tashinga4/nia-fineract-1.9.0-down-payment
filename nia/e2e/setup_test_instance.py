"""Prepare an EMPTY Fineract TEST instance for the twice-weekly end-to-end checks.

Sets production working days and calendar settings, creates placeholder fund, charges, GL accounts and delinquency
bucket so the Nia product import can run, imports the 30 Nia loan products, converts the 14 bi-weekly ones to every
3 days and creates one test client.
Never point this at production: FINERACT_BASE must be a local test instance.
"""
import base64, json, os, ssl, subprocess, sys, urllib.error, urllib.request
from pathlib import Path

IMPORT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(IMPORT_DIR))
import import_nia_loan_products as m  # noqa: E402

BASE = os.environ["FINERACT_BASE"]
if "localhost" not in BASE:
    sys.exit("FINERACT_BASE must be a local test instance")
CTX = ssl._create_unverified_context()
AUTH = base64.b64encode(b"mifos:password").decode()


def call(method, path, body=None):
    req = urllib.request.Request(f"{BASE}/{path}", method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Basic " + AUTH, "Fineract-Platform-TenantId": "default",
                                          "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, context=CTX, timeout=120) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {e.read().decode()[:400]}")


def names(path):
    r = call("GET", path)
    return {x["name"] for x in (r["pageItems"] if isinstance(r, dict) else r)}


# 1. working days and global settings as in production
prod = json.loads((IMPORT_DIR / "prod_calendar_settings.json").read_text())
call("PUT", "workingdays", {"recurrence": "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR,SA", "repaymentRescheduleType": 2,
                            "extendTermForDailyRepayments": prod["workingDays"]["extendTermForDailyRepayments"],
                            "extendTermForRepaymentsOnHolidays": prod["workingDays"]["extendTermForRepaymentsOnHolidays"],
                            "locale": "en"})
wanted = {c["name"]: c["enabled"] for c in prod["globalSettings"]}
for c in call("GET", "configurations")["globalConfiguration"]:
    if c["name"] in wanted and wanted[c["name"]] != c["enabled"]:
        call("PUT", f"configurations/{c['id']}", {"enabled": wanted[c["name"]]})
        print("setting", c["name"], wanted[c["name"]])

# 2. placeholders the product import looks up by name or code
_, specs = m.load_source(m.DEFAULT_INPUT)
if "Loan Funding" not in names("funds"):
    call("POST", "funds", {"name": "Loan Funding"})
tmpl = call("GET", "charges/template")
calc = {o["value"]: o["id"] for o in tmpl["loanChargeCalculationTypeOptions"]}
when = {o["value"]: o["id"] for o in tmpl["loanChargeTimeTypeOptions"]}
have = names("charges")
for s in specs:
    for c in s["charges"]:
        if c["name"] not in have:
            call("POST", "charges", {"name": c["name"], "chargeAppliesTo": 1, "currencyCode": "USD", "chargeTimeType": when[c["collectedOn"]],
                                     "chargeCalculationType": calc[c["type"]], "chargePaymentMode": 0, "amount": c["amount"],
                                     "active": True, "penalty": False, "locale": "en"})
            have.add(c["name"])


def gl_type(field):  # 1 asset, 2 liability, 4 income, 5 expense
    if field in ("fundSourceAccountId", "loanPortfolioAccountId", "transfersInSuspenseAccountId"):
        return 1
    if field == "overpaymentLiabilityAccountId":
        return 2
    if field.startswith(("interestOn", "incomeFrom")):
        return 4
    return 5


codes = {}
for s in specs:
    for f, code in s.get("accountCodes", {}).items():
        codes.setdefault(code, gl_type(f))
existing = {a.get("glCode") for a in call("GET", "glaccounts")}
for code, t in sorted(codes.items()):
    if code not in existing:
        call("POST", "glaccounts", {"name": f"Test account {code}", "glCode": code, "type": t, "usage": 1, "manualEntriesAllowed": True})
if "Par 1-30" not in names("delinquency/buckets"):
    rid = call("POST", "delinquency/ranges", {"classification": "Par 1-30 range", "minimumAgeDays": 1, "maximumAgeDays": 30,
                                              "locale": "en"})["resourceId"]
    call("POST", "delinquency/buckets", {"name": "Par 1-30", "ranges": [rid]})

# 3. the 30 Nia products, with the existing import script
r = subprocess.run([sys.executable, str(IMPORT_DIR / "import_nia_loan_products.py"), "--base-url", BASE, "--insecure", "--apply"],
                   input="mifos\npassword\n", capture_output=True, text=True)
print(r.stdout.strip().splitlines()[-1] if r.stdout.strip() else r.stderr)

# 4. as in the deployment instructions: the 14 bi-weekly products repay every 3 days (not Hilton, HBW4)
BIWEEKLY = ["HP4B", "25wb", "W6WB", "H6BW", "WC4B", "2W5B", "G4WB", "G5WB", "SFBW", "2W6B", "5G5B", "2HB4", "2H8B", "5H8B"]
by_short_name = {p["shortName"]: p for p in call("GET", "loanproducts")}
for sn in BIWEEKLY:
    call("PUT", f"loanproducts/{by_short_name[sn]['id']}", {"repaymentFrequencyType": 0, "repaymentEvery": 3, "locale": "en"})
print(f"converted {len(BIWEEKLY)} bi-weekly products to every 3 days")

# 5. one test client
clients = call("GET", "clients?limit=1")
if clients["totalFilteredRecords"] == 0:
    cid = call("POST", "clients", {"officeId": 1, "legalFormId": 1, "firstname": "Test", "lastname": "Twice-Weekly", "active": True,
                                   "activationDate": "01 September 2026", "submittedOnDate": "01 September 2026",
                                   "dateFormat": "dd MMMM yyyy", "locale": "en"})["clientId"]
else:
    cid = clients["pageItems"][0]["id"]
print(f"test client id {cid} (use FINERACT_CLIENT={cid})")
