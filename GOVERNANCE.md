# Governance

`cloud-itonami-5223` is an OSS open-business blueprint for community
airport operations and ground handling, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Airport Operations Governor remains independent of the advisor.
- hard policy violations (out-of-scope airfield movement, an
  uninspected turnaround dispatch, an unverified clearance record)
  cannot be overridden by human approval.
- every dispatch, sign-off, clearance and reconciliation path is
  auditable.
- sensitive airline and passenger-flow data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or ATC-clearance-scope checks
- mishandling airline or passenger-flow data
- misrepresenting certification status
- failing to respond to safety incidents
