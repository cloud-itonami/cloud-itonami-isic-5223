# ADR-0001: GroundOps-LLM ⊣ Airport Ground Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-5223` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-5223` was registered as a `:blueprint`-tier repo
(README, business-model, operator-guide, `blueprint.edn` published,
GitHub repo created) but had no `deps.edn`, `src`, or `test` -- an
early bulk-scaffolding-pass entry, not yet promoted to real code. This
ADR records the governed-actor architecture that promotes it, following
the same langgraph StateGraph + independent Governor + Phase 0->3
rollout pattern established by `cloud-itonami-isic-6511` (life
insurance) and applied across many prior siblings, most recently
`cloud-itonami-isic-5110` (passenger air transport) and
`cloud-itonami-isic-5222` (water transport support).

## Decision

### Decision 1: an airport-ground-handling OPERATIONS COORDINATION actor, not direct ramp-safety authority

This actor's closed op-allowlist is exactly four ops, all
`:effect :propose`: `:log-service-record` (de-icing/baggage-handling/
ramp-service data logging), `:schedule-ground-operation` (ramp/gate/
de-icing scheduling coordination), `:flag-ramp-safety-concern`
(surface a ramp-hazard/FOD/de-icing-fluid-holdover concern to a
human), and `:coordinate-equipment-maintenance` (ground-support-
equipment maintenance coordination -- not an equipment RELEASE/
return-to-service sign-off). It never clears a ramp/apron area as
safe and never overrides a de-icing protocol -- those acts are
structurally outside this actor's vocabulary.

