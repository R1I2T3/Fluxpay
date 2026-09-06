#!/usr/bin/env python3
"""Gate: mvn verify + ojet build + seed + smoke(login, 1 msg per topic)."""
import argparse, subprocess, sys
def run(cmd, cwd=None):
    print("+"," ".join(cmd)); r=subprocess.run(cmd,cwd=cwd); return r.returncode
def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--suite",default="all",choices=["all","backend","frontend","e2e"]); ap.add_argument("--env-file",default=".env"); ap.add_argument("--verbose",action="store_true"); a=ap.parse_args()
    if a.suite in ("all","backend"):
        if run(["./mvnw","-f","backend/pom.xml","verify"]): print("backend FAIL"); return 3
    if a.suite in ("all","frontend"):
        if run(["ojet","build"],cwd="frontend/fluxpay-ui"): print("frontend FAIL"); return 3
    if a.suite in ("all","e2e"):
        if run(["python3","scripts/seed-demo.py"]): print("seed FAIL"); return 3
        import urllib.request, json
        try:
            req=urllib.request.Request("http://localhost:8080/api/auth/login",data=json.dumps({"email":"alice@demo.io","password":"Pass123!"}).encode(),headers={"Content-Type":"application/json"})
            urllib.request.urlopen(req,timeout=5).read(); print("smoke login OK")
        except Exception as e: print("smoke login FAIL",e); return 3
        topics=["payment.initiated","payment.route.selected","payment.screening.completed","payout.submitted","payout.failed","payout.completed","payment.refunded"]
        for t in topics:
            r1=subprocess.run(["docker","exec","fluxpay-kafka","kafka-console-producer.sh","--bootstrap-server","localhost:29092","--topic",t],input=b"smoke-1\n",capture_output=True,timeout=15)
            if r1.returncode!=0: print("produce FAIL",t); return 3
        print("smoke kafka OK 7/7")
    print("ALL GREEN"); return 0
if __name__=="__main__": sys.exit(main())
