#!/usr/bin/env python3
"""Run Spring Boot with .env propagated; wait for docs endpoint."""
import argparse, os, subprocess, sys, time, urllib.request
def load_env(path):
    if os.path.exists(path):
        for line in open(path):
            line=line.strip()
            if line and not line.startswith("#") and "=" in line:
                k,v=line.split("=",1); os.environ.setdefault(k,v)
def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--port",type=int,default=int(os.environ.get("SERVER_PORT","8080"))); ap.add_argument("--profile",default="local"); ap.add_argument("--env-file",default=".env"); ap.add_argument("--verbose",action="store_true"); a=ap.parse_args()
    load_env(a.env_file); os.environ["SERVER_PORT"]=str(a.port)
    p=subprocess.Popen(["./mvnw","-f","backend/pom.xml","spring-boot:run",f"-Dspring-boot.run.profiles={a.profile}"])
    for _ in range(30):
        time.sleep(2)
        try:
            with urllib.request.urlopen(f"http://localhost:{a.port}/v3/api-docs",timeout=2) as r:
                if r.status==200: print("backend UP"); return 0
        except Exception as e:
            if a.verbose: print("waiting backend...",e)
    p.terminate(); print("backend timeout"); return 2
if __name__=="__main__": sys.exit(main())
