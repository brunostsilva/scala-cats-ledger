import type { Account } from '../api'

type Props = {
  account: Account
  selected: boolean
  onSelect: (id: string) => void
}

export function AccountCard({ account, selected, onSelect }: Props) {
  return (
    <div className={`account-card${selected ? ' selected' : ''}`} onClick={() => onSelect(account.id)}>
      <strong>{account.owner}</strong>
      <span className="balance">${account.balance.toFixed(2)}</span>
      <code className="id">{account.id}</code>
    </div>
  )
}
