export * from './extra';

export function add(a, b) {
  return a + b;
}

export const subtract = (a, b) => a - b;

export class Calculator {
  constructor(initial) {
    this.value = initial;
  }

  add(amount) {
    this.value += amount;
    return this.value;
  }
}

export default function square(x) {
  return x * x;
}
