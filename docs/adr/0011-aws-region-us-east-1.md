---
status: accepted
date: 2026-09-22
decision-makers: Eduardo Furlanetto
---

# Everything runs in us-east-1

## Context and Problem Statement

Adding `spring-cloud-aws` made the region explicit configuration: the SQS client cannot be built
without one. The choice is a cost decision rather than a technical preference, and this project has
a hard constraint that costs stay inside the AWS free tier.

## Considered Options

- **`us-east-1`** (N. Virginia).
- **`sa-east-1`** (São Paulo), nearest to PNCP and to the users.

## Decision Outcome

Chosen option: **`us-east-1`**, for every resource this system uses.

It is the region this account's free tier covers. `sa-east-1` is closer to both PNCP and the eventual
users, and it is rejected because free-tier coverage is the binding constraint and latency is not.

### Why the latency does not matter here

PNCP is called by a **daily batch**, not on a user request path. A round trip from `us-east-1` to
Brazil costs roughly 150ms more than from `sa-east-1`, and that cost is paid **once per page**:

- a daily run fetches 200 to 320 pages;
- at 8 concurrent requests, the extra latency adds roughly **4 to 6 seconds** to a run;
- the whole-operation deadline is **5 minutes**.

So the penalty is under 2% of the budget the run already has, on a job nobody is waiting for. If a
user-facing query ever needed PNCP synchronously the calculation would change, but that is not this
system: the API serves matches out of PostgreSQL, and PostgreSQL will be in the same region as the
services.

### Same region for everything

**All resources live in `us-east-1`** — EC2, SQS, RDS, and anything stage 8 adds. Not for tidiness:
cross-region data transfer is billed, and a queue in one region read by a service in another would
turn free-tier traffic into a line item. Co-locating also keeps SQS and RDS latency negligible,
which matters more than the PNCP hop because those are on every message.

### No residency constraint

The data is **public procurement notices published by the Brazilian government for anyone to read**.
There is no personal data, no commercial confidentiality, and nothing that obliges it to be stored
in Brazil. Company profiles are the only user data the system will hold, and they are a CNPJ, a
CNAE and a list of keywords — all of which a company publishes about itself.

Had the data been personal or regulated, LGPD would have made this a different decision and cost
would not have been the deciding factor.

### Consequences

- Good, because the free tier is the binding constraint and this is the region that satisfies it.
- Good, because one region for everything means no cross-region transfer charges to discover later.
- Bad, because every PNCP call is about 150ms slower than it needs to be. Measured against the
  operation deadline, that is noise.
- Bad, because a future user-facing feature that calls PNCP synchronously would be worse off. That
  would be the trigger to revisit this, not a reason to pre-emptively pay for it now.

The value is pinned in `CLAUDE.md` so that stage 8 infrastructure uses the same one rather than
picking its own, and it is overridable via `AWS_REGION` for anyone running the stack elsewhere.
