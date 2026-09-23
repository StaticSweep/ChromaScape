# Instructions

## Pathing

- Run ChromaScape commands from this native Fedora checkout using POSIX paths. Use the
  canonical Windows project identity only for Felix or Shortcut references when required.

## ChromaScape operations on this Fedora host

This checkout runs natively on Fedora. For FEDORA-03 work, restart ChromaScape
on this Fedora host. Do not route the restart to the Windows machine.

Use docs/CHROMASCAPE_RESTART_RUNBOOK.md and
docs/upstream-integration/sc-1667/fedora-host-native-runbook.md.

From this repository root, check `scripts/fedora/chromascape-fedora.sh status`,
then run `scripts/fedora/chromascape-fedora.sh preflight`. Resolve reported
preflight failures and run `scripts/fedora/chromascape-fedora.sh restart`.
The wrapper stops only the Fedora instance it owns and starts a replacement;
it also starts one when no owned instance exists. Verify
`http://127.0.0.1:8090/debugger` and report the wrapper's log path.

Configuration belongs in the ignored `.env.fedora.local`, copied from
`.env.fedora.example`. If ChromaScape must restart before the client exists,
set `CHROMASCAPE_FEDORA_REQUIRE_TARGET=false` for that restart, then restore
strict preflight before selecting or attaching to a client.

For FEDORA-03 release evidence, use the packaged-runtime qualification
procedure in docs/upstream-integration/fedora-03/qualification-runbook.md.
A development `bootRun` start is not packaged-runtime certification.

## Skills
A skill is a set of local instructions to follow that is stored in a `SKILL.md` file. Below is the list of skills that can be used. Each entry includes a name, description, and file path so you can open the source for full instructions when using a specific skill.

### Available skills
- skill-creator: Guide for creating effective skills. This skill should be used when users want to create a new skill (or update an existing skill) that extends Codex's capabilities with specialized knowledge, workflows, or tool integrations. (file: /home/ikiddoi/.codex/skills/.system/skill-creator/SKILL.md)
- skill-installer: Install Codex skills into $CODEX_HOME/skills from a curated list or a GitHub repo path. Use when a user asks to list installable skills, install a curated skill, or install a skill from another repo (including private repos). (file: /home/ikiddoi/.codex/skills/.system/skill-installer/SKILL.md)

### How to use skills
- Discovery: The list above is the skills available in this session (name + description + file path). Skill bodies live on disk at the listed paths.
- Trigger rules: If the user names a skill (with `$SkillName` or plain text) OR the task clearly matches a skill's description shown above, you must use that skill for that turn. Multiple mentions mean use them all. Do not carry skills across turns unless re-mentioned.
- Missing/blocked: If a named skill isn't in the list or the path can't be read, say so briefly and continue with the best fallback.
- How to use a skill (progressive disclosure):
  1) After deciding to use a skill, open its `SKILL.md`. Read only enough to follow the workflow.
  2) If `SKILL.md` points to extra folders such as `references/`, load only the specific files needed for the request; don't bulk-load everything.
  3) If `scripts/` exist, prefer running or patching them instead of retyping large code blocks.
  4) If `assets/` or templates exist, reuse them instead of recreating from scratch.
- Coordination and sequencing:
  - If multiple skills apply, choose the minimal set that covers the request and state the order you'll use them.
  - Announce which skill(s) you're using and why (one short line). If you skip an obvious skill, say why.
- Context hygiene:
  - Keep context small: summarize long sections instead of pasting them; only load extra files when needed.
  - Avoid deep reference-chasing: prefer opening only files directly linked from `SKILL.md` unless you're blocked.
  - When variants exist (frameworks, providers, domains), pick only the relevant reference file(s) and note that choice.
- Safety and fallback: If a skill can't be applied cleanly (missing files, unclear instructions), state the issue, pick the next-best approach, and continue.
