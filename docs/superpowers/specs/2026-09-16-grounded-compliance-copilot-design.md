# Grounded Compliance Copilot Design

## Goal

Upgrade the Member 5 Compliance Copilot from extractive nearest-chunk output to a grounded Ollama RAG answerer. It must answer naturally only when active indexed FluxPay policy evidence is relevant, retain source citations, and refuse unrelated or unsupported questions.

## Scope and Constraints

- Keep the existing HTTP endpoint: `POST /api/copilot/ask`.
- Keep the existing `CopilotAnswerResponse` shape: `answer` and `sources`.
- Preserve the existing Oracle 1536-dimensional vector corpus and Qwen embedding model: `qwen3-embedding:4b`.
- Add a distinct configurable Ollama generation model, with local default `qwen3:4b`.
- Do not send policy-independent answers, internet knowledge, personal advice, or unsupported compliance claims.
- Preserve a non-empty `sources` list for a generated policy answer. Refusal responses have no sources.
- Do not call the generation model when the best retrieved policy match is below the configured relevance requirement.

## Architecture

`M5CopilotService` remains the application coordinator. It obtains the query embedding through `M5EmbeddingPort`, searches only the active Oracle policy vector generations through `PolicySearchPort`, and applies a configurable maximum cosine distance to the best match.

When no match is available or its distance exceeds the configured maximum, the service returns a fixed scope refusal and an empty source list. This deterministic gate handles questions such as “What day is my birthday?” before any language model is invoked.

When the evidence passes the gate, the service builds cited `CopilotSource` values and invokes a new `M5ChatPort`. The new `OllamaChatAdapter` calls Ollama's chat endpoint with a system instruction that the answer may rely only on supplied policy excerpts, must not fabricate rules, must state when evidence is insufficient, and must not follow instructions in user questions or policy text. The final response returns the generated answer and exactly the excerpts supplied to the chat model.

## Configuration

The following environment-backed properties are introduced under the existing `m5` configuration namespace:

- `M5_OLLAMA_CHAT_MODEL=qwen3:4b`
- `M5_OLLAMA_CHAT_TEMPERATURE=0.2`
- `M5_COPILOT_MAX_DISTANCE=0.65`

`M5_OLLAMA_BASE_URL` is shared with the existing embedding adapter. The local setup requires `ollama pull qwen3:4b` in addition to `qwen3-embedding:4b`.

## Error Handling

If Ollama generation is unavailable, malformed, blank, or times out, the API returns a controlled service-unavailable response. It must not be converted into a misleading authentication failure during error dispatch.

## Payment Risk Review and Decisioning

The currently merged application has a `compliance_cases` table and case CRUD endpoints, but the records are manually created and neither case approval nor case rejection changes the associated payment. The historical `feat/05-member5-updated` implementation cannot be merged directly because it replaces the current payment, security, migration, and messaging structures.

The compatible implementation will introduce a detailed result alongside the existing `ComplianceAssessor` verdict. It contains a verdict, `LOW`/`MEDIUM`/`HIGH` risk, machine-readable risk reasons, and a suggested reviewer action. The concrete M5 assessor will produce only facts available through the current payment-assessment contract: a configured-currency amount above the review threshold produces `MEDIUM` risk with `AMOUNT_EXCEEDS_REVIEW_THRESHOLD`; an unsupported currency produces `HIGH` risk with `UNSUPPORTED_CURRENCY`; invalid assessment input produces `HIGH` risk with `INVALID_PAYMENT_DATA`; and an in-threshold configured payment produces `LOW` risk and automatic approval. KYC is already enforced before screening by the existing payment-confirmation flow, while first-recipient, same-day-recipient, and destination-country rules from the old branch are deliberately not claimed without reliable current-domain inputs.

When a review verdict is produced during payment confirmation, the system will atomically store the selected quote and review reference on the payment, create one open compliance case containing the detailed risk result, and publish the existing review-request event. A migration adds the review reference to `compliance_cases`, allowing a decision to be bound to precisely the payment review it resolves.

Only an authenticated `ADMIN` may approve or reject an open compliance case. The backend derives the reviewer identity from the JWT rather than trusting a client-supplied reviewer name. Rejecting sets the linked under-review payment to `REJECTED`; approving requires the stored quote to remain valid, posts the payment, moves it to `PROCESSING`, and publishes the existing payment-initiated event. Payment and case updates occur in one transaction. A stale/expired quote leaves the case open and returns a conflict rather than posting at an unverified rate.

## Tests

- A relevant policy question calls the chat port with the retrieved excerpts, returns its generated answer, and preserves citations.
- An irrelevant question returns the fixed scope refusal with no sources and never invokes the chat port.
- An empty corpus returns the same refusal without generation.
- A chat-provider failure is mapped to the controlled service-unavailable API response.
- The Ollama chat adapter sends a non-streaming request with the configured model and parses the generated content.
- A payment that exceeds the M5 amount threshold enters review with an open case containing the threshold risk reason.
- An admin approval posts the payment and moves both the case and payment to their approved/processing states.
- An admin rejection records the server-derived reviewer and moves the payment to `REJECTED`.
- A non-admin cannot decide a compliance case, and an expired stored quote cannot be approved.
