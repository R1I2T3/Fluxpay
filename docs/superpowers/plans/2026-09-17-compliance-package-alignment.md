# Compliance package alignment implementation plan

**Goal:** Integrate the M5 compliance, policy indexing, and copilot code into the backend's established responsibility-based packages.

**Architecture:** Follow the existing `com.fluxpay` controller/service/dto/config/common-contracts/exception/adapter structure. Mirror production packages in tests and preserve HTTP, security, database, and provider behavior.

**Tech stack:** Java 17, Spring Boot, Maven, Oracle, Ollama.

**Requirements:** The user's request to align M5-prefixed code and folders with the main project's structure; existing sibling packages are the authoritative convention.

## Tasks

- [x] Move controllers to `controller`, API and boundary records to `dto`, application services to `service`, configuration to `config`, ports to `common/contracts`, exceptions to `exception`, and Oracle/Ollama implementations to `adapter/persistence` and `adapter/ollama`. Remove M5 class and bean prefixes; update imports and mirrored tests. Keep migration contract tests in `repository`.
- [x] Rename canonical settings to `fluxpay.compliance`, `fluxpay.vector`, and `fluxpay.copilot`, with `FLUXPAY_*` environment variables. Preserve old property/environment settings as YAML fallbacks. Keep the persisted `m5-sentence-v1` algorithm identifier and all versioned SQL files byte-for-byte unchanged. Document the layout, settings, and compatibility exceptions.
- [x] Verify new/legacy property binding and precedence, production wiring, API/security tests, indexing/search/provider tests, and the full backend unit suite from a clean build. Run Python script tests and formatting for changed Java files. Inspect the final diff, package/path agreement, remaining M5 references, and unchanged migration contents.

## Verification commands

```powershell
.\mvnw.cmd -f backend/pom.xml clean test
.\mvnw.cmd -f backend/pom.xml spotless:check
python -B -m unittest discover -s tests
git diff --check
```

Database/Kafka acceptance requires dedicated running services and is not implied by unit-suite results. No schema reset or database migration is needed for this reorganization.

## Verification results

- Clean Maven build: 525 tests discovered, 471 passed, 54 Oracle-dependent tests skipped; zero failures or errors. This includes the five new configuration binding/compatibility checks, production context boot, API/security contracts, and moved service/provider tests.
- Spotless apply/check: all 49 changed Java files formatted and verified using a filename-regex filter. The initial relative-path filter matched no files; the corrected invocation explicitly reported 49 files.
- Python: 38 tests passed in an isolated copy of the unchanged scripts/tests. Running from the checkout exposed two pre-existing test-isolation failures because its local `.env` supplies integration credentials and selects external infrastructure. No scripts, tests, or private `.env` were changed.
- All 373 Java package declarations agree with their directories. Neither source tree retains an `m5` folder or an M5-prefixed Java file.
- Compared all 48 moved/updated existing Java files with their original contents after normalizing the intended package, name, configuration, and formatting changes: no additional logic changes.
- `git diff --check` passed; versioned migration contents are unchanged. Remaining legacy references are configuration fallbacks and their tests, migration filenames/SQL, the persisted chunker identifier, and explanatory documentation.
