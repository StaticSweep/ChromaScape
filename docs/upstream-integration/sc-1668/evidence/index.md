# SC-1668 evidence index

Generated host records and logs are not committed by default. Before adding any evidence, redact
usernames, absolute home paths, account names, tokens, raw process command lines, and other secrets.

Committed evidence for this implementation consists of source-controlled tests and the final
verification summary. Real-host evidence slots remain:

- `fedora-primary.json` plus referenced logs — NOT RUN;
- `fedora-secondary.json` plus referenced logs — NOT RUN;
- `windows.json` plus referenced logs — NOT RUN.

The strict validator is the release oracle; prose cannot override it.
