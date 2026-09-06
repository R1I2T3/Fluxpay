# FluxPay

Phase-0 frozen foundation. See `../Flux_pay/docs/00-initial-setup.md`.

Run order: `python3 scripts/start-infra.py` → `python3 scripts/start-backend.py` → `python3 scripts/seed-demo.py` → `python3 scripts/start-frontend.py` → `python3 scripts/test-all.py`

## Code formatting (pre-commit hook)

Commits auto-format staged Python (**ruff**), JS/TS/CSS/HTML/JSON (**prettier**) and Java (**Spotless / google-java-format**). One-time setup:

```bash
git config core.hooksPath .githooks        # activate the hook
curl -LsSf https://astral.sh/ruff/install.sh | sh   # ruff binary (~/.local/bin), no pip needed
npm install --prefix frontend/fluxpay-ui   # prettier + @devDependencies
```

Manual formatting: `ruff format .` / `npx prettier --write .` / `./mvnw -f backend/pom.xml spotless:apply` (configs: `pyproject.toml`, `.prettierrc.json`, `.prettierignore`, `backend/pom.xml`).

