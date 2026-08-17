export function add(a: number, b: number): number {
  return a + b;
}

export const multiply = (a: number, b: number): number => a * b;

const internalHelper = (a: number): number => a + 1;

export default function formatUser(name: string): string {
  return `User: ${name}`;
}
