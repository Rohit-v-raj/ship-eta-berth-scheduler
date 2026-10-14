/**
 * Who may do what. This is the one list; screens ask `can(session, action)`
 * and never test a role directly, so adding a role or moving a permission is a
 * change to this file alone.
 *
 * When login is added the server must enforce the same rules; this list only
 * decides what the screen offers.
 */

export const ROLES = {
  PLANNER: 'PLANNER',
  OPERATOR: 'OPERATOR',
}

export const ROLE_LABELS = {
  [ROLES.PLANNER]: 'Terminal planner',
  [ROLES.OPERATOR]: 'Vessel operator',
}

const { PLANNER, OPERATOR } = ROLES

export const PERMISSIONS = {
  'plan.view': [PLANNER],
  'berth.release': [PLANNER],
  'visit.cancel': [PLANNER],
  'berth.maintain': [PLANNER],
  'vessel.view': [OPERATOR],
  'position.report': [OPERATOR],
  'visit.answer': [OPERATOR],
  'events.view': [PLANNER, OPERATOR],
  'story.run': [PLANNER, OPERATOR],
}

/** Where each role lands when it opens the console. */
export const HOME = {
  [PLANNER]: '/planner',
  [OPERATOR]: '/operator',
}

export function can(session, action) {
  const roles = PERMISSIONS[action]
  if (!roles) throw new Error(`Unknown action '${action}'`)
  return roles.includes(session?.role)
}
