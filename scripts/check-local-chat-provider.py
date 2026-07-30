#!/usr/bin/env python3
"""Probe existing local model records with bounded real retries. No activation or credentials in evidence."""
import argparse,concurrent.futures,json,time
from pathlib import Path
from acceptance_http import LocalAcceptanceClient

def check(name):
    client=LocalAcceptanceClient();rows=client.request('/api/model-config/list')['data']
    matches=[row for row in rows if row['modelType']=='CHAT' and row['modelName']==name]
    if len(matches)!=1:raise ValueError('Expected one existing model identity')
    row=matches[0];attempts=[]
    for number in range(1,6):
        started=time.monotonic()
        try:
            result=client.request('/api/model-config/test','POST',row)
            passed=result.get('success') is True
            attempts.append({'attempt':number,'passed':passed,'elapsedSeconds':round(time.monotonic()-started,3),'code':result.get('code')})
        except Exception as error:
            passed=False;attempts.append({'attempt':number,'passed':False,'elapsedSeconds':round(time.monotonic()-started,3),'failureClass':type(error).__name__})
        if passed:break
        if number<5:time.sleep(min(2**(number-1),8))
    return {'modelName':name,'id':row['id'],'status':'PASS_CONNECTIVITY_ONLY' if passed else 'FAILED_AFTER_FIVE_ATTEMPTS','attempts':attempts}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise ValueError('Retain prior evidence')
    names=['gpt-5.6-luna','gpt-5.6-terra']
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(check,names))
    report={'models':results,'boundary':'Actual provider connectivity through existing backend configuration; no activation, model substitution, business query or fabricated success.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False))
    raise SystemExit(0 if all(r['status']=='PASS_CONNECTIVITY_ONLY' for r in results) else 1)
if __name__=='__main__':main()
