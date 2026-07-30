"""Resolve only explicitly named, independently identified local synthetic business fixtures."""
import re


def business_database(project_id, sql, namespace=None):
    if not namespace:
        if project_id not in (1, 2):
            raise ValueError('Legacy acceptance projects only; a fresh fixture needs an explicit namespace')
        return 'semevosql_acceptance_business'
    if not re.fullmatch(r'[a-z][a-z0-9_]{0,23}', namespace) or namespace.startswith('benchmark_'):
        raise ValueError('Named ordinary synthetic fixture required; frozen evaluation cannot be shared')
    database = 'semevosql_quality_' + namespace
    sources = sql('semevosql_acceptance', f"""SELECT DISTINCT d.id,d.database_name,d.username,d.host,d.port
        FROM qw_project p JOIN qw_project_datasource_binding b ON b.project_id=p.id
        JOIN datasource d ON d.id=b.datasource_id
        WHERE p.id={int(project_id)} AND p.project_code='semevosql-quality-{namespace}'""")
    if len(sources) != 1 or any(sources[0].get(key) != value for key, value in {
            'database_name':database,'username':'semevosql_reader_'+namespace,'host':'metadata-db','port':5432}.items()):
        raise ValueError('Project, datasource and readonly synthetic reader identity do not match')
    if sql(database, 'SELECT fixture FROM public.fixture_identity WHERE id=1') != [{'fixture':'SEMEVOSQL_QUALITY_V1'}]:
        raise ValueError('Business database is not the named synthetic fixture')
    return database
