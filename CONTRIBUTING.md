# Contributing

Thank you for considering contributing to this project.

## How to contribute

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## Guidelines

- Follow the existing code style
- Write tests for new features
- Keep pull requests focused on a single change
- Keep the fast test list in sync: `scripts/run-tests.sh` FAST_TESTS and
  `.github/workflows/ci.yml` unit-test `--tests` filters must list the same
  21 classes; update both when adding a test class
- Never commit regenerable ETL artifacts (`scripts/seed.json.bak`,
  `scripts/SubstanceIndex.json`, `scripts/pharmacology_*.csv` and their
  `scripts/cache/` copies): they are gitignored and rebuilt by the pipeline
- Copy pipeline output to all seed targets in one run: `scripts/seed.json`
  plus the four `psychonautwiki_seed.json` copies and the four
  `dosewiki_slim.json` copies must stay byte-identical (CI checks hashes)
- No em dashes in docs or script output: use hyphens or colons instead
