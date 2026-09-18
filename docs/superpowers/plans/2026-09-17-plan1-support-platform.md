# Plan 1 — Support Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build user ticketing plus admin reports with no other-track conflicts.

**Architecture:** New `support_*` tables (V006 only this track touches), owner-scoped user APIs plus ADMIN-only admin/report APIs following `AdminKycController` annotation pattern. Reports are read-only aggregates over existing tables.

**Tech Stack:** Spring Boot 3.2.5 / JPA / Flyway, JUnit 5 + MockMvc, Oracle JET frontend extension via new files only.

**Spec:** `docs/superpowers/specs/2026-09-17-ticketing-reporting-design.md`

## Global Constraints

- Java 17, `mvnw -f backend/pom.xml spotless:apply` before commit.
- All admin endpoints use `@PreAuthorize("hasRole('ADMIN')")`; user reads return 404 on cross-user access, never 403.
- All POSTs require `Idempotency-Key`; subject 1-120 chars, body 1-4000 chars.
- OWNERSHIP: this track alone creates `V006` and `support_*` / `Ticket*` / `Report*` backend files. Do NOT edit `PayoutController.java`, `WalletController.java`, `ComplianceCaseController.java`, `PolicyController.java`, `appController.ts`, `admin.ts`, `session.ts`. Frontend: only create `tickets` files and append `flux-api.ts` ticket block at end-of-file anchor.
- Contract for Plan 5: this track produces `GET /api/tickets*`, `POST /api/tickets*`, `GET /api/admin/tickets*`, `PUT /api/admin/tickets/{id}`, `GET /api/admin/reports/*`; Plan 5 wires routes/tabs without changing these URLs.

---

### Task 1: V006 tickets schema + entities

**Files:**
- Create: `backend/src/main/resources/db/migration/V006__support_tickets.sql`
- Create: `backend/src/main/java/com/fluxpay/beans/SupportTicket.java`
- Create: `backend/src/main/java/com/fluxpay/beans/TicketMessage.java`
- Create: `backend/src/main/java/com/fluxpay/repository/SupportTicketRepository.java`
- Create: `backend/src/main/java/com/fluxpay/repository/TicketMessageRepository.java`
- Test: `backend/src/test/java/com/fluxpay/repository/SupportTicketRepositoryTest.java`

