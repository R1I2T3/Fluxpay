# Policy Library Management Design

## Goal

Give administrators a clear policy-management workflow: create or import JSON policy drafts at the top of the Policy Library, browse policies in a compact list, and manage a selected policy from focused dialogs.

## Scope

This design changes the existing admin-only Policy Library. It does not introduce file storage, background imports, version history, or a bulk server import endpoint.

## Admin workflow

### Create and JSON import

The first panel on the Policy Library tab is **Add policies**. It contains the existing manual title, category, and content fields plus a JSON file chooser that accepts only `.json` files.

The browser reads the selected file locally. It accepts either one object or an array of objects. Every item must have `title`, `category`, and `content` values:

```json
[
  {
    "title": "High-value transfer review",
    "category": "PAYMENT_REVIEW",
    "content": "Payments above USD 10,000 require manual compliance review."
  }
]
```

The browser never uploads the original file. It turns valid records into editable drafts. The administrator can edit each draft's title, category, and content, remove individual drafts, or add the manual form as another draft. Invalid JSON, missing or blank fields, unsupported categories, titles longer than 200 characters, and duplicate title-plus-content pairs are reported before a server request is made.

Clicking **Add policies** sends the reviewed drafts using the existing create endpoint one at a time. The UI reports how many were created and preserves any draft that fails due to a server validation or duplicate-content conflict, so an administrator can correct it. New policies are intentionally not indexed automatically; the UI explains that indexing occurs from the policy details dialog.

### Browse, inspect, edit, and delete

Below the creation panel, search, category filtering, and refresh precede a compact list. Each row shows the category and title only. Clicking the row, except for its action controls, opens a details dialog.

Each row has two right-aligned controls:

- **Edit** opens an edit dialog with title, category, and content.
- **Delete** opens the existing destructive confirmation dialog and names the selected policy.

The details dialog shows full content, policy ID, document fingerprint, index/rebuild action, current chunks, manual chunk creation, refresh, edit, and delete. Chunks and other source operations therefore stay associated with the policy rather than cluttering the list.

All dialogs use the existing focus-trapping dialog binding, Escape close behavior where applicable, accessible labels, and the existing busy-state protection.

## Backend contract and consistency

Add an admin-only update operation:

```text
PUT /api/policies/{id}
Content-Type: application/json

{
  "title": "High-value transfer review",
  "category": "PAYMENT_REVIEW",
  "content": "Updated policy text"
}
```

It uses the existing `PolicyDocumentRequest` validation and returns the standard `PolicyDocumentResponse` envelope. The endpoint returns the repository's normal not-found response for an unknown UUID and the existing conflict style for a duplicate document hash.

On successful edit, the service recalculates `documentHash`, deletes every active vector generation and indexed chunk for that policy, and persists the changed document in one transaction. This prevents Copilot from grounding an answer in superseded content. The returned document has no indexed chunks until the administrator explicitly rebuilds the index.

Policy create, read, update, delete, index, and manual-chunk endpoints are all protected with `hasRole('ADMIN')`. The frontend continues to hide the tab for non-admin users, but server authorization remains the boundary.

## Components and ownership

| Component | Responsibility |
| --- | --- |
| `PolicyController` | Exposes the secured update endpoint and applies admin authorization consistently to policy operations. |
| `PolicyDocumentService` | Validates and persists edits, calculates hashes, rejects duplicate content, and clears stale index state. |
| `PolicyIndexStore` | Deletes the policy's active vector generations before a changed policy is saved. |
| `flux-api.ts` | Defines `updatePolicy` and the policy draft type used by the UI. |
| `compliance-workspace.ts` | Parses client-side JSON, validates/manages drafts, coordinates sequential create operations, and holds dialog/edit state. |
| `admin.html` | Renders creation/import, list rows, and the policy detail/edit/delete dialogs. |
| `workspace.css` | Replaces the card grid with responsive list-row and draft-preview styling. |

## Failure handling

- A non-JSON file or malformed JSON does not alter existing drafts.
- Import validation errors identify the failing draft position and field.
- A failed individual create leaves that draft visible, marked with its error; successfully created drafts are removed.
- Editing a policy that changed/deleted elsewhere shows the API error and leaves the edit dialog open.
- Deletion keeps the details dialog open if the request fails; on success it closes details, clears Copilot source state for that policy, and refreshes the list.
- A non-admin API request receives the existing authorization denial rather than data or mutation access.

## Testing

Backend tests cover update validation, not-found handling, duplicate hash rejection, hash replacement, stale vector deletion, transaction behavior, and `ADMIN` requirements for every policy endpoint.

Frontend tests cover parsing one-object and array imports, file/JSON/field validation, editable/removable drafts, partial failure preservation, compact-list row selection, action click isolation, details content, edit submission, and delete confirmation.

Manual verification covers a complete UI flow: import a JSON array, edit a draft, add the policies, open one policy, index it, edit it, verify chunks/index are cleared, rebuild the index, and delete it through confirmation.
