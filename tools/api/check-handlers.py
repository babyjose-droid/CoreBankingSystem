#!/usr/bin/env python3
"""Every Spring handler (method + path) across backend/app; reports duplicates and diffs against docs/api/openapi.yaml."""
import re, sys, glob, yaml, collections
root = sys.argv[1] if len(sys.argv) > 1 else '.'
ANN = re.compile(r'@(Get|Post|Put|Delete|Patch|Request)Mapping\b(\s*\(((?:[^()"]|"(?:[^"\\]|\\.)*"|\((?:[^()"]|"[^"]*")*\))*)\))?', re.S)
def paths_of(args):
    if not args: return ['']
    m = re.search(r'(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")', args)
    if m: src = m.group(1)
    else:
        m = re.match(r'\s*(\{[^}]*\}|"[^"]*")', args)
        src = m.group(1) if m else ''
    return re.findall(r'"([^"]*)"', src) or ['']
handlers = collections.defaultdict(list)
for f in sorted(glob.glob(root + '/backend/app/src/main/java/**/*.java', recursive=True)):
    s = open(f).read()
    s_nc = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
    s_nc = re.sub(r'(?m)^\s*//.*$', '', s_nc)
    cls = re.search(r'(?m)^(?:public\s+|final\s+|abstract\s+)*(class|interface)\s+(\w+)', s_nc)
    if not cls: continue
    head = s_nc[:cls.start()]
    prefix = ['']
    for m in ANN.finditer(head):
        if m.group(1) == 'Request': prefix = paths_of(m.group(3))
    for m in ANN.finditer(s_nc[cls.start():]):
        kind = m.group(1)
        if kind == 'Request':
            mm = re.findall(r'RequestMethod\.(\w+)', m.group(3) or '')
            methods = mm or ['ANY']
        else: methods = [kind.upper()]
        line = s_nc[:cls.start() + m.start()].count('\n') + 1
        for pre in prefix:
            for p in paths_of(m.group(3)):
                full = (pre.rstrip('/') + '/' + p.lstrip('/')).rstrip('/') if p else pre
                for me in methods:
                    handlers[(me, full)].append(f'{cls.group(2)}:{line}')
norm = lambda p: re.sub(r'\{[^}]+\}', '{}', p)
dups = {k: v for k, v in handlers.items() if len(set(v)) > 1}
bynorm = collections.defaultdict(list)
for (me, p), v in handlers.items(): bynorm[(me, norm(p))] += v
dups.update({k: v for k, v in bynorm.items() if len(set(v)) > 1})
print(f'{len(handlers)} handlers; duplicates: {len(dups)}')
for k, v in sorted(dups.items()): print('  DUP', k, v)
spec = yaml.safe_load(open(root + '/docs/api/openapi.yaml'))
ops = {(m.upper(), norm(p)) for p, item in spec['paths'].items() for m in item if m in ('get','post','put','delete','patch')}
print(f'{len(ops)} OpenAPI operations')
STATIC = ('/developer',)   # the developer portal's static files are not API operations
for k in sorted(set(bynorm) - ops):
    if not k[1].startswith(STATIC): print('  NO SPEC   ', k, sorted(set(bynorm[k])))
for k in sorted(ops - set(bynorm)): print('  NO HANDLER', k)
sys.exit(1 if dups else 0)
