#!/usr/bin/env python3
"""Create/reuse a named draft through the API; never edit published data or mark a release passed.

The customer dimension maps to the existing synthetic business seed. Validate and publish through
the project page, then issue the natural-language pagination query. Output preserves the exact draft.
"""
import argparse
import json
from pathlib import Path
from acceptance_http import LocalAcceptanceClient

SOURCE = 'SYNTHETIC_PAGINATION_ACCEPTANCE'
client = None


def api(path, method='GET', body=None):
    global client
    if client is None:
        client = LocalAcceptanceClient()
    return client.request('/api/semevosql' + path, method, body)


def prepare(project, version_number, output):
    if output.exists():
        raise ValueError('Keep previous evidence; use a fresh output')
    current = api(f'/projects/{project}')
    active = current['project']['activePublishedVersionId']
    versions = api(f'/projects/{project}/versions')
    matching = [v for v in versions if v['versionNumber'] == version_number]
    if matching:
        version = matching[0]
        if version['source'] != SOURCE:
            raise ValueError('Version belongs to another authoring operation')
    else:
        version = api(f'/projects/{project}/versions', 'POST', {
            'versionNumber': version_number, 'creationMode': 'CLONE',
            'parentVersionId': active, 'source': SOURCE})['version']
    version_id = version['id']
    catalog = api(f'/projects/{project}/versions/{version_id}/semantic-catalog')
    if version['status'] != 'PUBLISHED':
        if version['analysisStatus'] not in ('RUNNING', 'COMPLETED'):
            api(f'/projects/{project}/versions/{version_id}/analysis/start', 'POST', {})
        if not any(d['modelCode'] == 'orders' and d['dimensionCode'] == 'customer_id'
                   for d in catalog['dimensions']):
            catalog['dimensions'].append({'modelCode': 'orders', 'dimensionCode': 'customer_id',
                'businessName': '客户编号', 'columnName': 'customer_id', 'expression': 'customer_id',
                'dimensionType': 'CATEGORICAL', 'status': 'ENABLED',
                'description': '按订单的 customer_id 分组；客户编号仅表示身份，不包含客户名称。',
                'evidence': 'SYNTHETIC_BUSINESS: deploy/acceptance/sql/business-seed.sql'})
            for kind in ('models', 'columns', 'metrics', 'dimensions', 'relationships', 'grains', 'enumValues', 'rules'):
                for asset in catalog[kind]:
                    for field in ('id', 'projectId', 'projectVersionId', 'createTime', 'updateTime'):
                        asset.pop(field, None)
            catalog = api(f'/projects/{project}/versions/{version_id}/semantic-catalog', 'PUT', catalog)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'projectId': project, 'versionId': version_id,
        'status': 'DRAFT_REQUIRES_REAL_VALIDATION_AND_PUBLICATION' if version['status'] != 'PUBLISHED'
                  else 'EXISTING_PUBLICATION_NOT_NEWLY_VALIDATED',
        'source': SOURCE, 'catalog': catalog}, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'projectId': project, 'versionId': version_id, 'version': version_number,
                      'status': version['status']}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-id', type=int, default=2)
    parser.add_argument('--version', default='1.0.1')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.project_id, args.version, args.output)
