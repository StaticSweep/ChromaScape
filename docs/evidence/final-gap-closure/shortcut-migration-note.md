# Shortcut MCP Migration Note — captured 2026-08-14

## Status
The self-hosted Shortcut MCP server used in this session is **deprecated and no longer
maintained**. Every response carried:
"DEPRECATED: This self-hosted Shortcut MCP server is deprecated and no longer maintained.
Please migrate to the official hosted server at https://mcp.shortcut.com/mcp."

## Required migration
Release automation that reads/writes Shortcut MUST migrate to the official hosted connector
at https://mcp.shortcut.com/mcp. Until migration is complete, do not rely on this self-hosted
connector for release gating.

## What was synchronized (states kept conservative; no story moved to Done/In Review)
- SC-572 (In Progress): comment with canonical FISHING_TRAINING + harness/golden-manifest
  evidence. Not moved — Tier-B live run + cleanup DoD outstanding.
- SC-574 (To Do): comment with structured conflict/timeout evidence. Not moved — no successful
  real FISHING_TRAINING publish yet (blocked by account-hash divergence).
- SC-189 (In Progress): comment with canonical identity + settings contract + executable
  acceptance criteria. Not moved — never Done without a real fishing run.
- SC-964 created: Builder Goal dispatch hardening story, linked (relates to) SC-572 and SC-574.
- SC-573 / SC-584 / SC-671: not updated — evidence gates for In Review not fully met.

## Blocker (do not mark Done)
Operational publish for account 3720a9a3b5c9f57b is blocked: Micro6 resolves the in-game RSN
hash (b01c7f006bace664) while ChromaScape keys the account by profile-name hash
(3720a9a3b5c9f57b), so the launch stays WAITING_FOR_ACCOUNT. Tracked in SC-964.

## Connector limitation
Creating a Shortcut document via the deprecated connector returned HTTP 403 (write scope
missing). This note is preserved in the evidence bundle instead.
