#!/usr/bin/env python3
"""Read-only inspection of request understanding in a persisted native checkpoint.

Decodes only ObjectOutputStream primitive block data written by DurableGraphStateSerializer;
never loads Java objects. Saves the exact SQL and a small, credential-free state projection.
"""
import argparse
import base64
import hashlib
import json
import struct
import subprocess
import uuid
from pathlib import Path

KEYS = ('ORIGINAL_REQUEST', 'rootCanonicalQuery', 'input', 'ACTIVE_QUERY', 'ACTIVE_TODO_ID',
        'requestEnhancementOutput', 'QUERY_REPAIR_BUDGET', 'native_clarification_applied_answers')

def primitive_json(payload):
    raw = base64.b64decode(payload, validate=True)
    if raw[:4] != bytes.fromhex('aced0005'):
        raise ValueError('Unexpected Java stream header')
    offset, blocks = 4, []
    while offset < len(raw):
        tag = raw[offset]
        offset += 1
        if tag == 0x77:
            length = raw[offset]
            offset += 1
        elif tag == 0x7a:
            length = struct.unpack('>I', raw[offset:offset+4])[0]
            offset += 4
        else:
            raise ValueError('Only primitive block data is supported')
        if length > 16000004 or offset + length > len(raw):
            raise ValueError('Invalid primitive block length')
        blocks.append(raw[offset:offset+length])
        offset += length
    data = b''.join(blocks)
    if len(data) < 4 or struct.unpack('>I', data[:4])[0] != len(data)-4:
        raise ValueError('Invalid durable state length')
    value = json.loads(data[4:])
    if value.get('schemaVersion') != 1:
        raise ValueError('Unknown durable schema')
    return value['state']

def plain(value):
    if not isinstance(value, dict):
        return value
    if 'fields' in value:
        return {k: plain(v) for k, v in value['fields'].items()}
    if 'values' in value:
        return [plain(v) for v in value['values']]
    scalar = value.get('value')
    if value.get('type') in ('java.lang.Integer','java.lang.Long','java.lang.Short','java.lang.Byte'):
        return int(scalar)
    return scalar

def inspect(run_id, output):
    run_id = str(uuid.UUID(run_id))
    if output.exists() or output.with_suffix('.sql').exists():
        raise ValueError('Use a new evidence path')
    sql = ("SELECT c.checkpoint_id,c.node_id,c.next_node_id,c.state_data FROM graphcheckpoint c "
        "JOIN graphthread t ON t.thread_id=c.thread_id "
        "JOIN qw_native_graph_binding b ON b.graph_thread_id::text=t.thread_name "
        "WHERE b.run_id='"+run_id+"' ORDER BY c.saved_at DESC LIMIT 1")
    command = ['docker','exec','semevosql-acceptance-metadata-db-1','psql','-X','-A','-t',
        '-v','ON_ERROR_STOP=1','-U','acceptance','-d','semevosql_acceptance',
        '-c','SELECT row_to_json(x) FROM ('+sql+') x']
    row = json.loads(subprocess.check_output(command, text=True))
    state = plain(primitive_json(row['state_data']['binaryPayload']))
    # Constant values have both legacy upper-case and lower-case spellings in older graph versions.
    selected = {k:v for k,v in state.items() if k in KEYS or k.lower() in {
        'original_request','active_query','active_todo_id','query_repair_budget','request_analysis'}}
    result = {'runId':run_id,'checkpointId':row['checkpoint_id'],'node':row['node_id'],
        'nextNode':row['next_node_id'],'state':selected,
        'nativePayloadSha256':hashlib.sha256(row['state_data']['binaryPayload'].encode()).hexdigest(),
        'boundary':'Read-only durable state; no status, answer, budget or result changes.'}
    envelope = state.get('CONVERSATION_CONTEXT_ENVELOPE')
    if isinstance(envelope, dict):
        result['contextSourceSnapshot'] = {'schemaVersion': envelope.get('schemaVersion'),
            **{name: [{key: turn.get(key) for key in ('sequence','sourceRunId','sourceRevision')}
                      for turn in envelope.get(name, [])]
               for name in ('recentTurns','retrievedTurns')}}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.with_suffix('.sql').write_text(sql+';\n')
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    inspect(args.run_id,args.output)
