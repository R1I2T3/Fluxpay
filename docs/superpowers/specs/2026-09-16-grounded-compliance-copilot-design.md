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

## Tests

- A relevant policy question calls the chat port with the retrieved excerpts, returns its generated answer, and preserves citations.
- An irrelevant question returns the fixed scope refusal with no sources and never invokes the chat port.
- An empty corpus returns the same refusal without generation.
- A chat-provider failure is mapped to the controlled service-unavailable API response.
- The Ollama chat adapter sends a non-streaming request with the configured model and parses the generated content.

