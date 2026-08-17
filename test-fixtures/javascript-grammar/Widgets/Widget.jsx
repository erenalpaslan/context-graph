import React from 'react';

export const Widget = (props) => {
  return <div className="widget">{props.children}</div>;
};

export default function Panel(props) {
  return <section>{props.children}</section>;
}
