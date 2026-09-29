/** "All branches" or "HO (home), MUM" from /me. */
export function branchScopeText(me: { allBranches?: boolean; branches?: string[]; homeBranch?: string }): string {
  if (me.allBranches) return 'Branch scope: all branches';
  const list = (me.branches ?? (me.homeBranch ? [me.homeBranch] : [])).map((b) => (b === me.homeBranch ? `${b} (home)` : b));
  return `Branch scope: ${list.join(', ') || '—'}`;
}
