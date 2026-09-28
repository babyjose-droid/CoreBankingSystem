import { useMemo, useState } from 'react';
import { useGlHeads, useMe, useProposeGlHead } from '../../api/hooks';
import type { GlHead } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { Badge, Button, Card, Checkbox, Dialog, ErrorBanner, Input, PageHeader, Select, Spinner, StatusBadge } from '../../ui';
import { useProposalToast } from '../proposal';
import { CategoryBadge } from './common';

interface Node {
  head: GlHead;
  children: Node[];
}

export function buildTree(heads: GlHead[]): Node[] {
  const byCode = new Map<string, Node>();
  heads.forEach((h) => byCode.set(h.code, { head: h, children: [] }));
  const roots: Node[] = [];
  for (const n of byCode.values()) {
    const parent = n.head.parentCode ? byCode.get(n.head.parentCode) : undefined;
    if (parent) parent.children.push(n);
    else roots.push(n);
  }
  const sort = (ns: Node[]) => {
    ns.sort((a, b) => a.head.code.localeCompare(b.head.code));
    ns.forEach((n) => sort(n.children));
  };
  sort(roots);
  return roots;
}

export function AccountsPage() {
  const heads = useGlHeads();
  const me = useMe().data!;
  const [expanded, setExpanded] = useState<Set<string> | null>(null);
  const [proposing, setProposing] = useState(false);
  const tree = useMemo(() => buildTree(heads.data ?? []), [heads.data]);
  const groups = (heads.data ?? []).filter((h) => !h.posting);
  const open = expanded ?? new Set(tree.map((n) => n.head.code));
  const toggle = (code: string) => {
    const next = new Set(open);
    if (next.has(code)) next.delete(code);
    else next.add(code);
    setExpanded(next);
  };

  const renderNodes = (nodes: Node[], level: number) => (
    <ul className="tree" role={level === 1 ? 'tree' : 'group'} aria-label={level === 1 ? 'Chart of accounts' : undefined}>
      {nodes.map((n) => {
        const hasKids = n.children.length > 0;
        const isOpen = open.has(n.head.code);
        return (
          <li key={n.head.code} role="treeitem" aria-level={level} aria-expanded={hasKids ? isOpen : undefined} aria-selected={false}>
            <div className="tree__row">
              {hasKids ? (
                <button type="button" className="tree__toggle" aria-label={`${isOpen ? 'Collapse' : 'Expand'} ${n.head.name}`} onClick={() => toggle(n.head.code)}>
                  {isOpen ? '−' : '+'}
                </button>
              ) : (
                <span className="tree__spacer" />
              )}
              <span className="mono" style={{ minWidth: 48 }}>
                {n.head.code}
              </span>
              <span style={{ fontWeight: n.head.posting ? 400 : 600 }}>{n.head.name}</span>
              <CategoryBadge category={n.head.category} />
              {n.head.posting ? <Badge tone="accent" title="Accepts voucher lines">Posting</Badge> : <Badge>Group</Badge>}
              {n.head.status && n.head.status !== 'ACTIVE' && <StatusBadge status={n.head.status} />}
            </div>
            {hasKids && isOpen && renderNodes(n.children, level + 1)}
          </li>
        );
      })}
    </ul>
  );

  return (
    <div className="stack">
      <PageHeader
        title="Chart of accounts"
        subtitle="Only posting (leaf) heads accept voucher lines."
        actions={
          <>
            <Button onClick={() => setExpanded(new Set((heads.data ?? []).map((h) => h.code)))}>Expand all</Button>
            <Button onClick={() => setExpanded(new Set())}>Collapse all</Button>
            {hasPermission(me.permissions, P.glPropose) && (
              <Button variant="primary" onClick={() => setProposing(true)}>
                Propose new head
              </Button>
            )}
          </>
        }
      />
      <Card>
        {heads.isLoading ? <Spinner /> : <ErrorBanner error={heads.error} />}
        {tree.length > 0 && renderNodes(tree, 1)}
      </Card>
      {proposing && <ProposeHeadDialog groups={groups} existing={heads.data ?? []} onClose={() => setProposing(false)} />}
    </div>
  );
}

function ProposeHeadDialog({ groups, existing, onClose }: { groups: GlHead[]; existing: GlHead[]; onClose: () => void }) {
  const [h, setH] = useState<GlHead>({ code: '', name: '', category: 'ASSET', parentCode: '', posting: true });
  const [touched, setTouched] = useState(false);
  const propose = useProposeGlHead();
  const toast = useProposalToast();
  const parents = groups.filter((g) => g.category === h.category);
  const errors = {
    code: !/^[0-9]{1,10}$/.test(h.code) ? 'Code must be 1-10 digits' : existing.some((x) => x.code === h.code) ? 'Code already exists' : null,
    name: !h.name.trim() ? 'Name is required' : null,
    parentCode: !h.parentCode ? 'Select a parent group' : null,
  };
  const valid = !errors.code && !errors.name && !errors.parentCode;
  return (
    <Dialog
      open
      onClose={onClose}
      title="Propose new GL head"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={propose.isPending}
            onClick={() => {
              setTouched(true);
              if (!valid) return;
              propose.mutate({ ...h, name: h.name.trim() }, { onSuccess: (a) => (toast(a, 'New GL head'), onClose()) });
            }}
          >
            Submit for approval
          </Button>
        </>
      }
    >
      <div className="form-grid">
        <Input label="Code" required value={h.code} onChange={(e) => setH({ ...h, code: e.target.value.trim() })} error={touched ? errors.code : null} className="mono" />
        <Input label="Name" required value={h.name} onChange={(e) => setH({ ...h, name: e.target.value })} error={touched ? errors.name : null} />
        <Select
          label="Category"
          required
          value={h.category}
          onChange={(e) => setH({ ...h, category: e.target.value as GlHead['category'], parentCode: '' })}
          options={['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'].map((c) => ({ value: c, label: c.charAt(0) + c.slice(1).toLowerCase() }))}
        />
        <Select
          label="Parent group"
          required
          value={h.parentCode ?? ''}
          onChange={(e) => setH({ ...h, parentCode: e.target.value })}
          placeholder="Select…"
          options={parents.map((p) => ({ value: p.code, label: `${p.code} — ${p.name}` }))}
          error={touched ? errors.parentCode : null}
        />
      </div>
      <div style={{ marginTop: 12 }}>
        <Checkbox label="Posting head (accepts voucher lines)" checked={h.posting} onChange={(e) => setH({ ...h, posting: e.target.checked })} />
      </div>
      <div style={{ marginTop: 12 }}>
        <ErrorBanner error={propose.error} />
      </div>
    </Dialog>
  );
}
