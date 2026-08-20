/**
 * 공통 버튼 (KAN-148). 화면들이 실제로 반복해서 쓰는 세 무게만 둔다 —
 * 주동작(primary)·보조(secondary)·이탈(text).
 *
 * 크기·색·눌림 효과는 `components.css`의 `.btn` 계열이 갖는다. 여기서 하는 일은
 * 변형을 클래스 이름으로 옮기고 `type="button"`을 기본으로 박는 것뿐이다 —
 * 폼 안에서 `<button>`의 기본값은 submit이라, 빠뜨리면 눌렀을 때 페이지가 새로고침된다.
 */

import type { ButtonHTMLAttributes, ReactNode } from 'react'

export type ButtonVariant = 'primary' | 'secondary' | 'text'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  children: ReactNode
}

export function Button({ variant = 'primary', className, children, ...rest }: ButtonProps) {
  const classes = ['btn', `btn--${variant}`, className].filter(Boolean).join(' ')
  return (
    <button type="button" className={classes} {...rest}>
      {children}
    </button>
  )
}
