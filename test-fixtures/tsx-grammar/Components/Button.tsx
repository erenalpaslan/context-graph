import { memo } from 'react';

export interface ButtonProps {
  label: string;
  onClick: () => void;
}

export const Button = (props: ButtonProps) => {
  return <button onClick={props.onClick}>{props.label}</button>;
};

export const MemoButton = memo(function MemoButtonInner(props: ButtonProps) {
  return <button onClick={props.onClick}>{props.label}</button>;
});

export const StyledButton = styled.button`
  color: red;
`;

function helperNotAComponent(a: number, b: number): number {
  return a + b;
}

export default function Icon() {
  return <span className="icon" />;
}
