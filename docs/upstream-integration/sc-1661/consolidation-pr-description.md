# Consolidation baseline for SC-1661

This prerequisite PR publishes the existing local consolidation lineage at `583a05b91687d156b1baa52dbda2b219604c7b39` for review against `integration/chromascape-consolidated-20260901` (inspected at `471262b`). It contains 41 inherited commits, not the SC-1661 routing/attribute carries.

SC-1661 has a separate stacked PR against this branch. Review and merge this consolidation PR before retargeting the SC-1661 PR to the integration branch. No forced target advancement or automatic merge is requested.

Stacked SC-1661 PR: https://github.com/ikiddoi/ChromaScape/pull/4.

The paired baseline evidence in the SC-1661 PR tests candidate `4e0a3d5` (production source identical to `583a05b`) and reconciled `966e0e3`: compilation passes; existing formatting and test failures are documented. Database-backed checks could not execute without Docker/PostgreSQL. This evidence is not full certification of the inherited consolidation changes.

Story: https://app.shortcut.com/leeuwbot/story/1661
Deferred inherited findings: https://app.shortcut.com/leeuwbot/story/1696 and https://app.shortcut.com/leeuwbot/story/1697.
