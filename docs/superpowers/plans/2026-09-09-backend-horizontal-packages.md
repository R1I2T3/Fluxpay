# Backend Horizontal Packages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace feature-oriented backend package placeholders with fixed horizontal layer packages.

**Architecture:** Remove the empty feature package markers and introduce markers for the approved `beans`, `controller`, `dto`, `repository`, `service`, and `config` layers. Retain `common` and the application bootstrap package unchanged, because they contain working cross-cutting code and Spring Boot scans from `com.fluxpay`.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Maven, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-09-backend-horizontal-packages-design.md`

## Global Constraints

- The fixed backend package set is `beans`, `controller`, `dto`, `repository`, `service`, `config`, and `common`.
- Do not change runtime behavior, routes, dependencies, database migrations, or public APIs.
- `common` remains a cross-cutting package.

---

## File structure

- Delete: `backend/src/main/java/com/fluxpay/{auth,compliance,event,kyc,ledger,payment,policy,recipient,routing,user,wallet}/package-info.java` — obsolete empty vertical package markers.
- Create: `backend/src/main/java/com/fluxpay/{beans,controller,dto,repository,service,config}/package-info.java` — fixed horizontal package markers.
- Verify: `backend/src/main/java/com/fluxpay/FluxPayApplication.java` — remains the component-scan root and is not modified.

### Task 1: Replace the package markers

**Files:**
- Create: `backend/src/main/java/com/fluxpay/beans/package-info.java`
- Create: `backend/src/main/java/com/fluxpay/controller/package-info.java`
- Create: `backend/src/main/java/com/fluxpay/dto/package-info.java`
- Create: `backend/src/main/java/com/fluxpay/repository/package-info.java`
- Create: `backend/src/main/java/com/fluxpay/service/package-info.java`
- Create: `backend/src/main/java/com/fluxpay/config/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/auth/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/compliance/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/event/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/kyc/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/ledger/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/payment/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/policy/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/recipient/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/routing/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/user/package-info.java`
- Delete: `backend/src/main/java/com/fluxpay/wallet/package-info.java`

**Interfaces:**
- Consumes: `com.fluxpay.FluxPayApplication` as the root package for Spring Boot scanning.
- Produces: Empty marker packages with exact declarations `package com.fluxpay.<layer>;`.

- [x] **Step 1: Establish the expected layout before editing**

Run:

```powershell
Get-ChildItem backend/src/main/java/com/fluxpay -Directory | Select-Object -ExpandProperty Name
```

Expected: the eleven obsolete feature package names and `common` are listed; no new horizontal layer packages are listed.

- [x] **Step 2: Remove obsolete feature package markers**

Delete exactly the eleven `package-info.java` files named in **Files**. Do not delete `common` or `FluxPayApplication.java`.

- [x] **Step 3: Create the six horizontal layer markers**

Create each file in **Files** with its exact package declaration, for example:

```java
package com.fluxpay.controller;
```

Use the same form for `beans`, `dto`, `repository`, `service`, and `config`.

- [x] **Step 4: Verify marker declarations and package names**

Run:

```powershell
rg -n "^package com\\.fluxpay\\.(beans|controller|dto|repository|service|config);$" backend/src/main/java/com/fluxpay
Get-ChildItem backend/src/main/java/com/fluxpay -Directory | Select-Object -ExpandProperty Name
```

Expected: one exact declaration per new layer, `common` remains, and none of `auth`, `compliance`, `event`, `kyc`, `ledger`, `payment`, `policy`, `recipient`, `routing`, `user`, or `wallet` remains.

- [x] **Step 5: Compile and test the backend**

Run:

```powershell
./mvnw.cmd -f backend/pom.xml clean test
```

Expected: Maven exits with code 0.

- [x] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/fluxpay
git commit -m "refactor: organize backend into horizontal packages"
```

Expected: the commit includes only the new and removed package-marker files for this migration.

## Plan self-review

- Spec coverage: Task 1 replaces every obsolete feature marker, creates every approved layer other than retained `common`, preserves `FluxPayApplication` and common code, and verifies behavior with a Maven test run.
- Placeholder scan: no placeholder tasks or unspecified files remain.
- Type consistency: every created marker uses the declared `com.fluxpay.<layer>` package name; no public types or interfaces are introduced.
