# Operator Guide

## First Deployment
1. Register operator, airport/apron, airfield safety and ATC-clearance
   scope, staff and robots.
2. Import existing multi-airline slot-booking and billing history.
3. Run read-only safety-scope and runway/tug/pushback robot mission
   dry-runs.
4. Configure safety-class allowed sets and human sign-off paths.
5. Publish a dry-run reconciliation record and audit export.

## Minimum Production Controls
- airfield safety-scope and ATC-clearance-scope validation before any
  turnaround dispatch
- governor gate on every robot action before dispatch
- human sign-off for :high/:safety-critical actions (out-of-scope
  airfield movement, an uninspected turnaround dispatch, an
  unverified clearance record)
- evidence-backed reconciliation records
- audit export for every dispatch, sign-off and reconciliation record
- backup manual airport-operations process

## Certification
Certified operators must prove robot-safety integrity, airfield
safety and ATC-clearance-scope discipline, evidence-backed
reconciliation records and human review for turnaround-affecting
actions.
