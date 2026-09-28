import type { GlHead } from '../../api/types';
import { Badge, humanize, type Tone } from '../../ui';

export const CATEGORY_TONE: Record<GlHead['category'], Tone> = {
  ASSET: 'info',
  LIABILITY: 'warn',
  EQUITY: 'accent',
  INCOME: 'ok',
  EXPENSE: 'danger',
};

export function CategoryBadge({ category }: { category: string }) {
  return <Badge tone={CATEGORY_TONE[category as GlHead['category']] ?? 'neutral'}>{humanize(category)}</Badge>;
}
