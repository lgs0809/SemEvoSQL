#!/usr/bin/env python3
"""Freeze 300 explicitly synthetic questions and actual PostgreSQL result truth.

There are 15 authored template families, not 300 independent templates or real
user histories. Development has 10 families; held-out has five disjoint families.
The business fixture and catalog are shared controlled conditions. This is a
reproducible synthetic system evaluation, not an external production benchmark.
Only reference SQL is executed here; gold data is never sent to the query model.
"""
import argparse
from datetime import date, timedelta, datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path

from acceptance_http import ROOT

spec = importlib.util.spec_from_file_location('oracle', Path(__file__).with_name('verify-offline-catalog.py'))
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)

ALL_AMOUNT = 'b_8f40eb5413e36b5f5e0afbf0f18c3704'
PAID_AMOUNT = 'b_8a2fd2d561bceaa80f116fd8da857d9b'
DB = 'semevosql_quality_benchmark_20261002'


def cases():
    rows = []
    for i in range(20):
        start = date(2026, 1, 1) + timedelta(days=i * 3)
        end = start + timedelta(days=7 + i % 17)
        a, b = start.isoformat(), end.isoformat()
        period = f'{a}（含）至{b}（不含）'
        time = f" >= TIMESTAMP '{a}' AND {{field}} < TIMESTAMP '{b}'"
        def between(field):
            return field + time.format(field=field)
        def add(split, family, question, query, columns, dates=(), clarification=None, ordered=False):
            rows.append({'id': f'{family}-{i + 1:02d}', 'split': split, 'family': family,
                'questionSourceFamily': 'synthetic-authoring:' + family,
                'question': question, 'oracleSql': query, 'columns': columns,
                'dateColumns': list(dates), 'ordered': ordered,
                'mustClarify': clarification is not None, 'clarificationAnswer': clarification,
                'businessDefinitionSource': 'deploy/acceptance/catalog-initialization.md',
                'allowedSchema': ['public.customers', 'public.products', 'public.orders', 'public.order_items', 'public.refunds']})

        for family, name, expression, output in [
            ('D01-order-count', '全部状态订单有多少笔', 'count(*)', 'all_order_count'),
            ('D02-order-record-sum', '全部状态订单的记录金额合计多少元', 'sum(amount)', ALL_AMOUNT),
            ('D03-order-record-average', '全部状态订单的平均记录金额多少元，金额为空按SQL平均值规则忽略', 'avg(amount)', 'average_order_amount')]:
            add('development', family, f'按下单时间，{period}，{name}？',
                f'SELECT {expression} AS value FROM orders WHERE {between("ordered_at")}', {output: 'value'})
        add('development', 'D04-payment-period-sum', f'按付款时间，{period}，PAID或REFUNDED状态订单的付款记录金额合计多少元，不扣退款？',
            f"SELECT sum(amount) AS value FROM orders WHERE status IN ('PAID','REFUNDED') AND {between('paid_at')}", {PAID_AMOUNT: 'value'})
        for family, text, expression, output in [
            ('D05-refund-event-count', '成功退款事件次数，同订单多笔分别计数', 'count(*)', 'successful_refund_event_count'),
            ('D06-refund-order-distinct', '成功退款涉及的不同订单数，同订单多笔只计一笔', 'count(DISTINCT order_id)', 'successfully_refunded_order_count'),
            ('D07-refund-money-sum', '成功退款金额合计多少元', 'sum(amount)', 'successful_refund_amount')]:
            add('development', family, f'按退款成功时间，{period}，{text}？只包括SUCCESS状态。',
                f"SELECT {expression} AS value FROM refunds WHERE status='SUCCESS' AND {between('refunded_at')}", {output: 'value'})
        region = ['华东', '华南', '华北', '西南'][i % 4]
        cutoff = date(2025, 12, 1) + timedelta(days=i)
        add('development', 'D08-registered-region-filter', f'当前地区为{region}，注册时间从{cutoff}（含）到2026-01-01（不含）的客户有多少人？以客户身份计数，不按姓名去重。',
            f"SELECT count(*) AS value FROM customers WHERE region='{region}' AND created_at >= TIMESTAMP '{cutoff}' AND created_at < TIMESTAMP '2026-01-01'", {'registered_customer_count': 'value'})
        category = ['食品', '家居', '数码', '服装', '图书'][i % 5]
        minimum = i + 1
        add('development', 'D09-product-category-price-filter', f'商品分类为{category}，当前参考价格至少{minimum}元的商品有多少个？按商品身份计数。',
            f"SELECT count(*) AS value FROM products WHERE category='{category}' AND price >= {minimum}", {'product_count': 'value'})
        answer = f'本次把净收入定义为：{period}，按付款时间汇总PAID或REFUNDED状态订单记录金额，减去按退款成功时间汇总的SUCCESS退款金额。分别按发生时点归属；金额单位元，空聚合记0；不按原订单下单月份归属，只用于本次。'
        add('development', 'D10-undefined-net-income', f'{period}净收入是多少元？',
            f"SELECT COALESCE((SELECT sum(amount) FROM orders WHERE status IN ('PAID','REFUNDED') AND {between('paid_at')}),0)-COALESCE((SELECT sum(amount) FROM refunds WHERE status='SUCCESS' AND {between('refunded_at')}),0) AS value",
            {'$queryMeasure': 'value'}, clarification=answer)

        add('held_out', 'H01-monthly-order-two-measures', f'{period}按下单月份统计全部状态的订单数和订单记录金额，金额单位元，空金额不变0；各月分别一行。',
            f"SELECT date_trunc('month',ordered_at)::date AS month,count(*) AS order_count,sum(amount) AS record_amount FROM orders WHERE {between('ordered_at')} GROUP BY 1",
            {'order_creation_time': 'month', 'all_order_count': 'order_count', ALL_AMOUNT: 'record_amount'}, dates=['month'])
        add('held_out', 'H02-payment-current-region-left-join', f'{period}按付款时间统计PAID或REFUNDED订单的付款记录金额，按购买客户的当前地区分组，未知地区也要保留为NULL，不扣退款。',
            f"SELECT c.region AS region,sum(o.amount) AS value FROM orders o LEFT JOIN customers c USING(customer_id) WHERE o.status IN ('PAID','REFUNDED') AND {between('o.paid_at')} GROUP BY c.region",
            {'current_customer_region': 'region', PAID_AMOUNT: 'value'})
        n = 1 + i % 7
        add('held_out', 'H03-global-customer-ranking', f'{period}按下单时间计算所有状态订单记录金额，显示金额最高的{n}位客户的客户标识和金额。客户按标识分组而不是姓名；金额降序，金额相同时客户标识升序。',
            f'SELECT customer_id,sum(amount) AS value FROM orders WHERE {between("ordered_at")} GROUP BY customer_id ORDER BY value DESC NULLS LAST,customer_id ASC LIMIT {n}',
            {'customer_id': 'customer_id', ALL_AMOUNT: 'value'}, ordered=True)
        add('held_out', 'H04-monthly-refund-distinct-and-money', f'{period}按退款成功月份统计SUCCESS事件涉及的不同订单数和成功退款金额。一个订单有多笔退款时订单数去重，金额仍逐笔合计，各月一行。',
            f"SELECT date_trunc('month',refunded_at)::date AS month,count(DISTINCT order_id) AS order_count,sum(amount) AS value FROM refunds WHERE status='SUCCESS' AND {between('refunded_at')} GROUP BY 1",
            {'refund_success_time': 'month','successfully_refunded_order_count': 'order_count','successful_refund_amount': 'value'}, dates=['month'])
        add('held_out', 'H05-within-region-customer-ranking', f'{period}，按下单时间统计所有状态订单的记录金额，按购买客户当前地区分组，每个地区显示金额最高的前三位客户。显示地区、客户标识和金额；未知地区保留为NULL。每地区金额降序，同金额按客户标识升序选前三；最终按地区升序NULL最后、金额降序、客户标识升序排列。',
            f"SELECT region,customer_id,value FROM (SELECT c.region,o.customer_id,sum(o.amount) AS value,row_number() OVER(PARTITION BY c.region ORDER BY sum(o.amount) DESC NULLS LAST,o.customer_id ASC) AS rank FROM orders o LEFT JOIN customers c USING(customer_id) WHERE {between('o.ordered_at')} GROUP BY c.region,o.customer_id) ranked WHERE rank <= 3 ORDER BY region ASC NULLS LAST,value DESC NULLS LAST,customer_id ASC",
            {'current_customer_region':'region','customer_id':'customer_id',ALL_AMOUNT:'value'}, ordered=True)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists(): raise ValueError('Retain frozen benchmark; choose a new path')
    rows = cases()
    assert len(rows) == 300 and len({x['question'] for x in rows}) == 300
    dev = {x['family'] for x in rows if x['split'] == 'development'}
    held = {x['family'] for x in rows if x['split'] == 'held_out'}
    assert len(dev) == 10 and len(held) == 5 and not dev & held
    assert oracle.sql(DB, 'SELECT fixture FROM fixture_identity WHERE id=1') == [{'fixture':'SEMEVOSQL_QUALITY_V1'}]
    for case in rows:
        case['expectedRows'] = oracle.sql(DB, case['oracleSql'], preserve_decimals=True)
        case['oracleSha256'] = hashlib.sha256(case['oracleSql'].encode()).hexdigest()
    report = {'formatVersion':1,'scope':'FROZEN_SYNTHETIC_300_NOT_EXTERNAL_PRODUCTION_BENCHMARK',
        'frozenAt':datetime.now(timezone.utc).isoformat(),'projectId':5,'projectVersionId':13,'datasourceId':4,
        'database':DB,'account':'sem_member_c','models':{'small':'gpt-5.6-luna','large':'gpt-5.6-terra'},
        'developmentCount':200,'heldOutCount':100,'heldOutRepeats':5,
        'familyIsolation':{'development':sorted(dev),'heldOut':sorted(held),
            'source':'15 authored synthetic question families; shared seeded commerce snapshot and business catalog',
            'limitation':'Parameter variants within a family are correlated. This is not 300 independent templates or real user questions.'},
        'businessSeedSha256':hashlib.sha256((ROOT/'deploy/acceptance/sql/quality-business-seed.sql').read_bytes()).hexdigest(),
        'businessMaterialSha256':hashlib.sha256((ROOT/'deploy/acceptance/catalog-initialization.md').read_bytes()).hexdigest(),
        'goldBoundary':'Reference SQL and expected rows are local evaluation-only data and are never sent to planning/generation/retrieval. Clarification answers are fixed natural-language responses, submitted only after an actual question.',
        'cases':rows}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'path':str(args.output),'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest(),
        'cases':len(rows),'families':15,'development':200,'heldOut':100,'plannedRuns':700}))


if __name__ == '__main__': main()