This is a narrower scope than the originally-published
`README.md`/`docs/business-model.md` text (which describes a broader
"airfield/ATC clearance, slot/booking/reconciliation" vision,
`:operating-states [:intake :book :transit :deliver :reconcile
:audit]` in the `kotoba-lang/industry` registry entry). Per this
fleet's own "extending coverage is additive, scope down for R0"
convention (see `cloud-itonami-isic-5110`'s own ADR-0001 Decision 1),
this build deliberately implements the ground-handling-operations-
coordination slice of that vision now and leaves slot/booking/
reconciliation and ATC-clearance records as a follow-up. The original
README/business-model/operator-guide docs are left UNMODIFIED (they
are the blueprint-tier vision, not a contract this R0 build must
literally match field-for-field); this ADR is the authoritative
record of what the R0 implementation actually covers.

### Decision 2: `:effect` is structurally ALWAYS `:propose`

Every proposal this actor's advisor can produce carries a literal
`:effect :propose` -- this actor never performs a real-world
actuation of any kind, only ever drafts and appends a coordination
record. `groundops.governor`'s `effect-not-propose-violations` check
independently re-verifies this on every proposal (defense in depth:
even if the advisor drifted, the governor still blocks a non-
`:propose` effect), and `groundops.store/commit-record!` itself only
recognizes `:propose`.

### Decision 3: `finalize-clearance-scope-violations` -- a HARD, PERMANENT block, phrased as the finalization ACTION

Per the fleet-wide known bug class (multiple sibling actors have
independently hit and fixed the SAME mistake: a scope-exclusion term
list phrased as a bare noun accidentally matches inside the mock
advisor's own default rationale/disclaimer text for a legitimate
proposal, causing the actor to self-block on its own happy path),
`groundops.governor/finalize-clearance-phrases` is phrased as the
finalization/execution ACTION ("finalize the ramp-safety clearance",
"clear the ramp as safe", "override the de-icing protocol") rather
than a bare topic noun ("safety"/"ramp"/"de-icing" alone).
`groundops.groundopsllm/propose-flag-ramp-safety-concern` itself
legitimately talks about ramp hazards, FOD and de-icing-fluid
holdover time as the CONTENT of a concern being flagged -- a
bare-noun term list would have self-tripped on exactly this op's own
happy path. `test/groundops/governor_contract_test.clj`'s
`default-advisor-proposals-never-self-trip-finalize-clearance-scope`
asserts directly, for all four ops, that the default mock advisor's
own proposals never trip this check.

This is also the CRITICAL Wave-2 invariant for this class: any op
finalizing an airport/ground-safety-clearance decision (e.g. clearing
a ramp area as safe after a reported hazard, overriding a de-icing
protocol) is a hard, permanent, un-overridable block -- structurally
absent from the op-allowlist AND defended in depth by this text-scan
check in case a proposal's own text drifts toward one anyway.

### Decision 4: `:flag-ramp-safety-concern` is doubly enforced to never auto-commit

Two independent layers agree: `groundops.governor`'s `high-stakes`
gate (keyed on the proposal's own `:stake :ground/flag-ramp-safety-
concern`) always forces `:escalate?` true regardless of confidence or
other checks being clean, AND `groundops.phase`'s phase table never
adds `:flag-ramp-safety-concern` to any phase's `:auto` set, including
phase 3 -- a permanent structural fact, not a rollout milestone still
to come. This satisfies the CRITICAL Wave-2 requirement that any "flag
a concern" op must always escalate to human sign-off and never be in
any phase's `:auto` set.

### Decision 5: `facility-unverified-violations` -- ground truth this actor CONSUMES, never MINTS

`:facility-verified?` represents an engagement's own airport-facility
permit and ground-handling-operator-license record, independently
verified and registered by a real airport operator / civil aviation
authority OUTSIDE this actor's own closed op-allowlist. None of the
four ops ever sets it -- there is no `:facility/assess`-style op in
this actor's vocabulary. This is evaluated UNCONDITIONALLY across all
four ops: no coordination proposal may proceed for an engagement whose
own facility permit has not been independently verified and
registered -- the CRITICAL invariant that an airport-facility/permit
record must be independently verified/registered before any action.

### Decision 6: `open-ramp-hazard-blocks-op` -- a genuinely new check, exempting the flag op itself

An unresolved ramp-safety concern already on file
(`:ramp-hazard-raised? true` AND `:ramp-hazard-resolved? false`)
blocks `:log-service-record`/`:schedule-ground-operation`/
`:coordinate-equipment-maintenance` on that engagement, but
deliberately NOT `:flag-ramp-safety-concern` itself -- the
safety-reporting channel must always stay open, including to report
further detail on an already-open concern. Resolving a concern
(`:ramp-hazard-resolved? true`), and finalizing any ramp/ground-safety
clearance, is likewise OUTSIDE this actor's own op-allowlist -- a real
airport ground-safety authority's call, not this actor's, mirroring
Decision 5's "consume, never mint" discipline for ground-truth safety
facts.

### Decision 7: self-contained, no bespoke domain capability library

There is no `kotoba-lang/aviation` bespoke domain capability library to
delegate aerodrome-certificate/ground-handling-operator-license
validation to (checked: `kotoba-lang` org has no aviation-domain
package). Like `cloud-itonami-isic-5110` (passenger air transport) and
`cloud-itonami-isic-5222` (water transport support), this R0 build is
self-contained: `groundops.facts`/`groundops.registry`/
`groundops.governor` implement the domain logic as pure functions
rather than wrapping an external lib.

### Decision 8: `blueprint.edn` maturity flip

`blueprint.edn` did not yet carry `:itonami.blueprint/maturity` (its
`:required-technologies`/`:optional-technologies` already correctly
matched the `kotoba-lang/industry` registry entry, so no field-sync fix
was needed there) -- fixed by adding `:itonami.blueprint/maturity
:implemented`, matching the registry's own `:maturity :implemented`
flip.

## Alternatives considered

- **Matching the originally-published airfield/ATC/slot-booking/
  reconciliation vision literally, op-for-op.** Rejected for R0: the
  task at hand specifies a narrower, explicit four-op ground-handling-
  operations-coordination vocabulary with a clear non-actuation
  invariant; scoping down and recording the gap here (Decision 1)
  follows this fleet's own established "extending coverage is
  additive" convention rather than inventing an op set the governor
  rules were not designed against.
- **A bare-noun scope-exclusion term list** (`#{"safety" "ramp"
  "de-icing"}`). Rejected: this is the exact fleet-wide known bug
  class (Decision 3) -- it would have self-tripped on
  `propose-flag-ramp-safety-concern`'s own legitimate happy path.
- **Rewriting the existing README/business-model/operator-guide docs
  to match the R0 scope exactly.** Rejected: the task instructions
  direct keeping the existing boilerplate docs rather than recreating
  the repo; this ADR is the authoritative record of the gap instead.

## Consequences

- `cloud-itonami-isic-5223` promoted to `:implemented`.
- Establishes a "ground truth consumed, never minted" pattern for both
  facility-permit verification and ramp-hazard-resolution facts -- this
  actor coordinates around them but never sets them itself.
- `MemStore` ‖ `DatomicStore` parity is proven by
  `test/groundops/store_contract_test.clj`.
- The self-tripping-bug-class regression is covered by a dedicated
  test, not just avoided by convention.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of
  the general governed-actor architecture pattern)
- `cloud-itonami-isic-5110/docs/adr/0001-architecture.md` (sibling
  passenger-air-transport build this architecture mirrors most
  closely, including the same known self-tripping-bug-class fix)
- `cloud-itonami-isic-5222/docs/adr/0001-architecture.md` (sibling
  water-transport-support build, same pattern family)
- 14 C.F.R. Part 139 (US, Certification of Airports)
- 空港法 (Airport Act, Japan)
- Airports (Groundhandling) Regulations 1997 (SI 1997/2214, UK)
- EU Regulation (EU) No 139/2014 (Aerodromes) / Verordnung über
  Bodenabfertigungsdienste (BADV, Germany)
