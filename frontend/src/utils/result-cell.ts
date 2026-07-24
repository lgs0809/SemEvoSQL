/** Keep missing values, empty text, zero and false visibly distinct in every result table. */
export const resultCellText = (_row: unknown, _column: unknown, value: unknown): string => {
  if (value === null || value === undefined) return '无值';
  if (value === '') return '空文本';
  return String(value);
};
