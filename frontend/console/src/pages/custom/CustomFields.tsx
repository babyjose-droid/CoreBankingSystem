import { useEnumeration, useMe } from '../../api/hooks';
import { useCustomFields, type CustomEntity } from '../../api/platformHooks';
import type { CustomField, CustomValues } from '../../api/types';
import { P, hasPermission } from '../../auth/permissions';
import { formatDate } from '../../lib/dates';
import { Badge, Checkbox, Input, Select } from '../../ui';

/** Form state of custom fields: text as typed, booleans as booleans. */
export type CustomDraft = Record<string, string | boolean>;

/** Active definitions of an entity, in display order; empty for users without custom-field:view. */
export function useCustomDefs(entity: CustomEntity): CustomField[] {
  const me = useMe().data;
  const q = useCustomFields(entity, hasPermission(me?.permissions, P.customFieldView));
  return (q.data ?? []).filter((f) => f.active !== false).sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
}

/** Client-side checks that mirror the server's (required, number range, text length and pattern). */
export function customDraftErrors(defs: CustomField[], draft: CustomDraft): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const d of defs) {
    const key = d.key!;
    const v = draft[key];
    if (d.dataType === 'BOOLEAN') continue;
    const s = typeof v === 'string' ? v.trim() : '';
    if (!s) {
      if (d.required) errors[key] = `${d.label} is required`;
      continue;
    }
    if (d.dataType === 'NUMBER') {
      if (!/^-?\d+(\.\d+)?$/.test(s)) errors[key] = 'Enter a number';
      else if (d.min && Number(s) < Number(d.min)) errors[key] = `At least ${d.min}`;
      else if (d.max && Number(s) > Number(d.max)) errors[key] = `At most ${d.max}`;
    } else if (d.dataType === 'TEXT') {
      let re: RegExp | null = null;
      try {
        re = d.regex ? new RegExp(`^(?:${d.regex})$`) : null;
      } catch {
        re = null;
      }
      if (re && !re.test(s)) errors[key] = `Not in the expected format (${d.regex})`;
      else if (d.min && s.length < Number(d.min)) errors[key] = `At least ${d.min} characters`;
      else if (d.max && s.length > Number(d.max)) errors[key] = `At most ${d.max} characters`;
    }
  }
  return errors;
}

/** Draft -> the `custom` object of the API: typed values, empty ones left out. */
export function customFromDraft(defs: CustomField[], draft: CustomDraft): CustomValues {
  const out: CustomValues = {};
  for (const d of defs) {
    const v = draft[d.key!];
    if (d.dataType === 'BOOLEAN') {
      if (v === true) out[d.key!] = true;
    } else if (typeof v === 'string' && v.trim()) out[d.key!] = d.dataType === 'NUMBER' ? Number(v.trim()) : v.trim();
  }
  return out;
}

export function draftFromCustom(values: CustomValues | undefined): CustomDraft {
  return Object.fromEntries(Object.entries(values ?? {}).map(([k, v]) => [k, typeof v === 'boolean' ? v : String(v ?? '')]));
}

function EnumField({ def, value, onChange, error }: { def: CustomField; value: string; onChange: (v: string) => void; error?: string | null }) {
  const options = useEnumeration(def.enumType ?? '');
  return (
    <Select
      label={def.label}
      required={def.required}
      value={value}
      placeholder={def.required ? 'Select…' : 'None'}
      onChange={(e) => onChange(e.target.value)}
      options={(options.data ?? []).filter((o) => o.active !== false).map((o) => ({ value: o.code, label: o.label }))}
      error={error}
    />
  );
}

/** Inputs for an entity's custom fields, by data type. Renders nothing when the tenant has none. */
export function CustomFieldInputs({ defs, draft, onChange, errors }: { defs: CustomField[]; draft: CustomDraft; onChange: (next: CustomDraft) => void; errors?: Record<string, string> | null }) {
  if (defs.length === 0) return null;
  const set = (k: string, v: string | boolean) => onChange({ ...draft, [k]: v });
  return (
    <div className="form-grid" data-testid="custom-fields">
      {defs.map((d) => {
        const key = d.key!;
        const error = errors?.[key] ?? null;
        const text = typeof draft[key] === 'string' ? (draft[key] as string) : '';
        if (d.dataType === 'BOOLEAN') return <Checkbox key={key} label={d.label} checked={draft[key] === true} onChange={(e) => set(key, e.target.checked)} />;
        if (d.dataType === 'ENUM') return <EnumField key={key} def={d} value={text} onChange={(v) => set(key, v)} error={error} />;
        if (d.dataType === 'DATE') return <Input key={key} label={d.label} required={d.required} type="date" value={text} onChange={(e) => set(key, e.target.value)} error={error} />;
        return (
          <Input
            key={key}
            label={d.label}
            required={d.required}
            numeric={d.dataType === 'NUMBER'}
            autoComplete={d.pii ? 'off' : undefined}
            value={text}
            onChange={(e) => set(key, e.target.value)}
            hint={d.pii ? 'Personal data: stored encrypted and shown masked afterwards' : undefined}
            error={error}
          />
        );
      })}
    </div>
  );
}

function EnumLabel({ type, code }: { type: string; code: string }) {
  const q = useEnumeration(type);
  return <>{q.data?.find((o) => o.code === code)?.label ?? code}</>;
}

/** Read-only custom values as a definition list; personal-data fields arrive masked from the API and are marked. */
export function CustomFieldValues({ defs, values }: { defs: CustomField[]; values: CustomValues | undefined }) {
  const shown = defs.filter((d) => values && values[d.key!] !== undefined && values[d.key!] !== null && values[d.key!] !== '');
  if (shown.length === 0) return <p className="muted" style={{ margin: 0 }}>No additional details recorded.</p>;
  return (
    <dl className="kv" data-testid="custom-values">
      {shown.map((d) => {
        const v = values![d.key!];
        return (
          <div key={d.key} style={{ display: 'contents' }}>
            <dt>{d.label}</dt>
            <dd>
              {d.dataType === 'BOOLEAN' ? (v ? 'Yes' : 'No') : d.dataType === 'DATE' ? formatDate(String(v)) : d.dataType === 'ENUM' ? <EnumLabel type={d.enumType ?? ''} code={String(v)} /> : d.pii ? <span className="masked" title="Masked for privacy">{String(v)}</span> : String(v)}
              {d.pii && <> <Badge tone="info">Personal data, masked</Badge></>}
            </dd>
          </div>
        );
      })}
    </dl>
  );
}
