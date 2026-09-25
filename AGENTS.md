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
  2. Set the local Micro6 path and, if needed, the machine UUID documented in the restart runbook:
     - `BUILDER_GOAL_MICRO6_PROJECT_DIR=C:\Dev\IdeaProjects\micro6`
  3. Restart from Windows: `cd /d C:\Dev\IdeaProjects\ChromaScape && scripts\restart-chromascape.bat`.
  4. Inspect startup failures at `C:\Dev\IdeaProjects\ChromaScape\chroma_bootrun.log`.
  5. Verify `http://localhost:8090/debugger`.
  6. Confirm `/debugger` loads and the requested local workflow is available.

The current session supplies the skill catalog. Do not pin machine-specific skill paths or
skill selection rules in this repository file.
