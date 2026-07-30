#!/usr/bin/env python3
"""Read-only timestamped resource samples for the existing local Qwen service."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time
import urllib.request


PROBE = r'''
import json,os
from pathlib import Path
def pairs(name):
    return {v.split()[0].rstrip(':'):v.split()[1:] for v in Path(name).read_text().splitlines()}
def stat(name):
    raw=Path(name).read_text(); v=raw[raw.rfind(')')+2:].split()
    return dict(state=v[0],majorFaults=int(v[9]),userTicks=int(v[11]),systemTicks=int(v[12]),startTicks=int(v[19]))
threads=[]
for p in Path('/proc/1/task').iterdir():
    try: threads.append(dict(tid=int(p.name),**stat(str(p/'stat')),waitChannel=(p/'wchan').read_text().strip()))
    except (FileNotFoundError,ProcessLookupError): pass
print(json.dumps(dict(clockTicks=os.sysconf('SC_CLK_TCK'),process=stat('/proc/1/stat'),
    processStatus=pairs('/proc/1/status'),threads=threads,
    vmMemory=pairs('/proc/meminfo'),vmStat=pairs('/proc/vmstat'),
    vmMemoryPressure=Path('/proc/pressure/memory').read_text(),vmCpuPressure=Path('/proc/pressure/cpu').read_text(),
    cgroupMemory=pairs('/sys/fs/cgroup/memory.stat'),cgroupMemoryEvents=pairs('/sys/fs/cgroup/memory.events'),
    cgroupCpu=pairs('/sys/fs/cgroup/cpu.stat'))))
'''


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--seconds',type=int,default=240)
    p.add_argument('--interval',type=int,default=10)
    args=p.parse_args()
    if args.output.exists(): raise ValueError('Keep existing evidence; choose a new output')
    if not 1<=args.seconds<=600 or not 2<=args.interval<=30: raise ValueError('Bounded local sampling only')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    deadline=time.monotonic()+args.seconds
    with args.output.open('x') as out:
        while True:
            sample={'at':datetime.now(timezone.utc).isoformat()}
            try:
                with urllib.request.urlopen('http://127.0.0.1:8110/ready',timeout=3) as response:
                    sample['ready']=json.load(response)
            except Exception as exc: sample['readyError']=type(exc).__name__+': '+str(exc)
            result=subprocess.run(['docker','exec','embedding-model','python','-c',PROBE],capture_output=True,text=True,timeout=15)
            sample['probeExitCode']=result.returncode
            if result.returncode==0: sample['resources']=json.loads(result.stdout)
            else: sample['probeError']=result.stderr[-2000:]
            out.write(json.dumps(sample,separators=(',',':'))+'\n');out.flush()
            print(json.dumps({'at':sample['at'],'inference':sample.get('ready',{}).get('inference'),'probeExitCode':result.returncode}),flush=True)
            if time.monotonic()>=deadline: break
            time.sleep(min(args.interval,max(0,deadline-time.monotonic())))


if __name__=='__main__': main()
