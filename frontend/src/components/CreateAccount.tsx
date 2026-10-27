import { useState } from 'react'

type Props = {
  onCreate: (owner: string) => void
}

export function CreateAccount({ onCreate }: Props) {
  const [owner, setOwner] = useState('')

  return (
    <form
      className="card"
      onSubmit={(e) => {
        e.preventDefault()
        if (owner.trim()) {
          onCreate(owner.trim())
          setOwner('')
        }
      }}
    >
      <h2>New account</h2>
      <input placeholder="Owner name" value={owner} onChange={(e) => setOwner(e.target.value)} />
      <button type="submit">Create</button>
    </form>
  )
}
