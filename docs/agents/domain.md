# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill creates them lazily when terms or decisions actually get resolved.

## File structure

Single-context repo (this repo):

```
/
├── GLOSSARY.md
├── docs/adr/
│   ├── 0001-kmp-with-native-ui.md           ← KMP + native UI per platform (accepted)
│   ├── 0002-ktor-instead-of-retrofit.md      ← Ktor Client for shared networking (accepted)
│   ├── 0003-koin-for-di.md                   ← Koin for cross-platform DI (accepted)
│   ├── 0004-room-offline-first.md            ← Room KMP as single source of truth (accepted)
│   ├── 0005-navigation.md                    ← Navigation Compose + NavigationStack (accepted)
│   ├── 0006-call-log-strategy.md             ← Call-log tracking strategy for Arham (proposed)
│   └── 0007-hybrid-feature-modularization.md ← Layers inside Features architecture (accepted)
└── ...
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `GLOSSARY.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (hybrid feature modularization), but worth reopening because…_
