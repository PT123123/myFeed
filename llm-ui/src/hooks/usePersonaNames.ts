/**
 * Persona id -> display name, for the card author line (docs/08 §4).
 *
 * Personas are server-owned (there is no Dexie table), so this is a read-only
 * cache. Offline it stays empty and the card simply shows no author — the last
 * thing a reader should get from a network blip is a fake name.
 */

import { useEffect, useState } from 'react'

import { listPersonas } from '../services/api'

export function usePersonaNames(): Map<string, string> {
  const [names, setNames] = useState<Map<string, string>>(() => new Map())

  useEffect(() => {
    let cancelled = false
    void listPersonas()
      .then((rows) => {
        if (!cancelled) setNames(new Map(rows.map((p) => [p.id, p.name])))
      })
      .catch(() => {
        /* keep the empty map */
      })
    return () => {
      cancelled = true
    }
  }, [])

  return names
}
