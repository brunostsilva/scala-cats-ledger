import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// Vitest globals are off, so Testing Library can't register its own cleanup: unmount after each test.
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})
