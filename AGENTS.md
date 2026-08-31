# Instructions

## Pathing

- Always use Windows pathing for projects located on Windows drives. Use paths such as
  `C:\Dev\IdeaProjects\ChromaScape`, never WSL equivalents such as
  `/mnt/c/Dev/IdeaProjects/ChromaScape`, including when calling Felix MCP or any other tool.

## ChromaScape Operations

- When asked how to restart ChromaScape, use `docs/CHROMASCAPE_RESTART_RUNBOOK.md` as the source of truth.
- Preferred restart command:
  - `cd /d C:\Dev\IdeaProjects\ChromaScape`
  - `scripts\restart-chromascape.bat`
- Run ChromaScape restart commands from Windows, not WSL.
- Machine-specific configuration lives in `.env.local` (git-ignored; `.env.example` documents the
  supported values). The restart script loads `.env.local` before starting `bootRun`:
  1. Copy `.env.example` to `.env.local`.
  2. Set the local Micro6 path and optional local machine UUID:
     - `BUILDER_GOAL_MICRO6_PROJECT_DIR=C:\Dev\IdeaProjects\micro6`
     - `BUILDER_GOAL_LAUNCHER_LOCAL_MACHINE_UUID=1d391d65-6c97-5b77-b676-964f157e8c73`
  3. Restart from Windows: `cd /d C:\Dev\IdeaProjects\ChromaScape && scripts\restart-chromascape.bat`.
  4. Inspect startup failures at `C:\Dev\IdeaProjects\ChromaScape\chroma_bootrun.log`.
  5. Verify `http://localhost:8090/debugger`.
  6. Confirm Launch Host selects `CZC8518C27`, the executor becomes dispatch-ready, and Fishing
     Training can be published.

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