**Interfaces:**
- Consumes: `users(id)`, `payments(id)` FK targets (read-only).
- Produces: `SupportTicket(status OPEN/IN_PROGRESS/RESOLVED/CLOSED)`, `findByUserIdOrderByCreatedAtDesc(UUID,int)`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void ticketPersistsWithOpenStatus() {
    SupportTicket t = new SupportTicket(userId, null, "Payout stuck", "My payment failed twice");
    assertEquals("OPEN", t.getStatus());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f backend/pom.xml test -Dtest=SupportTicketRepositoryTest -v`
Expected: FAIL with "SupportTicket not defined"

- [ ] **Step 3: Write minimal SQL + entities**

```sql
CREATE TABLE support_tickets (
  id RAW(16) PRIMARY KEY, user_id RAW(16) NOT NULL REFERENCES users(id),
  payment_id VARCHAR2(36) NULL REFERENCES payments(id),
  subject VARCHAR2(120) NOT NULL, body VARCHAR2(4000) NOT NULL,
  status VARCHAR2(20) DEFAULT 'OPEN' NOT NULL CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED')),
  assignee_admin_id RAW(16) NULL REFERENCES users(id),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_tickets_user ON support_tickets(user_id, created_at DESC);
CREATE INDEX idx_tickets_status ON support_tickets(status, created_at DESC);
```

```java
@Entity @Table(name = "support_tickets")
public class SupportTicket {
  @Id private UUID id;
  private UUID userId; private String paymentId;
  private String subject; private String body; private String status = "OPEN";
  public void moveTo(String next) {
    if (status.equals("CLOSED") && !next.equals("OPEN")) throw new IllegalStateException("INVALID_STATUS_TRANSITION");
    this.status = next;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f backend/pom.xml test -Dtest=SupportTicketRepositoryTest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V006__support_tickets.sql backend/src/main/java/com/fluxpay/beans/SupportTicket.java backend/src/test/java/com/fluxpay/repository/SupportTicketRepositoryTest.java
git commit -m "feat(tickets): add V006 support ticket schema"
```

### Task 2: Ticket + report APIs

**Files:**
- Create: `backend/src/main/java/com/fluxpay/service/TicketService.java`
- Create: `backend/src/main/java/com/fluxpay/service/ReportQueryService.java`
- Create: `backend/src/main/java/com/fluxpay/controller/TicketController.java`
- Create: `backend/src/main/java/com/fluxpay/controller/AdminTicketController.java`
- Create: `backend/src/main/java/com/fluxpay/controller/AdminReportController.java`
- Test: `backend/src/test/java/com/fluxpay/controller/TicketControllerMvcTest.java`

**Interfaces:**
- Consumes: `DbPaymentReader.get(paymentId)` + owner check `(id,senderId)` returning 404 on mismatch.
- Produces: URLs listed in plan header; `PUT /api/admin/tickets/{id}` accepts `{status, assigneeAdminId}`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void crossUserTicketReturns404() throws Exception {
  mockMvc.perform(get("/api/tickets/other-user-ticket-id")
    .header("Authorization", "Bearer user-token")).andExpect(status().isNotFound());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f backend/pom.xml test -Dtest=TicketControllerMvcTest#crossUserTicketReturns404 -v`
Expected: FAIL with "No mapping for /api/tickets"

- [ ] **Step 3: Write minimal controllers**

```java
@RestController @RequestMapping("/api/tickets")
public class TicketController {
  @PostMapping public ApiResponse<TicketResponse> create(
    @AuthenticationPrincipal CurrentUser u,
    @RequestHeader("Idempotency-Key") String key,
    @Valid @RequestBody CreateTicketRequest r) {
    return new ApiResponse<>(cid(), tickets.create(u.userId(), r, key));
  }
  @GetMapping("/{id}") public ApiResponse<TicketResponse> get(
    @AuthenticationPrincipal CurrentUser u, @PathVariable UUID id) {
    return new ApiResponse<>("none", tickets.getOwned(u.userId(), id));
  }
}
@RestController @RequestMapping("/api/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportController {
  @GetMapping("/provider-summary") public ApiResponse<List<ProviderRow>> summary(
    @RequestParam String from, @RequestParam String to) {
    return new ApiResponse<>("none", reports.providerSummary(from, to));
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f backend/pom.xml test -Dtest=TicketControllerMvcTest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/controller/TicketController.java backend/src/main/java/com/fluxpay/controller/AdminTicketController.java backend/src/main/java/com/fluxpay/controller/AdminReportController.java
git commit -m "feat(tickets): add ticket and report APIs"
```

### Task 3: Frontend tickets fragment (new files only)

**Files:**
- Create: `frontend/fluxpay-ui/src/ts/viewModels/tickets.ts`
- Create: `frontend/fluxpay-ui/src/ts/views/tickets.html`
- Modify (append-only at EOF anchor): `frontend/fluxpay-ui/src/ts/services/flux-api.ts`
- Test: manual create -> list -> admin resolve flow.

**Interfaces:**
- Consumes: Task 2 URLs. Produces: `viewModels/tickets.ts` exporting `TicketsPage`; Plan 5 mounts route.

- [ ] **Step 1: Append ticket API block**

```ts
// PLAN1-ANCHOR-TICKETS-EOF: do not move; Plan 5 never edits below
export const ticketApi = {
  list: () => fluxApi.get('/api/tickets'),
  create: (b: any) => fluxApi.post('/api/tickets', b),
};
```

- [ ] **Step 2: Create viewModel + view, verify `npm run build` passes**
- [ ] **Step 3: Commit**

```bash
git add frontend/fluxpay-ui/src/ts/viewModels/tickets.ts frontend/fluxpay-ui/src/ts/views/tickets.html frontend/fluxpay-ui/src/ts/services/flux-api.ts
git commit -m "feat(tickets): add user tickets fragment"
```
