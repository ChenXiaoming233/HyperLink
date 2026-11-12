# Coding Standards

This file contains review-time judgement calls. Deterministic rules belong in tests, lint, or CI.

## Cross-process contracts

- RPC methods, Bundle keys, protocol versions, field types, and default values have one authoritative contract type.
- Producers, consumers, tests, and acceptance tooling must not independently re-enumerate the same wire fields.

## Runtime probe boundaries

- Hook installation, caller authentication, vendor reflection, and snapshot reading stay in separate types when they change for different reasons.
- One orchestration entry point may compose those types, but it must not also own their implementation details.
