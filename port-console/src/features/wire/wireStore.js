import { useSyncExternalStore } from 'react'

/**
 * The wire log: what the console sent to the scheduler and what came back.
 * A small store outside React so the HTTP layer can write to it directly.
 * Polled reads are not logged; they would bury the requests a person caused.
 */

const LIMIT = 150

let entries = []
let nextId = 1
const listeners = new Set()

function emit() {
  listeners.forEach((listener) => listener())
}

export function recordExchange(exchange) {
  entries = [{ id: nextId++, ...exchange }, ...entries].slice(0, LIMIT)
  emit()
}

export function clearExchanges() {
  entries = []
  emit()
}

function subscribe(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

const getSnapshot = () => entries

export function useWireLog() {
  return useSyncExternalStore(subscribe, getSnapshot)
}
