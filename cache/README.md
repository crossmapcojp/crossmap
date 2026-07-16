# Crossmap cache

This directory contains reproducible, machine-local files that make data processing faster and more efficient.
Downloaded HTML, derived candidates, LLM translations, and generated search indexes are grouped by cache type.

Every immediate subdirectory has a committed `README.md` naming the classes and functions that produce or consume
its payload. Cache payloads are ignored by Git; only these README contracts are committed. Versioned source data,
hand/agent-curated catalogs, templates, and configuration remain under `resources/`.
