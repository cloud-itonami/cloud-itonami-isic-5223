# cloud-itonami-5223

Open Business Blueprint for **ISIC Rev.5 5223**: service activities
incidental to air transportation (airport operation, air traffic
control services, and independent/third-party ground handling).

This repository designs a forkable OSS business for community airport
operations: airfield/apron-safety-scope management, robotics-assisted
runway/taxiway inspection, baggage handling and aircraft turnaround
services performed on behalf of MULTIPLE airlines, and slot/booking/
reconciliation records — run by a qualified operator so an airport or
independent ground-handling company keeps its own safety-certification
and turnaround history instead of renting a closed airport-operations
platform.

## Scope note: airport/ATC/ground-handling infrastructure, not the airline

`cloud-itonami-isic-5110` ("Community Passenger Air Transport") already
covers the airline business, and its own docs mention "ground handling"
as one of the SERVICES AN AIRLINE PERFORMS FOR ITS OWN FLIGHTS (turning
around its own aircraft) — an internal function of the carrier, not a
separately licensed business. This repository is deliberately scoped
to the SEPARATE business of airport operation, air traffic control and
independent ground handling: infrastructure and services provided to
MULTIPLE airlines under their own independent licensing regime (ICAO
Annex 14 aerodrome certification; the US FAA's Part 139 airport
certification; the EU's Regulation (EU) 139/2014 aerodrome-operations
rules and Directive 96/67/EC governing ground-handling market access;
Japan's 空港法 with Civil Aviation Bureau oversight; separately
certified air traffic control providers such as the UK's NATS under
CAA oversight, the US FAA's Air Traffic Organization, or Japan's own
ATC under JCAB). An airport operator, an ATC provider or an
independent ground-handling company (e.g. serving several airlines at
one airport) is a fundamentally different, separately regulated
business from any single airline.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (runway/taxiway
inspection, baggage/cargo tugs, aircraft pushback, de-icing) operate
under an actor that proposes actions and an independent **Airport
Operations Governor** that gates them. The governor never dispatches
an airfield or turnaround operation itself; `:high`/`:safety-critical`
actions (any airfield movement outside verified certification scope,
a turnaround dispatched without a completed safety inspection, an ATC
clearance record without verified evidence) require human sign-off.

## Core Contract

```text
intake + identity + airfield safety/ATC scope + booking
        |
        v
Airport Operations Advisor -> Airport Operations Governor -> clearance record, dispatch, reconciliation record, or human approval
        |
        v
robot actions (gated) + turnaround record + reconciliation record + audit ledger
```

No automated advice can dispatch an airfield or turnaround operation
the governor refuses, issue a clearance record outside its verified
scope, or publish a reconciliation record without governor approval
and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `5223`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics) — booking, transit, delivery/reconciliation contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
